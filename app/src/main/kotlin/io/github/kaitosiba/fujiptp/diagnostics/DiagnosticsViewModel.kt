package io.github.kaitosiba.fujiptp.diagnostics

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kaitosiba.fujiptp.BuildConfig
import io.github.kaitosiba.fujiptp.FujiPtpApp
import io.github.kaitosiba.fujiptp.connection.ConnectionState
import io.github.kaitosiba.fujiptp.ptp.diagnostics.DiagnosticsOptions
import io.github.kaitosiba.fujiptp.ptp.diagnostics.DiagnosticsRunner
import io.github.kaitosiba.fujiptp.ptp.dump.DeviceDump
import io.github.kaitosiba.fujiptp.ptp.dump.DumpJson
import io.github.kaitosiba.fujiptp.ptp.dump.HostInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

data class DiagnosticsUiState(
    val running: Boolean = false,
    val includeThumbnails: Boolean = true,
    val log: List<String> = emptyList(),
    val dump: DeviceDump? = null,
    val dumpFile: File? = null,
    val message: String? = null,
)

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val connectionManager = (app as FujiPtpApp).container.connectionManager
    val connection: StateFlow<ConnectionState> = connectionManager.state

    private val _ui = MutableStateFlow(DiagnosticsUiState())
    val ui: StateFlow<DiagnosticsUiState> = _ui.asStateFlow()

    private var job: Job? = null
    private val diagnosticsDir: File get() = File(getApplication<Application>().cacheDir, "diagnostics")

    fun connectUsb(device: UsbDevice? = null) = connectionManager.connectUsb(device)

    fun connectDemo() = connectionManager.connectDemo()

    fun disconnect() {
        job?.cancel()
        connectionManager.disconnect()
    }

    fun setIncludeThumbnails(include: Boolean) = _ui.update { it.copy(includeThumbnails = include) }

    fun runDiagnostics() {
        val session = (connection.value as? ConnectionState.Connected)?.session ?: return
        if (_ui.value.running) return
        _ui.update { it.copy(running = true, log = emptyList(), dump = null, dumpFile = null, message = null) }

        job = viewModelScope.launch {
            try {
                val runner = DiagnosticsRunner(
                    client = session.client,
                    workDir = File(diagnosticsDir, "work"),
                    exifReader = AndroidExifReader,
                    options = DiagnosticsOptions(includeThumbnails = _ui.value.includeThumbnails),
                    log = ::appendLog,
                )
                val dump = withContext(Dispatchers.IO) {
                    runner.run(
                        source = session.source.name.lowercase(),
                        createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        host = hostInfo(),
                        usb = session.usb,
                    )
                }.copy(matchedProfileId = session.profile.profile.id)
                val file = withContext(Dispatchers.IO) { writeDump(dump) }
                appendLog("完了: ${file.name}")
                _ui.update { it.copy(dump = dump, dumpFile = file) }
            } catch (e: CancellationException) {
                appendLog("中断した")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "diagnostics failed", e)
                appendLog("失敗: $e")
                _ui.update { it.copy(message = "診断に失敗: ${e.message}") }
            } finally {
                _ui.update { it.copy(running = false) }
            }
        }
    }

    /** SAF で選ばれた保存先にダンプを書き出す。 */
    fun saveDumpTo(uri: Uri) {
        val file = _ui.value.dumpFile ?: return
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: error("保存先を開けない")
                }
            }
            _ui.update {
                it.copy(message = result.fold({ "保存した" }, { e -> "保存に失敗: ${e.message}" }))
            }
        }
    }

    fun shareIntent(): Intent? {
        val file = _ui.value.dumpFile ?: return null
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "診断結果を共有")
    }

    fun suggestedFileName(): String = _ui.value.dumpFile?.name ?: "fujiptp-dump.json"

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        _ui.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    private fun writeDump(dump: DeviceDump): File {
        diagnosticsDir.mkdirs()
        val model = (dump.deviceInfo.model ?: "unknown").replace(Regex("[^A-Za-z0-9_-]"), "_")
        val stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return File(diagnosticsDir, "fujiptp-$model-${dump.source}-$stamp.json").apply {
            writeText(DumpJson.encode(dump))
        }
    }

    private fun hostInfo() = HostInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        appVersion = BuildConfig.VERSION_NAME,
        timeZone = TimeZone.getDefault().id,
    )

    private companion object {
        const val TAG = "FujiPtp"
        const val MAX_LOG_LINES = 300
    }
}
