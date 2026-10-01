package io.github.kaitosiba.fujiptp.ptp.diagnostics

import io.github.kaitosiba.fujiptp.ptp.PtpClient
import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import io.github.kaitosiba.fujiptp.ptp.PtpObjectFormat
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import io.github.kaitosiba.fujiptp.ptp.PtpOperation
import io.github.kaitosiba.fujiptp.ptp.PtpStorageInfo
import io.github.kaitosiba.fujiptp.ptp.dump.DeviceDump
import io.github.kaitosiba.fujiptp.ptp.dump.ExifSample
import io.github.kaitosiba.fujiptp.ptp.dump.HostInfo
import io.github.kaitosiba.fujiptp.ptp.dump.ProbeResult
import io.github.kaitosiba.fujiptp.ptp.dump.ProbeStatus
import io.github.kaitosiba.fujiptp.ptp.dump.StorageDump
import io.github.kaitosiba.fujiptp.ptp.dump.ThumbnailSample
import io.github.kaitosiba.fujiptp.ptp.dump.UsbDump
import io.github.kaitosiba.fujiptp.ptp.toHex16
import io.github.kaitosiba.fujiptp.ptp.toHex32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/** JPEG ファイルから EXIF タグを読む。Android では androidx.exifinterface で実装する。 */
fun interface ExifReader {
    fun read(file: File): Map<String, String>
}

data class DiagnosticsOptions(
    /** ストレージごとに ObjectInfo を取得する上限件数（先頭と末尾から半分ずつ） */
    val maxObjectsPerStorage: Int = 300,
    /** ダンプにサムネイル画像本体を含めるか */
    val includeThumbnails: Boolean = true,
    val maxThumbnailSamples: Int = 6,
    /** 各形式 1 ファイルを丸ごと転送して速度を測るか */
    val measureThroughput: Boolean = true,
    /** EXIF 確認のために GetPartialObject で読むバイト数 */
    val partialReadBytes: Int = 256 * 1024,
)

/**
 * 実機の挙動を確認するための診断（設計ドキュメント §11 の確認事項）を順に実行し、[DeviceDump] にまとめる。
 *
 * 各ステップの失敗は [ProbeResult] として記録し、診断全体は止めない。
 */
