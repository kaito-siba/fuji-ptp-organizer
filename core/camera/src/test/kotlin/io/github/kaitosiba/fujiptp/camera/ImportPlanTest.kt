package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpObjectFormat
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportPlanTest {
    private fun obj(handle: Int, name: String, format: Int, size: Long = 100) = PtpObjectInfo(
        handle = handle, storageId = 1, format = format, compressedSize = size, parent = 2, name = name,
        keywords = "20260922T0527%02d".format(handle),
    )

    private val shots = groupIntoShots(
        listOf(
            obj(10, "DSCF0001.RAF", FujifilmProfile.FORMAT_RAF, 80),
            obj(11, "DSCF0001.JPG", PtpObjectFormat.EXIF_JPEG, 12),
            obj(12, "DSCF0002.JPG", PtpObjectFormat.EXIF_JPEG, 10),
            obj(13, "DSCF0003.MOV", PtpObjectFormat.QUICKTIME, 300),
        ),
        FujifilmX100VIProfile,
    )

    @Test
    fun `format filter selects members`() {
        fun names(format: ImportFormat) = planImport(shots, format, "S", emptySet()).items.map { it.info.name }
        assertEquals(listOf("DSCF0001.JPG", "DSCF0001.RAF", "DSCF0002.JPG", "DSCF0003.MOV"), names(ImportFormat.ALL))
        assertEquals(listOf("DSCF0001.JPG", "DSCF0002.JPG"), names(ImportFormat.DEVELOPED_ONLY))
        assertEquals(listOf("DSCF0001.RAF"), names(ImportFormat.RAW_ONLY))
    }

    @Test
    fun `already imported files are skipped and counted`() {
        val imported = setOf(StableObjectId.of("S", shots.first().members.first { it.kind == MediaKind.JPEG }.info).key)
        val plan = planImport(shots, ImportFormat.ALL, "S", imported)
        assertEquals(3, plan.items.size)
        assertEquals(1, plan.skippedImported)
        assertEquals(80L + 10 + 300, plan.totalBytes)
        assertEquals(4, planImport(shots, ImportFormat.ALL, "S", imported, skipImported = false).items.size)
    }

    @Test
    fun `import status of a shot`() {
        val pair = shots.first()
        val jpegKey = StableObjectId.of("S", pair.members.first { it.kind == MediaKind.JPEG }.info).key
        val rafKey = StableObjectId.of("S", pair.members.first { it.kind == MediaKind.RAW }.info).key
        assertEquals(ImportStatus.NONE, pair.importStatus("S", emptySet()))
        assertEquals(ImportStatus.PARTIAL, pair.importStatus("S", setOf(jpegKey)))
        assertEquals(ImportStatus.ALL, pair.importStatus("S", setOf(jpegKey, rafKey)))
    }
}
