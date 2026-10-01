package io.github.kaitosiba.fujiptp.thumbnail

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.kaitosiba.fujiptp.ptp.PtpClient
import io.github.kaitosiba.fujiptp.ptp.PtpException
import okio.Buffer

/**
 * カメラ上のサムネイル（GetThumb）を表す Coil のモデル。
 *
 * @property handle 現在のセッションでの ObjectHandle
 * @property cacheKey セッションをまたいで有効なキー（StableObjectId）
 */
data class PtpThumbnail(val handle: Int, val cacheKey: String)

class PtpThumbnailKeyer : Keyer<PtpThumbnail> {
    override fun key(data: PtpThumbnail, options: Options): String = "ptp-thumb:${data.cacheKey}"
}

/**
 * GetThumb で取ったサムネイルを返す Fetcher。
 *
 * Coil はカスタム Fetcher の結果をディスクキャッシュに書かないので、ここで読み書きする。
 * 再接続後もディスクにあればカメラに問い合わせない。
 */
class PtpThumbnailFetcher(
    private val data: PtpThumbnail,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val clientProvider: () -> PtpClient?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val diskCache = imageLoader.diskCache
        val diskKey = "ptp-thumb:${data.cacheKey}"

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

        val client = clientProvider() ?: throw PtpException("カメラに接続していない")
        val bytes = client.thumbnail(data.handle)

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

    class Factory(private val clientProvider: () -> PtpClient?) : Fetcher.Factory<PtpThumbnail> {
        override fun create(data: PtpThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            PtpThumbnailFetcher(data, options, imageLoader, clientProvider)
    }

    private companion object {
        const val MIME_TYPE = "image/jpeg"
    }
}