class DiagnosticsRunner(
    private val client: PtpClient,
    private val workDir: File,
    private val exifReader: ExifReader? = null,
    private val options: DiagnosticsOptions = DiagnosticsOptions(),
    private val log: (String) -> Unit = {},
) {
    private val probes = mutableListOf<ProbeResult>()
    private val thumbnails = mutableListOf<ThumbnailSample>()
    private val exifSamples = mutableListOf<ExifSample>()

    suspend fun run(
        source: String,
        createdAt: String,
        host: HostInfo? = null,
        usb: UsbDump? = null,
    ): DeviceDump {
        probes.clear()
        thumbnails.clear()
        exifSamples.clear()
        workDir.mkdirs()

        val deviceInfo = probe("device-info", "DeviceInfo 取得") {
            val info = client.deviceInfo()
            info to "manufacturer=${info.manufacturer}, model=${info.model}, version=${info.version}, " +
                "operations=${info.operationsSupported.size}, events=${info.eventsSupported.size}"
        } ?: PtpDeviceInfo(manufacturer = null, model = null, version = null, serialNumber = null)

        record(
            "operations", "対応オペレーション", ProbeStatus.INFO,
            deviceInfo.operationsSupported.joinToString("\n") { "${it.toHex16()} ${PtpOperation.nameOf(it)}" }
                .ifEmpty { "(取得できず)" },
        )
        record(
            "partial-support", "GetPartialObject 対応",
            if (deviceInfo.supportsOperation(PtpOperation.GET_PARTIAL_OBJECT)) ProbeStatus.OK else ProbeStatus.FAILED,
            if (deviceInfo.supportsOperation(PtpOperation.GET_PARTIAL_OBJECT)) {
                "対応: バイト単位の進捗表示・途中再開が可能"
            } else {
                "非対応: ファイル単位の進捗になる"
            },
        )

        val storageIds = probe("storage-ids", "ストレージ一覧") {
            val ids = client.storageIds()
            ids to ids.joinToString { it.toHex32() }.ifEmpty { "(なし: SD カード未挿入?)" }
        }.orEmpty()

        val storages = storageIds.map { storageId -> dumpStorage(storageId) }
        val allObjects = storages.flatMap { it.objects }.filterNot { it.isFolder }

        summarizeFormats(allObjects)
        summarizeCaptureDates(allObjects)
        probeThumbnails(allObjects)
        val exifSource = probePartialRead(deviceInfo, allObjects)
        val downloadedJpeg = probeThroughput(allObjects)
        probeExif(exifSource ?: downloadedJpeg)

        workDir.listFiles()?.forEach { it.delete() }

        return DeviceDump(
            createdAt = createdAt,
            source = source,
            host = host,
            usb = usb,
            deviceInfo = deviceInfo,
            storages = storages,
            probes = probes.toList(),
            thumbnails = thumbnails.toList(),
            exifSamples = exifSamples.toList(),
        )
    }

    private suspend fun dumpStorage(storageId: Int): StorageDump {
        val sid = storageId.toHex32()
        val info = probe("storage-info-$sid", "StorageInfo $sid") {
            val info = client.storageInfo(storageId)
            info to "description=${info.description}, volume=${info.volumeIdentifier}, " +
                "capacity=${info.maxCapacity.mib()}MiB, free=${info.freeSpace.mib()}MiB"
        } ?: PtpStorageInfo(storageId, null, null, 0, 0)

        val allHandles = probe("list-all-$sid", "GetObjectHandles(全階層) $sid") {
            val handles = client.objectHandles(storageId, parent = PtpClient.PARENT_ALL)
            handles to "${handles.size} 件"
        }
        val rootHandles = probe("list-root-$sid", "GetObjectHandles(ルート直下) $sid") {
            val handles = client.objectHandles(storageId, parent = PtpClient.PARENT_ROOT)
            handles to "${handles.size} 件"
        }

        val limit = options.maxObjectsPerStorage
        val objects: List<PtpObjectInfo>
        val truncated: Boolean
        if (!allHandles.isNullOrEmpty()) {
            val sample = (allHandles.take(limit / 2) + allHandles.takeLast(limit - limit / 2)).distinct()
            truncated = sample.size < allHandles.size
            objects = fetchObjectInfos("object-info-$sid", sample, allHandles.size)
        } else if (!rootHandles.isNullOrEmpty()) {
            record(
                "list-all-unsupported-$sid", "全階層一覧", ProbeStatus.FAILED,
                "parent=ALL で取得できないためフォルダを辿る（プロファイルに Quirk 登録が必要）",
            )
            val traversed = traverse(storageId, rootHandles, limit)
            objects = traversed.first
            truncated = traversed.second
        } else {
            objects = emptyList()
            truncated = false
        }

        return StorageDump(
            info = info,
            handleCountAll = allHandles?.size,
            handleCountRoot = rootHandles?.size,
            objects = objects,
            objectsTruncated = truncated,
        )
    }

    private suspend fun fetchObjectInfos(id: String, handles: List<Int>, total: Int): List<PtpObjectInfo> {
        val result = mutableListOf<PtpObjectInfo>()
        var failures = 0
        val start = System.nanoTime()
        for ((index, handle) in handles.withIndex()) {
            currentCoroutineContext().ensureActive()
            runCatchingNonCancel { client.objectInfo(handle) }
                .onSuccess { result += it }
                .onFailure { failures++ }
            if ((index + 1) % 50 == 0) log("ObjectInfo ${index + 1}/${handles.size}")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val perItem = if (handles.isEmpty()) 0.0 else elapsedMs.toDouble() / handles.size
        record(
            id, "ObjectInfo 取得",
            if (failures == 0) ProbeStatus.OK else ProbeStatus.FAILED,
            "%d/%d 件取得 (失敗 %d), 平均 %.1f ms/件, 全 %d 件なら推定 %.1f 秒".format(
                result.size, handles.size, failures, perItem, total, perItem * total / 1000,
            ),
            elapsedMs,
        )
        return result
    }

    private suspend fun traverse(storageId: Int, root: List<Int>, limit: Int): Pair<List<PtpObjectInfo>, Boolean> {
        val result = mutableListOf<PtpObjectInfo>()
        val queue = ArrayDeque(root)
        while (queue.isNotEmpty() && result.size < limit) {
            currentCoroutineContext().ensureActive()
            val info = runCatchingNonCancel { client.objectInfo(queue.removeFirst()) }.getOrNull() ?: continue
            result += info
            if (info.isFolder) {
                runCatchingNonCancel { client.objectHandles(storageId, parent = info.handle) }
                    .onSuccess { queue.addAll(it) }
            }
        }
        return result to queue.isNotEmpty()
    }

    private fun summarizeFormats(objects: List<PtpObjectInfo>) {
        val detail = objects
            .groupBy { it.format to it.extension }
            .entries
            .sortedByDescending { it.value.size }
            .joinToString("\n") { (key, list) ->
                val (format, ext) = key
                val avgSize = list.map { it.compressedSize }.average().toLong()
                "${format.toHex16()} ${PtpObjectFormat.nameOf(format)} .$ext: ${list.size} 件 " +
                    "(平均 ${avgSize.mib()}MiB, thumbFormat=${list.first().thumbFormat.toHex16()})"
            }
            .ifEmpty { "(ファイルなし)" }
        record("format-summary", "ファイル形式の内訳", ProbeStatus.INFO, detail)
    }

    private fun summarizeCaptureDates(objects: List<PtpObjectInfo>) {
        if (objects.isEmpty()) {
            record("capture-date", "ObjectInfo の撮影日時", ProbeStatus.SKIPPED, "ファイルなし")
            return
        }
        val withDate = objects.filter { it.dateCreatedMillis > 0 }
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        val examples = withDate.take(3).joinToString("\n") {
            "${it.name}: created=${formatter.format(Date(it.dateCreatedMillis))}, " +
                "modified=${formatter.format(Date(it.dateModifiedMillis))}"
        }
        record(
            "capture-date", "ObjectInfo の撮影日時",
            if (withDate.size == objects.size) ProbeStatus.OK else ProbeStatus.FAILED,
            "日時あり ${withDate.size}/${objects.size} 件（端末 TZ で解釈された値）\n$examples",
        )
    }

    private suspend fun probeThumbnails(objects: List<PtpObjectInfo>) {
        val representatives = objects.groupBy { it.format to it.extension }.values.map { it.first() }
        if (representatives.isEmpty()) {
            record("thumb", "GetThumb", ProbeStatus.SKIPPED, "ファイルなし")
            return
        }
        for (obj in representatives) {
            probe("thumb-${obj.extension}", "GetThumb (.${obj.extension})") {
                val bytes = client.thumbnail(obj.handle)
                if (options.includeThumbnails && thumbnails.size < options.maxThumbnailSamples) {
                    thumbnails += ThumbnailSample(
                        handle = obj.handle,
                        objectFormat = obj.format,
                        fileName = obj.name,
                        size = bytes.size,
                        base64 = Base64.getEncoder().encodeToString(bytes),
                    )
                }
                Unit to "${obj.name}: ${bytes.size} bytes, 形式=${detectMagic(bytes)}"
            }
        }
    }

    /** @return EXIF 確認に使える JPEG 先頭部分のファイル */
    private suspend fun probePartialRead(info: PtpDeviceInfo, objects: List<PtpObjectInfo>): JpegSample? {
        if (!info.supportsOperation(PtpOperation.GET_PARTIAL_OBJECT)) {
            record("partial-read", "GetPartialObject 読み出し", ProbeStatus.SKIPPED, "非対応")
            return null
        }
        objects.firstOrNull { it.extension == "raf" }?.let { raf ->
            probe("partial-raf-header", "RAF ヘッダ部分読み") {
                val bytes = client.partialObject(raf.handle, 0, 512)
                Unit to "${raf.name}: ${bytes.size} bytes, 形式=${detectMagic(bytes)}"
            }
        }
        val jpeg = objects.firstOrNull { it.isJpeg() } ?: return null
        return probe("partial-jpeg", "JPEG 先頭 ${options.partialReadBytes / 1024}KiB 部分読み") {
            val bytes = client.partialObject(jpeg.handle, 0, options.partialReadBytes)
            val file = File(workDir, "partial-${jpeg.name}")
            file.writeBytes(bytes)
            JpegSample(jpeg, file) to "${jpeg.name}: ${bytes.size} bytes, 形式=${detectMagic(bytes)}"
        }
    }

    /** @return 丸ごと転送した JPEG（EXIF 確認のフォールバック用） */
    private suspend fun probeThroughput(objects: List<PtpObjectInfo>): JpegSample? {
        if (!options.measureThroughput) return null
        var jpeg: JpegSample? = null
        val targets = listOfNotNull(
            objects.firstOrNull { it.isJpeg() },
            objects.firstOrNull { it.extension == "raf" },
            objects.firstOrNull { it.extension == "hif" || it.extension == "heif" || it.extension == "heic" },
        )
        for (obj in targets) {
            probe("download-${obj.extension}", "転送速度 (.${obj.extension})") {
                val file = File(workDir, "full-${obj.name}")
                val start = System.nanoTime()
                client.downloadTo(obj.handle, file)
                val seconds = (System.nanoTime() - start) / 1e9
                val size = file.length()
                if (obj.isJpeg()) jpeg = JpegSample(obj, file) else file.delete()
                Unit to "%s: %.1f MiB, %.2f 秒, %.1f MiB/s (ObjectInfo 上のサイズ %d, 実サイズ %d)".format(
                    obj.name, size / 1048576.0, seconds, size / 1048576.0 / seconds.coerceAtLeast(1e-6),
                    obj.compressedSize, size,
                )
            }
        }
        return jpeg
    }

    private fun probeExif(sample: JpegSample?) {
        val reader = exifReader
        if (reader == null || sample == null) {
            record("exif", "EXIF (撮影時刻・TZ)", ProbeStatus.SKIPPED, "JPEG が取得できなかった")
            return
        }
        val tags = try {
            reader.read(sample.file)
        } catch (e: Exception) {
            record("exif", "EXIF (撮影時刻・TZ)", ProbeStatus.FAILED, e.toString())
            return
        }
        exifSamples += ExifSample(handle = sample.info.handle, fileName = sample.info.name, tags = tags)
        val hasOffset = !tags["OffsetTimeOriginal"].isNullOrBlank()
        record(
            "exif", "EXIF (撮影時刻・TZ)",
            if (hasOffset) ProbeStatus.OK else ProbeStatus.INFO,
            buildString {
                append(if (hasOffset) "OffsetTimeOriginal あり" else "OffsetTimeOriginal なし: カメラ TZ の設定が必要")
                tags.forEach { (k, v) -> append("\n$k=$v") }
            },
        )
    }

    private suspend fun <T> probe(id: String, title: String, block: suspend () -> Pair<T, String>): T? {
        log("▶ $title")
        val start = System.nanoTime()
        return try {
            val (value, detail) = block()
            record(id, title, ProbeStatus.OK, detail, (System.nanoTime() - start) / 1_000_000)
            value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(id, title, ProbeStatus.FAILED, e.toString(), (System.nanoTime() - start) / 1_000_000)
            null
        }
    }

    private fun record(id: String, title: String, status: ProbeStatus, detail: String, durationMs: Long? = null) {
        // 同じ拡張子で形式コードが異なる場合などに ID が重複しないようにする
        val duplicates = probes.count { it.id == id || it.id.startsWith("$id#") }
        val uniqueId = if (duplicates == 0) id else "$id#${duplicates + 1}"
        probes += ProbeResult(uniqueId, title, status, detail, durationMs)
        log("  [$status] $title: ${detail.lineSequence().first()}")
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private class JpegSample(val info: PtpObjectInfo, val file: File)

    private fun PtpObjectInfo.isJpeg(): Boolean =
        format == PtpObjectFormat.EXIF_JPEG || extension == "jpg" || extension == "jpeg"

    private fun Long.mib(): Long = this / (1024 * 1024)

    companion object {
        fun detectMagic(bytes: ByteArray): String {
            fun startsWith(prefix: String, at: Int = 0) =
                bytes.size >= at + prefix.length && String(bytes, at, prefix.length, Charsets.ISO_8859_1) == prefix
            return when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "JPEG"
                startsWith("FUJIFILMCCD-RAW") -> "RAF"
                startsWith("ftyp", at = 4) -> "ISO-BMFF(${String(bytes, 8, minOf(4, bytes.size - 8), Charsets.ISO_8859_1)})"
                startsWith("II*\u0000") || startsWith("MM\u0000*") -> "TIFF"
                bytes.isEmpty() -> "(空)"
                else -> "不明(" + bytes.take(8).joinToString("") { "%02X".format(it) } + ")"
            }
        }
    }
}
