package io.github.kaitosiba.fujiptp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.kaitosiba.fujiptp.camera.FujifilmX100VIProfile
import io.github.kaitosiba.fujiptp.connection.CameraSession
import io.github.kaitosiba.fujiptp.connection.ConnectionState
import io.github.kaitosiba.fujiptp.connection.SessionSource
import io.github.kaitosiba.fujiptp.ptp.toHex16

/** 接続状態の表示と、接続・切断の操作。 */
@Composable
fun ConnectionCard(
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
private fun KeyValue(key: String, value: String) {
    Row {
        Text(key, modifier = Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
