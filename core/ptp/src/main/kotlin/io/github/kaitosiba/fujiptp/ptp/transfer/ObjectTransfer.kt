package io.github.kaitosiba.fujiptp.ptp.transfer

import io.github.kaitosiba.fujiptp.ptp.PtpClient
import io.github.kaitosiba.fujiptp.ptp.PtpException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream

/**
 * オブジェクト本体を [OutputStream] に書き出す。
 *
 * GetPartialObject に対応していればチャンクごとに読み、バイト単位で進捗を返す（途中キャンセルも効く）。
 * 非対応なら一時ファイルに GetObject してからコピーする（進捗はコピー時のみ）。
 */
object ObjectTransfer {
    const val DEFAULT_CHUNK_SIZE: Int = 4 * 1024 * 1024

    /** @return 書き出したバイト数 */
    suspend fun copy(
        client: PtpClient,
        handle: Int,
        size: Long,
        supportsPartial: Boolean,
        out: OutputStream,
        workDir: File,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onProgress: (Long) -> Unit = {},
    ): Long = if (supportsPartial) {
        copyRange(client, handle, 0, size, out, chunkSize, onProgress)
    } else {
        copyViaTempFile(client, handle, out, workDir, onProgress)
    }

    /**
     * オブジェクトの [start] から [length] バイトを GetPartialObject で書き出す（RAF の埋め込み JPEG など）。
     *
     * @param onProgress 書き出し済みのバイト数（[start] からの相対）
     */
    suspend fun copyRange(
        client: PtpClient,
        handle: Int,
        start: Long,
        length: Long,
        out: OutputStream,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onProgress: (Long) -> Unit = {},
    ): Long {
        var done = 0L
        while (done < length) {
            currentCoroutineContext().ensureActive()
            val size = minOf(chunkSize.toLong(), length - done).toInt()
            val bytes = client.partialObject(handle, start + done, size)
            if (bytes.isEmpty()) throw PtpException("転送が途中で終わった: $done / $length bytes")
            out.write(bytes)
            done += bytes.size
            onProgress(done)
        }
        return done
    }

    private suspend fun copyViaTempFile(
        client: PtpClient,
        handle: Int,
        out: OutputStream,
        workDir: File,
        onProgress: (Long) -> Unit,
    ): Long {
        workDir.mkdirs()
        val temp = File.createTempFile("transfer-", ".tmp", workDir)
        try {
            client.downloadTo(handle, temp)
            var copied = 0L
            temp.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    onProgress(copied)
                }
            }
            return copied
        } finally {
            temp.delete()
        }
    }
}
