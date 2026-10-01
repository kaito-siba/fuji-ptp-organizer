package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileRegistryTest {
    private val registry = ProfileRegistry.default()
    private val fujiUsb = UsbIdentity(FujifilmProfile.FUJIFILM_VENDOR_ID, 0x0000)

    private fun info(manufacturer: String, model: String) =
        PtpDeviceInfo(manufacturer = manufacturer, model = model, version = null, serialNumber = null)

    @Test
    fun `X100VI resolves to its own profile`() {
        assertEquals("fujifilm.x100vi", registry.resolve(fujiUsb, info("FUJIFILM", "X100VI")).profile.id)
    }

    @Test
    fun `X100V is not mistaken for X100VI`() {
        assertEquals("fujifilm", registry.resolve(fujiUsb, info("FUJIFILM", "X100V")).profile.id)
    }

    @Test
    fun `fujifilm is detected by vendor id before device info is available`() {
        assertEquals("fujifilm", registry.resolve(fujiUsb, null).profile.id)
    }

    @Test
    fun `other vendors fall back to generic`() {
        val match = registry.resolve(UsbIdentity(0x04A9, 0x1234), info("Canon Inc.", "EOS R6"))
        assertEquals("generic.ptp", match.profile.id)
    }
}
