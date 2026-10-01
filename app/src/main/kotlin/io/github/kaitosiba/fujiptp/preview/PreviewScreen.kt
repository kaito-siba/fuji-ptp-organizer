package io.github.kaitosiba.fujiptp.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kaitosiba.fujiptp.browser.BrowserViewModel
import io.github.kaitosiba.fujiptp.browser.ImportSheet
import io.github.kaitosiba.fujiptp.browser.ShotItem
import io.github.kaitosiba.fujiptp.browser.formatBytes
import io.github.kaitosiba.fujiptp.browser.rememberStartImport
import io.github.kaitosiba.fujiptp.camera.ImportStatus
import java.time.format.DateTimeFormatter

/** 全画面プレビュー。左右スワイプで前後の写真に移る。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val items = remember(ui.sections) { ui.sections.flatMap { it.items } }
    if (items.isEmpty()) {
        // 切断などで一覧が空になったら戻る
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val startIndex = remember { items.indexOfFirst { it.key == viewModel.previewStartKey }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    val current = items.getOrNull(pagerState.currentPage)
    var importKeys by remember { mutableStateOf<Set<String>?>(null) }
    val startImport = rememberStartImport(viewModel)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(current?.shot?.primary?.info?.name ?: "") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
                actions = {
                    if (current != null && !ui.importProgress.running) {
                        TextButton(onClick = { importKeys = setOf(current.key) }) { Text("取り込む") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                key = { items[it].key },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                PreviewPage(items[page])
            }
            current?.let { InfoPanel(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }

    importKeys?.let { keys ->
        ImportSheet(viewModel = viewModel, keys = keys, onStart = startImport, onDismiss = { importKeys = null })
    }
}

@Composable
private fun PreviewPage(item: ShotItem) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // まずサムネイルを出し、本体（またはRAF の埋め込み JPEG）が届いたら上に重ねる
        item.thumbnail?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        item.preview?.let {
            AsyncImage(
                model = it,
                contentDescription = item.shot.primary.info.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.preview == null) {
            Text(
                "この形式はプレビュー非対応",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

@Composable
private fun InfoPanel(item: ShotItem, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val members = item.shot.members.joinToString("  ") { "${it.info.name} (${formatBytes(it.info.compressedSize)})" }
        Text(members, color = Color.White, style = MaterialTheme.typography.bodySmall)
        Text(
            buildString {
                append(item.shot.capturedAt?.format(timeFormatter) ?: "日時不明")
                when (item.importStatus) {
                    ImportStatus.ALL -> append("  ・取込済")
                    ImportStatus.PARTIAL -> append("  ・一部取込")
                    ImportStatus.NONE -> Unit
                }
            },
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
