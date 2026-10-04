package io.github.kaitosiba.fujiptp.ptp

import io.github.kaitosiba.fujiptp.ptp.transfer.ObjectTransfer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ObjectTransferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val content = ByteArray(10_000) { (it % 251).toByte() }

    private class ContentClient(private val content: ByteArray) : PtpClient {
        var partialCalls = 0
        override suspend fun partialObject(handle: Int, offset: Long, size: Int): ByteArray {
            partialCalls++
            return content.copyOfRange(offset.toInt(), minOf(content.size, offset.toInt() + size))
        }
        override suspend fun downloadTo(handle: Int, destination: File) = destination.writeBytes(content)
        override suspend fun deviceInfo(): PtpDeviceInfo = throw UnsupportedOperationException()
        override suspend fun storageIds(): List<Int> = throw UnsupportedOperationException()
        override suspend fun storageInfo(storageId: Int): PtpStorageInfo = throw UnsupportedOperationException()
        override suspend fun objectHandles(storageId: Int, format: Int, parent: Int): List<Int> =
            throw UnsupportedOperationException()
        override suspend fun objectInfo(handle: Int): PtpObjectInfo = throw UnsupportedOperationException()
        override suspend fun thumbnail(handle: Int): ByteArray = throw UnsupportedOperationException()
        override fun close() {}
    }

    @Test
    fun `chunked copy reports progress per chunk`() = runTest {
        val client = ContentClient(content)
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val copied = ObjectTransfer.copy(
            client, 1, content.size.toLong(), supportsPartial = true, out = out, workDir = tmp.root,
            chunkSize = 4096, onProgress = { progress += it },
        )
        assertEquals(10_000L, copied)
        assertArrayEquals(content, out.toByteArray())
        assertEquals(listOf(4096L, 8192L, 10_000L), progress)
        assertEquals(3, client.partialCalls)
    }

    @Test
    fun `falls back to whole object download`() = runTest {
        val out = ByteArrayOutputStream()
        val work = tmp.newFolder()
        ObjectTransfer.copy(ContentClient(content), 1, content.size.toLong(), false, out, work)
        assertArrayEquals(content, out.toByteArray())
        assertEquals(0, work.listFiles()!!.size)
    }

    @Test
    fun `copies a range from the middle`() = runTest {
        val out = ByteArrayOutputStream()
        ObjectTransfer.copyRange(ContentClient(content), 1, start = 1000, length = 5000, out = out, chunkSize = 2048)
        assertArrayEquals(content.copyOfRange(1000, 6000), out.toByteArray())
    }

    @Test(expected = PtpException::class)
    fun `short read fails instead of looping`() = runTest {
        ObjectTransfer.copy(ContentClient(content), 1, 20_000, true, ByteArrayOutputStream(), tmp.root, 4096)
    }
}
