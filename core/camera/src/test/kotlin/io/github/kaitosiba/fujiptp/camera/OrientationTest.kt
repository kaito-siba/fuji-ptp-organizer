package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpClient
import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import io.github.kaitosiba.fujiptp.ptp.PtpException
import io.github.kaitosiba.fujiptp.ptp.PtpObjectFormat
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import io.github.kaitosiba.fujiptp.ptp.PtpStorageInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class OrientationTest {

    /** Pillow で EXIF Orientation を書いた JPEG（ビッグエンディアン "MM"） */
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/exif/$name")).use { it.readBytes() }

    /** APP0 (JFIF) の後ろにリトルエンディアン "II" の APP1 を置いた JPEG 先頭 */
    private fun littleEndianJpeg(orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0, // ヘッダ, IFD0 は 8
            2, 0, // エントリ数
            0x0F, 0x01, 2, 0, 1, 0, 0, 0, 0, 0, 0, 0, // Make (ダミー)
            0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0, // Orientation
            0, 0, 0, 0,
        )
        val app1 = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val app0 = "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
        fun segment(marker: Int, body: ByteArray): ByteArray {
            val length = body.size + 2
            return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + body
        }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xE0, app0) + segment(0xE1, app1) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2)
    }

    @Test
    fun `reads orientation written by another tool`() {
        for (o in listOf(1, 3, 6, 8)) {
            assertEquals(o, JpegExif.readOrientation(fixture("orientation-$o.jpg")))
        }
        assertNull(JpegExif.readOrientation(fixture("no-exif.jpg")))
    }

    @Test
    fun `reads little endian exif after JFIF segment`() {
        assertEquals(6, JpegExif.readOrientation(littleEndianJpeg(6)))
    }

    @Test
    fun `truncated or non JPEG data yields null`() {
        assertNull(JpegExif.readOrientation(fixture("orientation-6.jpg").copyOf(20)))
        assertNull(JpegExif.readOrientation("FUJIFILMCCD-RAW".toByteArray()))
    }

    @Test
    fun `parses embedded JPEG location from RAF header`() {
        val header = rafHeader(jpegOffset = 0x94, jpegLength = 0x12345)
        assertEquals(0x94L to 0x12345L, RafHeader.embeddedJpeg(header))
        assertNull(RafHeader.embeddedJpeg(ByteArray(RafHeader.SIZE)))
    }

    @Test
    fun `reader reads JPEG head and RAF embedded JPEG through partial reads`() = runTest {
        val jpeg = fixture("orientation-6.jpg")
        val raf = rafHeader(jpegOffset = 0x100, jpegLength = jpeg.size.toLong()).copyOf(0x100) + fixture("orientation-8.jpg")
        val client = BytesClient(mapOf(1 to jpeg, 2 to raf, 3 to fixture("no-exif.jpg")))
        val reader = OrientationReader(client)

        assertEquals(6, reader.read(info(1, "DSCF0001.JPG"), MediaKind.JPEG))
        assertEquals(8, reader.read(info(2, "DSCF0002.RAF"), MediaKind.RAW))
        assertNull(reader.read(info(3, "DSCF0003.JPG"), MediaKind.JPEG))
        assertNull(reader.read(info(4, "DSCF0004.MOV"), MediaKind.VIDEO))
        assertNull(reader.read(info(99, "MISSING.JPG"), MediaKind.JPEG))
    }

    @Test
    fun `letterbox of 3 to 2 photo in 4 to 3 thumbnail`() {
        assertEquals(CropRect(0, 9, 160, 102), letterboxContent(160, 120, 7728, 5152))
        assertEquals(CropRect(42, 0, 76, 120), letterboxContent(160, 120, 5152, 7728))
        assertNull(letterboxContent(160, 120, 4000, 3000))
        assertNull(letterboxContent(160, 120, 0, 0))
    }

    private fun rafHeader(jpegOffset: Long, jpegLength: Long): ByteArray {
        val header = ByteArray(RafHeader.SIZE)
        "FUJIFILMCCD-RAW 0201".toByteArray().copyInto(header)
        fun putU32(at: Int, value: Long) {
            for (i in 0 until 4) header[at + i] = (value shr (24 - 8 * i)).toByte()
        }
        putU32(0x54, jpegOffset)
        putU32(0x58, jpegLength)
        return header
    }

    private fun info(handle: Int, name: String) = PtpObjectInfo(
        handle = handle, storageId = 1, format = PtpObjectFormat.UNDEFINED, compressedSize = 0, parent = 0, name = name,
    )

    /** partialObject だけを実装したテスト用クライアント */
    private class BytesClient(private val files: Map<Int, ByteArray>) : PtpClient {
        override suspend fun partialObject(handle: Int, offset: Long, size: Int): ByteArray {
            val bytes = files[handle] ?: throw PtpException("not found")
            if (offset >= bytes.size) return ByteArray(0)
            return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + size))
        }

        override suspend fun deviceInfo(): PtpDeviceInfo = throw UnsupportedOperationException()
        override suspend fun storageIds(): List<Int> = throw UnsupportedOperationException()
        override suspend fun storageInfo(storageId: Int): PtpStorageInfo = throw UnsupportedOperationException()
        override suspend fun objectHandles(storageId: Int, format: Int, parent: Int): List<Int> =
            throw UnsupportedOperationException()
        override suspend fun objectInfo(handle: Int): PtpObjectInfo = throw UnsupportedOperationException()
        override suspend fun thumbnail(handle: Int): ByteArray = throw UnsupportedOperationException()
        override suspend fun downloadTo(handle: Int, destination: File) = throw UnsupportedOperationException()
        override fun close() {}
    }
}
