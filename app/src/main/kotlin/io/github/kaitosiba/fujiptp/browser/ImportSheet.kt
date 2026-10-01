package io.github.kaitosiba.fujiptp.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.kaitosiba.fujiptp.camera.ImportFormat
import io.github.kaitosiba.fujiptp.camera.ImportPlan
import io.github.kaitosiba.fujiptp.importer.MediaStoreWriter
import java.util.Locale

/**
 * 取り込む形式を選んで取り込みを始めるシート。
 *
 * [onStart] は通知権限の確認を挟むので、シートを閉じても結果を受け取れるよう呼び出し側で用意する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    viewModel: BrowserViewModel,
    keys: Set<String>,
    onStart: (ImportPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by remember { mutableStateOf(ImportFormat.ALL) }
    var skipImported by remember { mutableStateOf(true) }
    val plan = remember(keys, format, skipImported) { viewModel.buildImportPlan(format, skipImported, keys) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${keys.size} 枚を取り込む", style = MaterialTheme.typography.titleMedium)

            Text("形式", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            listOf(
                ImportFormat.ALL to "すべて（動画を含む）",
                ImportFormat.DEVELOPED_ONLY to "JPEG / HEIF のみ",
                ImportFormat.RAW_ONLY to "RAW のみ",
            ).forEach { (value, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = format == value, onClick = { format = value }, role = Role.RadioButton),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = format == value, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = skipImported, onValueChange = { skipImported = it }, role = Role.Checkbox),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = skipImported, onCheckedChange = null)
                Text("取込済みのファイルはスキップ", modifier = Modifier.padding(start = 8.dp))
            }

            Text(
                "${plan.items.size} ファイル・${formatBytes(plan.totalBytes)}" +
                    if (plan.skippedImported > 0) "（取込済み ${plan.skippedImported} 件をスキップ）" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "保存先: Pictures/${MediaStoreWriter.APP_DIR}/<撮影日>/（動画は Movies/）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    onStart(plan)
                    onDismiss()
                },
                enabled = plan.items.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 24.dp),
            ) {
                Text("取り込む")
            }
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", bytes / 1024)
}
