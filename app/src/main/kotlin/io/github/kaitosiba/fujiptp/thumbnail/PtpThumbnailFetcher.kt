package io.github.kaitosiba.fujiptp.thumbnail

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.kaitosiba.fujiptp.camera.MediaKind
import io.github.kaitosiba.fujiptp.camera.OrientationReader
import io.github.kaitosiba.fujiptp.connection.CameraSession
import io.github.kaitosiba.fujiptp.ptp.PtpException
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import io.github.kaitosiba.fujiptp.ptp.PtpOperation
import okio.Buffer

/**
 * カメラ上のサムネイル（GetThumb）を表す Coil のモデル。
 *
 * @property cacheKey セッションをまたいで有効なキー（StableObjectId）
 * @property info 現在のセッションでの ObjectInfo
 */
data class PtpThumbnail(val cacheKey: String, val info: PtpObjectInfo, val kind: MediaKind)

/** 黒帯除去・向き補正を入れたときに版を上げ、古いキャッシュを使わないようにする */
private const val CACHE_KEY_PREFIX = "ptp-thumb-v2:"

class PtpThumbnailKeyer : Keyer<PtpThumbnail> {
    override fun key(data: PtpThumbnail, options: Options): String = CACHE_KEY_PREFIX + data.cacheKey
}

/**
 * GetThumb で取ったサムネイルを、黒帯を除いて EXIF Orientation どおりの向きにして返す Fetcher。
 *
 * 向きは本体ファイルの先頭を GetPartialObject で読んで判定する（サムネイル自体には EXIF が無い）。
 * Coil はカスタム Fetcher の結果をディスクキャッシュに書かないので、加工後の画像をここで読み書きする。
 * 再接続後もディスクにあればカメラに問い合わせない。
 */
class PtpThumbnailFetcher(
    private val data: PtpThumbnail,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val sessionProvider: () -> CameraSession?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val diskCache = imageLoader.diskCache
        val diskKey = CACHE_KEY_PREFIX + data.cacheKey

        diskCache?.openSnapshot(diskKey)?.let { snapshot ->
            return SourceFetchResult(
                source = ImageSource(
                    file = snapshot.data,
                    fileSystem = diskCache.fileSystem,
                    diskCacheKey = diskKey,
                    closeable = snapshot,
                ),
                mimeType = MIME_TYPE,
                dataSource = DataSource.DISK,
            )
        }

        val session = sessionProvider() ?: throw PtpException("カメラに接続していない")
        val client = session.client
        val thumbnail = client.thumbnail(data.info.handle)
        val orientation = if (session.deviceInfo.supportsOperation(PtpOperation.GET_PARTIAL_OBJECT)) {
            OrientationReader(client).read(data.info, data.kind)
        } else {
            null
        }
        val bytes = ThumbnailNormalizer.normalize(
            thumbnail, data.info.imagePixWidth, data.info.imagePixHeight, orientation,
        )

        diskCache?.openEditor(diskKey)?.let { editor ->
            try {
                diskCache.fileSystem.write(editor.data) { write(bytes) }
                editor.commit()
            } catch (e: Exception) {
                editor.abort()
            }
        }

        return SourceFetchResult(
            source = ImageSource(source = Buffer().write(bytes), fileSystem = options.fileSystem),
            mimeType = MIME_TYPE,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val sessionProvider: () -> CameraSession?) : Fetcher.Factory<PtpThumbnail> {
        override fun create(data: PtpThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            PtpThumbnailFetcher(data, options, imageLoader, sessionProvider)
    }

    private companion object {
        const val MIME_TYPE = "image/jpeg"
    }
}
