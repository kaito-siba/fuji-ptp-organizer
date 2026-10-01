package io.github.kaitosiba.fujiptp.ptp.android

import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import io.github.kaitosiba.fujiptp.ptp.PtpClient
import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import io.github.kaitosiba.fujiptp.ptp.PtpException
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import io.github.kaitosiba.fujiptp.ptp.PtpStorageInfo
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * android.mtp.MtpDevice を使った [PtpClient]。
 *
 * MtpDevice の呼び出しはブロッキングかつ同時実行できないため、専用の単一スレッドで直列に実行する。
 */
class FrameworkPtpClient(private val device: MtpDevice) : PtpClient {

    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "ptp-${device.deviceName}") }.asCoroutineDispatcher()

    override suspend fun deviceInfo(): PtpDeviceInfo = io("GetDeviceInfo") {
        val info = device.deviceInfo ?: return@io null
        PtpDeviceInfo(
            manufacturer = info.manufacturer,
            model = info.model,
            version = info.version,
            serialNumber = info.serialNumber,
            operationsSupported = info.operationsSupported?.toList().orEmpty(),
            eventsSupported = info.eventsSupported?.toList().orEmpty(),
        )
    }

    override suspend fun storageIds(): List<Int> = io("GetStorageIDs") { device.storageIds?.toList() }

    override suspend fun storageInfo(storageId: Int): PtpStorageInfo = io("GetStorageInfo") {
        val info = device.getStorageInfo(storageId) ?: return@io null
        PtpStorageInfo(
            storageId = info.storageId,
            description = info.description,
            volumeIdentifier = info.volumeIdentifier,
            maxCapacity = info.maxCapacity,
            freeSpace = info.freeSpace,
        )
    }

    override suspend fun objectHandles(storageId: Int, format: Int, parent: Int): List<Int> =
        io("GetObjectHandles") { device.getObjectHandles(storageId, format, parent)?.toList() }

    override suspend fun objectInfo(handle: Int): PtpObjectInfo =
        io("GetObjectInfo") { device.getObjectInfo(handle)?.toPtp() }

    override suspend fun thumbnail(handle: Int): ByteArray = io("GetThumb") { device.getThumbnail(handle) }

    override suspend fun partialObject(handle: Int, offset: Long, size: Int): ByteArray = io("GetPartialObject") {
        val buffer = ByteArray(size)
        val read = device.getPartialObject(handle, offset, size.toLong(), buffer)
        buffer.copyOf(read.toInt())
    }

    override suspend fun downloadTo(handle: Int, destination: File) {
        val ok = io("GetObject") { device.importFile(handle, destination.absolutePath) }
        if (!ok) throw PtpException("GetObject failed: handle=$handle")
    }

    override fun close() {
        device.close()
        dispatcher.close()
    }

    /** MtpDevice は失敗時に null を返すので例外に変換する。 */
    private suspend fun <T : Any> io(operation: String, block: () -> T?): T = withContext(dispatcher) {
        val result = try {
            block()
        } catch (e: Exception) {
            throw PtpException("$operation failed: ${e.message}", e)
        }
        result ?: throw PtpException("$operation failed")
    }

    private fun MtpObjectInfo.toPtp() = PtpObjectInfo(
        handle = objectHandle,
        storageId = storageId,
        format = format,
        protectionStatus = protectionStatus,
        compressedSize = compressedSizeLong,
        thumbFormat = thumbFormat,
        thumbCompressedSize = thumbCompressedSizeLong,
        thumbPixWidth = thumbPixWidthLong,
        thumbPixHeight = thumbPixHeightLong,
        imagePixWidth = imagePixWidthLong,
        imagePixHeight = imagePixHeightLong,
        imagePixDepth = imagePixDepthLong,
        parent = parent,
        associationType = associationType,
        associationDesc = associationDesc,
        sequenceNumber = sequenceNumberLong,
        name = name,
        dateCreatedMillis = dateCreated,
        dateModifiedMillis = dateModified,
        keywords = keywords,
    )
}
