package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpClient
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import io.github.kaitosiba.fujiptp.ptp.toHex32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.ZoneId

data class CatalogState(
    val phase: Phase = Phase.IDLE,
    /** 全ストレージの ObjectHandle 数（フォルダを含む） */
    val totalObjects: Int = 0,
    /** ObjectInfo が揃った数（キャッシュ分を含む） */
    val loadedObjects: Int = 0,
    /** キャッシュから復元できた数 */
    val fromCache: Int = 0,
    /** ObjectInfo の取得に失敗した数 */
    val failedObjects: Int = 0,
    /** 新しい順 */
    val shots: List<Shot> = emptyList(),
    val error: String? = null,
) {
    enum class Phase { IDLE, LISTING, LOADING, COMPLETE, FAILED }
}

/**
 * カメラ内の写真一覧。ObjectInfo を新しい順に 1 件ずつ取得し、途中経過を [state] に流す。
 *
 * ObjectInfo は 1 件あたり数十 ms かかる（X100VI で約 21 ms）ため、取得済みのものを [store] に保存し、
 * 次回の接続では数件を抜き取り検査して一致すればキャッシュを使い、新しいハンドルだけを取得する。
 */
class CameraCatalog(
    private val client: PtpClient,
    private val profile: CameraProfile,
    private val cameraSerial: String,
    private val store: ObjectInfoStore,
    private val hostZone: ZoneId = ZoneId.systemDefault(),
    private val options: Options = Options(),
) {
    data class Options(
        /** キャッシュの抜き取り検査で取り直す件数 */
        val validationSamples: Int = 8,
        /** この件数ごとにキャッシュへ保存する */
        val saveEvery: Int = 300,
        /** この件数ごとに [state] を更新する */
        val publishEvery: Int = 40,
    )

    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    /** 一覧を読み込む。完了するかキャンセルされるまで戻らない。 */
    suspend fun load() {
        _state.value = CatalogState(phase = CatalogState.Phase.LISTING)
        val objects = LinkedHashMap<Int, PtpObjectInfo>()
        var total = 0
        var fromCache = 0
        var failed = 0
        try {
            val handlesByStorage = client.storageIds().associateWith { client.objectHandles(it) }
            total = handlesByStorage.values.sumOf { it.size }

            for ((storageId, handles) in handlesByStorage) {
                val reused = reuseCached(storeKey(storageId), handles)
                objects.putAll(reused)
                fromCache += reused.size
            }
            publish(CatalogState.Phase.LOADING, total, objects, fromCache, failed)

            // ハンドルは古い順に振られているので、新しい写真から先に見せるため逆順に取る
            val pending = handlesByStorage.values.flatMap { it.asReversed() }.filterNot { it in objects }
            var sinceSave = 0
            for ((index, handle) in pending.withIndex()) {
                currentCoroutineContext().ensureActive()
                try {
                    objects[handle] = client.objectInfo(handle)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
                if (++sinceSave >= options.saveEvery) {
                    save(objects.values)
                    sinceSave = 0
                }
                if ((index + 1) % options.publishEvery == 0) {
                    publish(CatalogState.Phase.LOADING, total, objects, fromCache, failed)
                }
            }
            save(objects.values)
            publish(CatalogState.Phase.COMPLETE, total, objects, fromCache, failed)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { save(objects.values) }
            throw e
        } catch (e: Exception) {
            save(objects.values)
            _state.value = _state.value.copy(phase = CatalogState.Phase.FAILED, error = e.message ?: e.toString())
        }
    }

    private suspend fun reuseCached(key: String, handles: List<Int>): Map<Int, PtpObjectInfo> {
        val cached = runCatching { store.load(key) }.getOrNull()?.associateBy { it.handle } ?: return emptyMap()
        val common = handles.filter { it in cached }
        if (common.isEmpty()) return emptyMap()
        for (handle in evenlySpaced(common, options.validationSamples)) {
            val fresh = try {
                client.objectInfo(handle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return emptyMap()
            }
            // カードの入れ替えやファイル削除でハンドルの対応が変わっていたらキャッシュを捨てる
            if (fresh != cached[handle]) return emptyMap()
        }
        return common.associateWith { cached.getValue(it) }
    }

    private fun save(objects: Collection<PtpObjectInfo>) {
        objects.groupBy { it.storageId }.forEach { (storageId, list) ->
            runCatching { store.save(storeKey(storageId), list) }
        }
    }

    private fun publish(
        phase: CatalogState.Phase,
        total: Int,
        objects: Map<Int, PtpObjectInfo>,
        fromCache: Int,
        failed: Int,
    ) {
        _state.value = CatalogState(
            phase = phase,
            totalObjects = total,
            loadedObjects = objects.size,
            fromCache = fromCache,
            failedObjects = failed,
            shots = groupIntoShots(objects.values.toList(), profile, hostZone).sortedNewestFirst(),
        )
    }

    private fun storeKey(storageId: Int) = "$cameraSerial-${storageId.toHex32()}"

    internal companion object {
        fun <T> evenlySpaced(items: List<T>, count: Int): List<T> {
            if (items.size <= count) return items
            if (count <= 1) return listOf(items.first())
            return (0 until count).map { items[it * (items.size - 1) / (count - 1)] }.distinct()
        }
    }
}
