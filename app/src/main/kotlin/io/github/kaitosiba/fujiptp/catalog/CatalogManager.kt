package io.github.kaitosiba.fujiptp.catalog

import android.content.Context
import android.util.Log
import io.github.kaitosiba.fujiptp.camera.CameraCatalog
import io.github.kaitosiba.fujiptp.camera.FileObjectInfoStore
import io.github.kaitosiba.fujiptp.camera.InMemoryObjectInfoStore
import io.github.kaitosiba.fujiptp.connection.CameraConnectionManager
import io.github.kaitosiba.fujiptp.connection.CameraSession
import io.github.kaitosiba.fujiptp.connection.ConnectionState
import io.github.kaitosiba.fujiptp.connection.SessionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId

/** 接続中のセッションと、その写真一覧。 */
class ActiveCatalog(val session: CameraSession, val catalog: CameraCatalog) {
    /** サムネイル等のキャッシュキーに使うカメラの識別子 */
    val cameraSerial: String get() = session.cameraSerial
}

val CameraSession.cameraSerial: String
    get() = deviceInfo.serialNumber?.takeIf { it.isNotBlank() } ?: "${deviceInfo.model ?: "unknown"}-${source.name}"

/** セッションが開いたら一覧の読み込みを始め、切断されたら止める。 */
class CatalogManager(
    context: Context,
    connectionManager: CameraConnectionManager,
    private val scope: CoroutineScope,
) {
    private val storeDir = File(context.applicationContext.filesDir, "object-info")
    private val _active = MutableStateFlow<ActiveCatalog?>(null)
    val active: StateFlow<ActiveCatalog?> = _active.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            connectionManager.state
                .map { (it as? ConnectionState.Connected)?.session }
                .distinctUntilChanged()
                .collect { session -> switchTo(session) }
        }
    }

    /** 一覧を読み直す（キャッシュがあれば差分だけ取得する）。 */
    fun reload() {
        val active = _active.value ?: return
        start(active)
    }

    private fun switchTo(session: CameraSession?) {
        job?.cancel()
        job = null
        if (session == null) {
            _active.value = null
            return
        }
        val store = when (session.source) {
            SessionSource.USB -> FileObjectInfoStore(storeDir)
            SessionSource.DEMO -> InMemoryObjectInfoStore()
        }
        val catalog = CameraCatalog(
            client = session.client,
            profile = session.profile.profile,
            cameraSerial = session.cameraSerial,
            store = store,
            hostZone = ZoneId.systemDefault(),
        )
        val active = ActiveCatalog(session, catalog)
        _active.value = active
        start(active)
    }

    private fun start(active: ActiveCatalog) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "catalog load start: ${active.cameraSerial}")
            active.catalog.load()
            val state = active.catalog.state.value
            Log.i(TAG, "catalog load ${state.phase}: ${state.loadedObjects}/${state.totalObjects} (cache ${state.fromCache})")
        }
    }

    private companion object {
        const val TAG = "FujiPtp"
    }
}
