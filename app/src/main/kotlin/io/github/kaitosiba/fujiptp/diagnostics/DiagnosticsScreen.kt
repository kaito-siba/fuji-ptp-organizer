package io.github.kaitosiba.fujiptp.diagnostics

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kaitosiba.fujiptp.camera.FujifilmX100VIProfile
import io.github.kaitosiba.fujiptp.connection.CameraSession
import io.github.kaitosiba.fujiptp.connection.ConnectionState
import io.github.kaitosiba.fujiptp.connection.SessionSource
import io.github.kaitosiba.fujiptp.ptp.dump.ProbeResult
import io.github.kaitosiba.fujiptp.ptp.dump.ProbeStatus
import io.github.kaitosiba.fujiptp.ptp.dump.ThumbnailSample
import io.github.kaitosiba.fujiptp.ptp.toHex16
import java.util.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.saveDumpTo(uri) }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Fuji PTP 診断") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ConnectionCard(
                    state = connection,
                    onConnectUsb = { viewModel.connectUsb() },
                    onConnectDemo = viewModel::connectDemo,
                    onDisconnect = viewModel::disconnect,
                )
            }
            item {
                DiagnosticsControls(
                    enabled = connection is ConnectionState.Connected && !ui.running,
                    running = ui.running,
                    includeThumbnails = ui.includeThumbnails,
                    onIncludeThumbnailsChange = viewModel::setIncludeThumbnails,
                    onRun = viewModel::runDiagnostics,
                )
            }
            if (ui.log.isNotEmpty()) {
                item { LogCard(ui.log) }
            }
            ui.dump?.let { dump ->
                item {
                    ExportCard(
                        fileName = viewModel.suggestedFileName(),
                        onShare = { viewModel.shareIntent()?.let { context.startActivity(it) } },
                        onSave = { saveLauncher.launch(viewModel.suggestedFileName()) },
                    )
                }
                if (dump.thumbnails.isNotEmpty()) {
                    item { ThumbnailRow(dump.thumbnails) }
                }
                item { SectionTitle("診断結果 (${dump.probes.size})") }
                items(dump.probes) { ProbeRow(it) }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    onConnectUsb: () -> Unit,
    onConnectDemo: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is ConnectionState.Connected -> SessionSummary(state.session, onDisconnect)
                is ConnectionState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(state.message)
                }
                ConnectionState.Disconnected, is ConnectionState.Failed -> {
                    if (state is ConnectionState.Failed) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                    Text("未接続", style = MaterialTheme.typography.titleMedium)
                    val guide = FujifilmX100VIProfile.connectionGuide
                    guide.steps.forEachIndexed { i, step ->
                        Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!guide.verified) {
                        Text("※ 手順は実機未確認", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConnectUsb) { Text("USB カメラに接続") }
                        OutlinedButton(onClick = onConnectDemo) { Text("デモモード") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSummary(session: CameraSession, onDisconnect: () -> Unit) {
    val info = session.deviceInfo
    Text(
        "${info.manufacturer ?: "?"} ${info.model ?: "?"}" + if (session.source == SessionSource.DEMO) "（デモ）" else "",
        style = MaterialTheme.typography.titleMedium,
    )
    KeyValue("ファームウェア", info.version ?: "-")
    KeyValue("シリアル", info.serialNumber ?: "-")
    session.usb?.let { KeyValue("USB VID:PID", "${it.vendorId.toHex16()}:${it.productId.toHex16()}") }
    val profile = session.profile.profile
    KeyValue(
        "プロファイル",
        "${profile.displayName} (score ${session.profile.score})" + if (profile.verified) "" else " 未検証",
    )
    KeyValue("対応オペレーション", "${info.operationsSupported.size} 件")
    OutlinedButton(onClick = onDisconnect) { Text("切断") }
}

@Composable
private fun DiagnosticsControls(
    enabled: Boolean,
    running: Boolean,
    includeThumbnails: Boolean,
    onIncludeThumbnailsChange: (Boolean) -> Unit,
    onRun: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("サムネイル画像をダンプに含める")
                    Text(
                        "共有する JSON に写真の縮小画像が入る",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = includeThumbnails, onCheckedChange = onIncludeThumbnailsChange, enabled = !running)
            }
            Button(onClick = onRun, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "診断中…" else "診断を実行")
            }
        }
    }
}

@Composable
private fun LogCard(lines: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            lines.takeLast(40).forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun ExportCard(fileName: String, onShare: () -> Unit, onSave: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fileName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare) { Text("共有") }
                OutlinedButton(onClick = onSave) { Text("ファイルに保存") }
            }
        }
    }
}

@Composable
private fun ThumbnailRow(samples: List<ThumbnailSample>) {
    Column {
        SectionTitle("GetThumb サンプル")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            samples.forEach { sample ->
                val bitmap: ImageBitmap? = remember(sample.handle, sample.base64) { decode(sample.base64) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = sample.fileName, modifier = Modifier.height(90.dp))
                    } else {
                        Text("（画像なし）", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(sample.fileName, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ProbeRow(probe: ProbeResult) {
    var expanded by rememberSaveable(probe.id) { mutableStateOf(probe.status == ProbeStatus.FAILED) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (mark, color) = when (probe.status) {
                ProbeStatus.OK -> "✓" to Color(0xFF2E7D32)
                ProbeStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
                ProbeStatus.SKIPPED -> "–" to MaterialTheme.colorScheme.outline
                ProbeStatus.INFO -> "i" to MaterialTheme.colorScheme.primary
            }
            Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
            Text(probe.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            probe.durationMs?.let { Text("${it}ms", style = MaterialTheme.typography.labelSmall) }
        }
        Text(
            if (expanded) probe.detail else probe.detail.lineSequence().first(),
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(key, modifier = Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

private fun decode(base64: String?): ImageBitmap? {
    if (base64 == null) return null
    val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
