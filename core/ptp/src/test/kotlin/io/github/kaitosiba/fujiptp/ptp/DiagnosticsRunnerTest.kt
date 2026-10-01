package io.github.kaitosiba.fujiptp.ptp

import io.github.kaitosiba.fujiptp.ptp.diagnostics.DiagnosticsRunner
import io.github.kaitosiba.fujiptp.ptp.dump.DumpJson
import io.github.kaitosiba.fujiptp.ptp.dump.ProbeStatus
import io.github.kaitosiba.fujiptp.ptp.fake.FakePtpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun run(supportsPartial: Boolean) = DiagnosticsRunner(
        client = FakePtpClient(TestDumps.x100vi(supportsPartial)),
        workDir = tmp.newFolder(),
        exifReader = { mapOf("DateTimeOriginal" to "2026:10:01 10:00:00", "OffsetTimeOriginal" to "+09:00") },
    ).run(source = "test", createdAt = "2026-10-01T00:00:00+09:00")

    @Test
    fun `collects storages, objects and probes`() = runTest {
        val dump = run(supportsPartial = true)
        val probes = dump.probes.associateBy { it.id }

        assertEquals("X100VI", dump.deviceInfo.model)
        assertEquals(5, dump.storages.single().objects.size)
        assertEquals(5, dump.storages.single().handleCountAll)
        assertEquals(1, dump.storages.single().handleCountRoot)
        assertEquals(ProbeStatus.OK, probes.getValue("partial-support").status)
        assertEquals(ProbeStatus.OK, probes.getValue("thumb-jpg").status)
        assertEquals(ProbeStatus.FAILED, probes.getValue("thumb-raf").status)
        assertEquals(ProbeStatus.OK, probes.getValue("partial-jpeg").status)
        assertEquals(ProbeStatus.OK, probes.getValue("exif").status)
        assertEquals(10, dump.exifSamples.single().handle)
        assertEquals(1, dump.thumbnails.size)
    }

    @Test
    fun `falls back to full download for exif when partial is unsupported`() = runTest {
        val dump = run(supportsPartial = false)
        val probes = dump.probes.associateBy { it.id }

        assertEquals(ProbeStatus.SKIPPED, probes.getValue("partial-read").status)
        assertEquals(ProbeStatus.OK, probes.getValue("download-jpg").status)
        assertEquals(ProbeStatus.OK, probes.getValue("exif").status)
    }

    @Test
    fun `dump survives json round trip and can drive a fake client`() = runTest {
        val dump = run(supportsPartial = true)
        val decoded = DumpJson.decode(DumpJson.encode(dump))
        assertEquals(dump, decoded)

        val replay = FakePtpClient(decoded)
        assertEquals(dump.storages.single().objects.size, replay.objectHandles(TestDumps.STORAGE).size)
    }

    @Test
    fun `detects file magic`() {
        assertEquals("JPEG", DiagnosticsRunner.detectMagic(TestDumps.jpegBytes))
        assertEquals("RAF", DiagnosticsRunner.detectMagic("FUJIFILMCCD-RAW 0201".toByteArray()))
        assertTrue(DiagnosticsRunner.detectMagic(byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray()).startsWith("ISO-BMFF(heic"))
    }
}
