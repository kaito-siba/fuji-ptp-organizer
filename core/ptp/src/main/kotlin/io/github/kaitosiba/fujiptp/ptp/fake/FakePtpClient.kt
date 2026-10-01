package io.github.kaitosiba.fujiptp.ptp.fake

import io.github.kaitosiba.fujiptp.ptp.PtpClient
import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import io.github.kaitosiba.fujiptp.ptp.PtpException
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import io.github.kaitosiba.fujiptp.ptp.PtpStorageInfo
import io.github.kaitosiba.fujiptp.ptp.dump.DeviceDump
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Base64

/**
 * [DeviceDump] を元にカメラを模倣する [PtpClient]。エミュレータ上のデモモードとテストで使う。
 *
 * ファイル本体はダンプに含まれないため、ダウンロードや部分読み出しではサムネイル（なければゼロ埋め）を返す。
 */
class FakePtpClient(
    private val dump: DeviceDump,
    private val latencyMillis: Long = 0,
) : PtpClient {

    private val mutex = Mutex()
    private var closed = false

    private val objects: Map<Int, PtpObjectInfo> =
        dump.storages.flatMap { it.objects }.associateBy { it.handle }

    private val thumbnailsByHandle: Map<Int, ByteArray> = dump.thumbnails
        .filter { it.base64 != null }
        .associate { it.handle to Base64.getDecoder().decode(it.base64) }

    private val thumbnailsByFormat: Map<Int, ByteArray> = dump.thumbnails
        .filter { it.base64 != null }
        .groupBy { it.objectFormat }
        .mapValues { (_, samples) -> Base64.getDecoder().decode(samples.first().base64) }

    override suspend fun deviceInfo(): PtpDeviceInfo = op { dump.deviceInfo }

    override suspend fun storageIds(): List<Int> = op { dump.storages.map { it.info.storageId } }

    override suspend fun storageInfo(storageId: Int): PtpStorageInfo = op {
        dump.storages.firstOrNull { it.info.storageId == storageId }?.info
            ?: throw PtpException("storage not found: $storageId")
    }

    override suspend fun objectHandles(storageId: Int, format: Int, parent: Int): List<Int> = op {
        objects.values
            .asSequence()
            .filter { it.storageId == storageId }
            .filter { format == 0 || it.format == format }
            .filter {
                when (parent) {
                    PtpClient.PARENT_ALL -> true
                    PtpClient.PARENT_ROOT -> it.parent == 0
                    else -> it.parent == parent
                }
            }
            .map { it.handle }
            .sorted()
            .toList()
    }

    override suspend fun objectInfo(handle: Int): PtpObjectInfo = op { requireObject(handle) }

    override suspend fun thumbnail(handle: Int): ByteArray = op {
        val info = requireObject(handle)
        if (info.isFolder) throw PtpException("no thumbnail for folder: $handle")
        thumbnailsByHandle[handle] ?: thumbnailsByFormat[info.format]
            ?: throw PtpException("no thumbnail: $handle")
    }

    override suspend fun partialObject(handle: Int, offset: Long, size: Int): ByteArray = op {
        val content = syntheticContent(requireObject(handle))
        if (offset >= content.size) return@op ByteArray(0)
        content.copyOfRange(offset.toInt(), minOf(content.size, offset.toInt() + size))
    }

    override suspend fun downloadTo(handle: Int, destination: File) = op {
        destination.writeBytes(syntheticContent(requireObject(handle)))
    }

    override fun close() {
        closed = true
    }

    private fun requireObject(handle: Int): PtpObjectInfo =
        objects[handle] ?: throw PtpException("object not found: $handle")

    private fun syntheticContent(info: PtpObjectInfo): ByteArray =
        thumbnailsByHandle[info.handle] ?: thumbnailsByFormat[info.format]
            ?: ByteArray(minOf(info.compressedSize, 64L * 1024).toInt())

    private suspend fun <T> op(block: () -> T): T = mutex.withLock {
        if (closed) throw PtpException("session closed")
        if (latencyMillis > 0) delay(latencyMillis)
        block()
    }
}
