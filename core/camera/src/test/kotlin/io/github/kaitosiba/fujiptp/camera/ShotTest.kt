package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpObjectFormat
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ShotTest {
    private fun obj(handle: Int, name: String, format: Int = PtpObjectFormat.UNDEFINED, parent: Int = 2) =
        PtpObjectInfo(handle = handle, storageId = 1, format = format, compressedSize = 1, parent = parent, name = name)

    private val profile = FujifilmX100VIProfile

    @Test
    fun `RAF and JPEG with same stem form one shot with JPEG primary`() {
        val shots = groupIntoShots(
            listOf(
                obj(1, "100_FUJI", PtpObjectFormat.ASSOCIATION, parent = 0),
                obj(10, "DSCF0001.RAF"),
                obj(11, "DSCF0001.JPG", PtpObjectFormat.EXIF_JPEG),
                obj(12, "DSCF0002.HIF"),
                obj(13, "DSCF0003.MOV"),
            ),
            profile,
        )

        assertEquals(3, shots.size)
        assertEquals(setOf(MediaKind.RAW, MediaKind.JPEG), shots[0].kinds)
        assertEquals("DSCF0001.JPG", shots[0].primary.info.name)
        assertEquals(MediaKind.HEIF, shots[1].primary.kind)
        assertEquals(MediaKind.VIDEO, shots[2].primary.kind)
    }

    @Test
    fun `same stem in different folders are different shots`() {
        val shots = groupIntoShots(listOf(obj(10, "DSCF0001.JPG", parent = 2), obj(20, "DSCF0001.JPG", parent = 3)), profile)
        assertEquals(2, shots.size)
    }
}
