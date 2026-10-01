package io.github.kaitosiba.fujiptp.ptp

import io.github.kaitosiba.fujiptp.ptp.fake.FakePtpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FakePtpClientTest {
    private val client = FakePtpClient(TestDumps.x100vi())

    @Test
    fun `parent ALL returns every object`() = runTest {
        assertEquals(listOf(1, 2, 10, 11, 12), client.objectHandles(TestDumps.STORAGE))
    }

    @Test
    fun `parent ROOT returns objects whose parent is zero`() = runTest {
        assertEquals(listOf(1), client.objectHandles(TestDumps.STORAGE, parent = PtpClient.PARENT_ROOT))
    }

    @Test
    fun `specific parent returns its children`() = runTest {
        assertEquals(listOf(10, 11, 12), client.objectHandles(TestDumps.STORAGE, parent = 2))
    }

    @Test
    fun `format filter`() = runTest {
        assertEquals(listOf(10), client.objectHandles(TestDumps.STORAGE, format = PtpObjectFormat.EXIF_JPEG))
    }

    @Test
    fun `thumbnail falls back to sample of same format`() = runTest {
        assertArrayEquals(TestDumps.jpegBytes, client.thumbnail(10))
    }

    @Test(expected = PtpException::class)
    fun `thumbnail without sample fails`() = runTest {
        client.thumbnail(11)
    }

    @Test(expected = PtpException::class)
    fun `closed client fails`() = runTest {
        client.close()
        client.deviceInfo()
    }
}
