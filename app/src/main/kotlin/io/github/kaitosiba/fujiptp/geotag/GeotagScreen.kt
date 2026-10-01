package io.github.kaitosiba.fujiptp.geotag

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeotagScreen(viewModel: GeotagViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::setGpxTree)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ジオタグ（確認）") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
                actions = { TextButton(onClick = { pickFolder.launch(config.gpxTreeUri) }) { Text("GPX フォルダ") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                GeotagUiState.NeedFolder -> NeedFolder(onPick = { pickFolder.launch(null) })
                is GeotagUiState.Loading -> Row(
                    Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(s.message)
                }
                is GeotagUiState.Failed -> Text(
                    "読み込みに失敗: ${s.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                is GeotagUiState.Ready -> ReadyContent(s, config, viewModel)
            }
        }
    }
}

@Composable
private fun NeedFolder(onPick: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GPSLogger が GPX を保存しているフォルダを選んでくれ。", style = MaterialTheme.typography.bodyLarge)
        Text(
            "フォルダ内の .gpx を読み、取り込み済みの写真の撮影時刻と突き合わせて位置を出す。",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onPick) { Text("フォルダを選ぶ") }
    }
}

@Composable
private fun ReadyContent(state: GeotagUiState.Ready, config: GeotagConfig, viewModel: GeotagViewModel) {
    var focus by remember { mutableStateOf<GeoMatch?>(null) }
    Column(Modifier.fillMaxSize()) {
        TrackMap(
            track = state.track,
            matches = state.rows.mapNotNull { it.match },
            focus = focus,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item { SummaryCard(state, config, viewModel) }
            items(state.rows, key = { it.file.stableKey }) { row ->
                RowItem(row, onClick = { row.match?.let { focus = it } })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SummaryCard(state: GeotagUiState.Ready, config: GeotagConfig, viewModel: GeotagViewModel) {
    val s = state.summary
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${s.total} 件中 ${s.interpolated + s.nearest} 件に位置を付けられる", style = MaterialTheme.typography.titleSmall)
            Legend(TrackMapController.INTERPOLATED_COLOR, "補間 ${s.interpolated}")
            Legend(TrackMapController.NEAREST_COLOR, "最寄りの記録点 ${s.nearest}")
            Text(
                "GPX の範囲外 ${s.unmatched}・撮影時刻なし ${s.noTime}  （GPX ${state.gpxFiles} ファイル" +
                    if (state.failedGpxFiles > 0) "、読めなかった ${state.failedGpxFiles}）" else "）",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                "カメラ時計の補正: ${formatDuration(config.clockCorrection)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "-1h" to Duration.ofHours(-1),
                    "-1m" to Duration.ofMinutes(-1),
                    "-10s" to Duration.ofSeconds(-10),
                    "+10s" to Duration.ofSeconds(10),
                    "+1m" to Duration.ofMinutes(1),
                    "+1h" to Duration.ofHours(1),
                ).forEach { (label, delta) ->
                    OutlinedButton(onClick = { viewModel.adjustClock(delta) }) { Text(label) }
                }
                TextButton(onClick = viewModel::resetClock) { Text("リセット") }
            }

            state.suggestion?.let { suggestion ->
                Text(
                    "時計が ${formatDuration(suggestion.shift)} ずれている可能性がある（ずらすと " +
                        "${suggestion.matchedWithoutShift} → ${suggestion.matchedWithShift} 件が GPX に収まる）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { viewModel.applySuggestion(suggestion) }) { Text("この補正を適用") }
            }

            Text(
                "書き込み（JPEG の EXIF / RAF・HEIF の XMP）は次の段階で対応する。今は付与予定の確認のみ。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Legend(color: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(android.graphics.Color.parseColor(color))),
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
private val utcFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

@Composable
private fun RowItem(row: GeotagRow, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = row.match != null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(row.file.fileName, style = MaterialTheme.typography.bodyMedium)
        val photo = row.photo
        Text(
            if (photo == null) {
                "撮影時刻なし"
            } else {
                photo.localTime.format(timeFormatter) + (row.file.exifOffsetTimeOriginal?.let { " $it" } ?: "（TZ 推定）") +
                    "  →  " + utcFormatter.format(photo.instant)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        val match = row.match
        Text(
            when {
                photo == null -> ""
                match == null -> "GPX の範囲外"
                else -> String.format(Locale.US, "%.5f, %.5f", match.latitude, match.longitude) +
                    if (match.method == MatchMethod.NEAREST) "（最寄り点・${match.timeDistance.seconds} 秒差）" else "（補間）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (match == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDuration(duration: Duration): String {
    if (duration.isZero) return "なし"
    val sign = if (duration.isNegative) "-" else "+"
    val abs = duration.abs()
    return buildString {
        append(sign)
        // Duration.toMinutesPart() などは API 31 からなので自前で分解する
        val hours = abs.toHours()
        val minutes = abs.toMinutes() % 60
        val seconds = abs.seconds % 60
        if (hours > 0) append("$hours 時間 ")
        if (minutes > 0) append("$minutes 分 ")
        if (seconds > 0) append("$seconds 秒")
    }.trim()
}
