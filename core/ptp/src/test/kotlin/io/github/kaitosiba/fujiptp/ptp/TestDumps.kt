package io.github.kaitosiba.fujiptp.ptp

import io.github.kaitosiba.fujiptp.ptp.dump.DeviceDump
import io.github.kaitosiba.fujiptp.ptp.dump.StorageDump
import io.github.kaitosiba.fujiptp.ptp.dump.ThumbnailSample
import java.util.Base64

object TestDumps {
    const val STORAGE = 0x00010001
    private const val DCIM = 1
    private const val FUJI_FOLDER = 2

    val jpegBytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 1, 2, 3)

    fun obj(handle: Int, name: String, format: Int, parent: Int = FUJI_FOLDER, size: Long = 1000) = PtpObjectInfo(
        handle = handle,
        storageId = STORAGE,
        format = format,
        compressedSize = size,
        parent = parent,
        name = name,
        dateCreatedMillis = 1_700_000_000_000 + handle,
    )

    fun x100vi(supportsPartial: Boolean = true): DeviceDump = DeviceDump(
        createdAt = "2026-10-01T00:00:00+09:00",
        source = "test",
        deviceInfo = PtpDeviceInfo(
            manufacturer = "FUJIFILM",
            model = "X100VI",
            version = "1.00",
            serialNumber = "SERIAL",
            operationsSupported = listOf(
                PtpOperation.GET_DEVICE_INFO,
                PtpOperation.GET_OBJECT_HANDLES,
                PtpOperation.GET_OBJECT_INFO,
                PtpOperation.GET_THUMB,
            ) + if (supportsPartial) listOf(PtpOperation.GET_PARTIAL_OBJECT) else emptyList(),
        ),
        storages = listOf(
            StorageDump(
                info = PtpStorageInfo(STORAGE, "SD", null, 64L shl 30, 32L shl 30),
                objects = listOf(
                    obj(DCIM, "DCIM", PtpObjectFormat.ASSOCIATION, parent = 0),
                    obj(FUJI_FOLDER, "100_FUJI", PtpObjectFormat.ASSOCIATION, parent = DCIM),
                    obj(10, "DSCF0001.JPG", PtpObjectFormat.EXIF_JPEG),
                    obj(11, "DSCF0001.RAF", 0xB103),
                    obj(12, "DSCF0002.HIF", PtpObjectFormat.UNDEFINED),
                ),
            ),
        ),
        thumbnails = listOf(
            ThumbnailSample(10, PtpObjectFormat.EXIF_JPEG, "DSCF0001.JPG", jpegBytes.size, Base64.getEncoder().encodeToString(jpegBytes)),
        ),
    )
}
