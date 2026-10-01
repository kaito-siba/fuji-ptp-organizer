package io.github.kaitosiba.fujiptp.ptp

import io.github.kaitosiba.fujiptp.ptp.diagnostics.DiagnosticsRunner
import io.github.kaitosiba.fujiptp.ptp.dump.DumpJson
import io.github.kaitosiba.fujiptp.ptp.dump.ProbeStatus
import io.github.kaitosiba.fujiptp.ptp.fake.FakePtpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** アプリ同梱のデモ用ダンプがスキーマと互換で、診断が通ることを確認する。 */
class DemoDumpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val demoFile = File("../../app/src/main/assets/demo/x100vi-demo.json")

    @Test
    fun `demo dump decodes and diagnostics succeed on it`() = runTest {
        val dump = DumpJson.decode(demoFile.readText())
        assertEquals("X100VI", dump.deviceInfo.model)

        val result = DiagnosticsRunner(FakePtpClient(dump), tmp.newFolder())
            .run(source = "demo", createdAt = dump.createdAt)
        // 動画の GetThumb 失敗は実機（X100VI FW1.32）と同じ挙動
        val failed = result.probes.filter { it.status == ProbeStatus.FAILED }.map { it.id }
        assertEquals(listOf("thumb-mov"), failed)
        assertEquals(dump.storages.single().objects.size, result.storages.single().objects.size)
    }
}
