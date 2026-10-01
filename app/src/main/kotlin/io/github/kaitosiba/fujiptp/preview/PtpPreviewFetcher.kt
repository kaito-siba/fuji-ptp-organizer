package io.github.kaitosiba.fujiptp.preview

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.kaitosiba.fujiptp.camera.MediaKind
import io.github.kaitosiba.fujiptp.camera.RafHeader
import io.github.kaitosiba.fujiptp.connection.CameraSession
import io.github.kaitosiba.fujiptp.ptp.PtpException
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import io.github.kaitosiba.fujiptp.ptp.PtpOperation
import io.github.kaitosiba.fujiptp.ptp.transfer.ObjectTransfer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.security.MessageDigest

/**
 * プレビュー用の画像。JPEG / HEIF は本体を、RAF は埋め込み JPEG を取得する。
 *
 * @property cacheKey StableObjectId.key
 */
data class PtpPreview(val cacheKey: String, val info: PtpObjectInfo, val kind: MediaKind) {
    val supported: Boolean
        get() = kind == MediaKind.JPEG || kind == MediaKind.HEIF || (kind == MediaKind.RAW && info.extension == "raf")
}

class PtpPreviewKeyer : Keyer<PtpPreview> {
    override fun key(data: PtpPreview, options: Options): String = "ptp-preview:${data.cacheKey}"
}

/**
 * プレビュー画像をカメラから取得し、[directory] にファイルとして置いて返す。
 *
 * 1 枚 10 MB 前後あるので Coil のディスクキャッシュとは別に置き、合計が上限を超えたら古いものから消す。
 * 向きは画像内の EXIF に従って Coil が回転する。
 */
class PtpPreviewFetcher(
    private val data: PtpPreview,
    private val directory: File,
    private val sessionProvider: () -> CameraSession?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val file = File(directory, fileNameFor(data.cacheKey))
        if (!file.exists()) {
            download(file)
            trim()
        } else {
            file.setLastModified(System.currentTimeMillis())
        }
        return SourceFetchResult(
            source = ImageSource(file = file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
            mimeType = if (data.kind == MediaKind.HEIF) "image/heif" else "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun download(file: File) {
        if (!data.supported) throw PtpException("プレビュー非対応の形式: ${data.info.name}")
        val session = sessionProvider() ?: throw PtpException("カメラに接続していない")
        val client = session.client
        val supportsPartial = session.deviceInfo.supportsOperation(PtpOperation.GET_PARTIAL_OBJECT)
        directory.mkdirs()
        val temp = File(directory, "${file.name}.tmp")
        try {
            temp.outputStream().use { out ->
                if (data.kind == MediaKind.RAW) {
                    if (!supportsPartial) throw PtpException("RAF のプレビューには GetPartialObject が必要")
                    val header = client.partialObject(data.info.handle, 0, RafHeader.SIZE)
                    val (offset, length) = RafHeader.embeddedJpeg(header)
                        ?: throw PtpException("RAF の埋め込み JPEG が見つからない")
                    ObjectTransfer.copyRange(client, data.info.handle, offset, length, out)
                } else {
                    ObjectTransfer.copy(
                        client, data.info.handle, data.info.compressedSize, supportsPartial, out, directory,
                    )
                }
            }
            if (!temp.renameTo(file)) throw PtpException("プレビューを保存できない")
        } finally {
            temp.delete()
        }
    }

    private fun trim() {
        val files = directory.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= TRIM_TO_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    class Factory(
        private val directory: File,
        private val sessionProvider: () -> CameraSession?,
    ) : Fetcher.Factory<PtpPreview> {
        override fun create(data: PtpPreview, options: Options, imageLoader: ImageLoader): Fetcher =
            PtpPreviewFetcher(data, directory, sessionProvider)
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
        const val TRIM_TO_BYTES = 384L * 1024 * 1024

        fun fileNameFor(key: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            return digest.joinToString("") { "%02x".format(it) } + ".img"
        }
    }
}
