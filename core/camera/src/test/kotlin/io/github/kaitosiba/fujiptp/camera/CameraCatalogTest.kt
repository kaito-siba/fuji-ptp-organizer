package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpClient
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import io.github.kaitosiba.fujiptp.ptp.dump.DeviceDump
import io.github.kaitosiba.fujiptp.ptp.dump.DumpJson
import io.github.kaitosiba.fujiptp.ptp.fake.FakePtpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

class CameraCatalogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dump: DeviceDump = DumpJson.decode(File("../../fixtures/dumps/x100vi-fw132.json").readText())
    private val objectCount = dump.storages.single().objects.size

    /** ObjectInfo の呼び出し回数を数える */
    private class CountingClient(private val delegate: PtpClient) : PtpClient by delegate {
        var objectInfoCalls = 0
        override suspend fun objectInfo(handle: Int): PtpObjectInfo {
            objectInfoCalls++
            return delegate.objectInfo(handle)
        }
    }

    private fun catalog(client: PtpClient, store: ObjectInfoStore) = CameraCatalog(
        client = client,
        profile = FujifilmX100VIProfile,
        cameraSerial = "SERIAL",
        store = store,
        hostZone = ZoneId.of("Asia/Tokyo"),
    )

    @Test
    fun `loads every object and groups shots newest first`() = runTest {
        val catalog = catalog(FakePtpClient(dump), InMemoryObjectInfoStore())
        catalog.load()
        val state = catalog.state.value

        assertEquals(CatalogState.Phase.COMPLETE, state.phase)
        assertEquals(objectCount, state.totalObjects)
        assertEquals(objectCount, state.loadedObjects)
        assertEquals(0, state.fromCache)
        assertEquals(157, state.shots.size)
        val times = state.shots.mapNotNull { it.capturedAt }
        assertEquals(times.sortedDescending(), times)
    }

    @Test
    fun `second load reuses the cache after spot checks`() = runTest {
        val store = FileObjectInfoStore(tmp.newFolder())
        catalog(FakePtpClient(dump), store).load()

        val client = CountingClient(FakePtpClient(dump))
        val second = catalog(client, store)
        second.load()

        assertEquals(objectCount, second.state.value.fromCache)
        assertEquals(objectCount, second.state.value.loadedObjects)
        assertEquals(8, client.objectInfoCalls)
    }

    @Test
    fun `cache is discarded when the card contents changed`() = runTest {
        val store = InMemoryObjectInfoStore()
        catalog(FakePtpClient(dump), store).load()
        val key = "SERIAL-0x10000001"
        // ハンドルとファイルの対応がずれた状態を作る
        store.save(key, store.load(key)!!.map { it.copy(name = "X" + it.name) })

        val client = CountingClient(FakePtpClient(dump))
        val catalog = catalog(client, store)
        catalog.load()

        assertEquals(0, catalog.state.value.fromCache)
        assertEquals(objectCount, catalog.state.value.loadedObjects)
        assertTrue(client.objectInfoCalls > objectCount)
    }

    @Test
    fun `evenly spaced samples include both ends`() {
        assertEquals(listOf(0, 3, 6, 9), CameraCatalog.evenlySpaced((0..9).toList(), 4))
        assertEquals(listOf(1, 2), CameraCatalog.evenlySpaced(listOf(1, 2), 8))
    }
}
