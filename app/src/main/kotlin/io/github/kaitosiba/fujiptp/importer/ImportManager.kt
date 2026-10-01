package io.github.kaitosiba.fujiptp.importer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.kaitosiba.fujiptp.camera.ImportPlan
import io.github.kaitosiba.fujiptp.camera.MediaKind
import io.github.kaitosiba.fujiptp.catalog.ActiveCatalog
import io.github.kaitosiba.fujiptp.catalog.CatalogManager
import io.github.kaitosiba.fujiptp.geotag.ExifTimeReader
import io.github.kaitosiba.fujiptp.ptp.PtpOperation
import io.github.kaitosiba.fujiptp.ptp.transfer.ObjectTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ImportProgress(
    val running: Boolean = false,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val failedFiles: Int = 0,
    val skippedFiles: Int = 0,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val currentFile: String? = null,
    /** 終了時のまとめ。null なら表示しない */
    val message: String? = null,
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * 取り込み（カメラ → 端末）を実行する。処理はアプリのスコープで行い、[ImportService] は
 * プロセスを前面に保って通知を出すだけにする。
 */
class ImportManager(
    context: Context,
    private val catalogManager: CatalogManager,
    private val dao: ImportedFileDao,
    private val scope: CoroutineScope,
) {
    private val context: Context = context.applicationContext
    private val writer = MediaStoreWriter(this.context.contentResolver)
    private val workDir = File(this.context.cacheDir, "import")

    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    private var job: Job? = null

    fun importedKeys(cameraSerial: String): Flow<Set<String>> = dao.keysFor(cameraSerial).map { it.toSet() }

    /** @return 開始できたら true（実行中・未接続なら false） */
    fun start(plan: ImportPlan): Boolean {
        if (job?.isActive == true || plan.items.isEmpty()) return false
        val active = catalogManager.active.value ?: return false
        _progress.value = ImportProgress(
            running = true,
            totalFiles = plan.items.size,
            skippedFiles = plan.skippedImported,
            totalBytes = plan.totalBytes,
        )
        ContextCompat.startForegroundService(context, Intent(context, ImportService::class.java))
        job = scope.launch(Dispatchers.IO) { run(active, plan) }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismissMessage() = _progress.update { if (it.running) it else it.copy(message = null) }

    private suspend fun run(active: ActiveCatalog, plan: ImportPlan) {
        val session = active.session
        val supportsPartial = session.deviceInfo.supportsOperation(PtpOperation.GET_PARTIAL_OBJECT)
        var bytesBefore = 0L
        var message: String? = null
        try {
            for (item in plan.items) {
                currentCoroutineContext().ensureActive()
                if (catalogManager.active.value?.session !== session) {
                    message = "カメラが切断されたため中断した"
                    break
                }
                _progress.update { it.copy(currentFile = item.info.name, transferredBytes = bytesBefore) }
                var target: MediaStoreWriter.Target? = null
                try {
                    target = writer.create(item)
                    writer.open(target).use { out ->
                        ObjectTransfer.copy(
                            client = session.client,
                            handle = item.info.handle,
                            size = item.info.compressedSize,
                            supportsPartial = supportsPartial,
                            out = out,
                            workDir = workDir,
                        ) { done -> _progress.update { it.copy(transferredBytes = bytesBefore + done) } }
                    }
                    writer.publish(target)
                    // ジオタグで使う撮影時刻は取り込んだファイル自身の EXIF から読んでおく
                    val exif = if (item.kind == MediaKind.VIDEO) null else ExifTimeReader.read(context.contentResolver, target.uri)
                    dao.upsert(
                        ImportedFileEntity(
                            stableKey = item.stableId.key,
                            cameraSerial = item.stableId.cameraSerial,
                            fileName = item.info.name,
                            kind = item.kind.name,
                            sizeBytes = item.info.compressedSize,
                            contentUri = target.uri.toString(),
                            relativePath = target.relativePath,
                            capturedAt = item.capturedAt?.toString(),
                            importedAtMillis = System.currentTimeMillis(),
                            exifDateTimeOriginal = exif?.dateTimeOriginal,
                            exifSubSecTimeOriginal = exif?.subSecTimeOriginal,
                            exifOffsetTimeOriginal = exif?.offsetTimeOriginal,
                            exifRead = true,
                        ),
                    )
                    _progress.update { it.copy(completedFiles = it.completedFiles + 1) }
                } catch (e: CancellationException) {
                    target?.let { withContext(NonCancellable) { writer.discard(it) } }
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "import failed: ${item.info.name}", e)
                    target?.let(writer::discard)
                    _progress.update { it.copy(failedFiles = it.failedFiles + 1) }
                }
                bytesBefore += item.info.compressedSize
            }
        } catch (e: CancellationException) {
            message = "中止した"
            throw e
        } finally {
            _progress.update {
                it.copy(
                    running = false,
                    currentFile = null,
                    message = message ?: buildString {
                        append("${it.completedFiles} 件を取り込んだ")
                        if (it.failedFiles > 0) append("（失敗 ${it.failedFiles} 件）")
                        if (it.skippedFiles > 0) append("、取込済み ${it.skippedFiles} 件はスキップ")
                    },
                )
            }
        }
    }

    private companion object {
        const val TAG = "FujiPtp"
    }
}
