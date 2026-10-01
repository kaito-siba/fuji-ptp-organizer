package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.dump.DeviceDump
import io.github.kaitosiba.fujiptp.ptp.dump.DumpJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** X100VI 実機ダンプ（FW1.32）に対してプロファイルが期待どおり振る舞うことを確認する。 */
class X100VIDumpTest {
    private val dump: DeviceDump = DumpJson.decode(File("../../fixtures/dumps/x100vi-fw132.json").readText())
    private val objects = dump.storages.single().objects
    private val profile = ProfileRegistry.default()
        .resolve(dump.usb?.let { UsbIdentity(it.vendorId, it.productId) }, dump.deviceInfo)
        .profile

    @Test
    fun `resolves to the X100VI profile`() {
        assertEquals(FujifilmX100VIProfile, profile)
        assertEquals(FujifilmX100VIProfile.USB_PRODUCT_ID, dump.usb?.productId)
        assertTrue(profile.connectionGuide.verified)
    }

    @Test
    fun `every file is classified`() {
        val kinds = objects.filterNot { it.isFolder }.groupingBy { profile.classify(it) }.eachCount()
        assertEquals(mapOf(MediaKind.RAW to 149, MediaKind.JPEG to 147, MediaKind.VIDEO to 2), kinds)
    }

    @Test
    fun `RAF and JPEG pairs are grouped into shots`() {
        val shots = groupIntoShots(objects, profile)
        assertEquals(157, shots.size)
        assertEquals(141, shots.count { it.kinds == setOf(MediaKind.JPEG, MediaKind.RAW) })
        assertTrue(shots.filter { MediaKind.JPEG in it.kinds }.all { it.primary.kind == MediaKind.JPEG })
    }

    @Test
    fun `video has no PTP thumbnail`() {
        assertFalse(profile.hasPtpThumbnail(MediaKind.VIDEO))
        assertTrue(profile.hasPtpThumbnail(MediaKind.RAW))
    }

    @Test
    fun `capture wall clock comes from keywords regardless of host time zone`() {
        val raf = objects.first { it.name == "DSCF0262.RAF" }
        val expected = LocalDateTime.of(2026, 9, 22, 5, 27, 36)
        assertEquals(expected, profile.captureWallClock(raf, ZoneId.of("America/Vancouver")))
        assertEquals(expected, profile.captureWallClock(raf, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun `generic profile recovers wall clock with the host zone used when dumping`() {
        val raf = objects.first { it.name == "DSCF0262.RAF" }
        val hostZone = ZoneId.of(dump.host!!.timeZone)
        assertEquals(LocalDateTime.of(2026, 9, 22, 5, 27, 36), GenericPtpProfile().captureWallClock(raf, hostZone))
    }
}
