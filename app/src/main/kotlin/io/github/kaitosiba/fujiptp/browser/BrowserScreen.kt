package io.github.kaitosiba.fujiptp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kaitosiba.fujiptp.camera.CatalogState
import io.github.kaitosiba.fujiptp.connection.ConnectionState
import io.github.kaitosiba.fujiptp.ui.ConnectionCard
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(viewModel: BrowserViewModel, onOpenDiagnostics: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val connected = ui.connection as? ConnectionState.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(connected?.session?.deviceInfo?.model ?: "Fuji PTP") },
                actions = {
                    if (connected != null) {
                        TextButton(onClick = viewModel::reload) { Text("再読込") }
                        TextButton(onClick = viewModel::disconnect) { Text("切断") }
                    }
                    TextButton(onClick = onOpenDiagnostics) { Text("診断") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (connected == null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    ConnectionCard(
                        state = ui.connection,
                        onConnectUsb = viewModel::connectUsb,
                        onConnectDemo = viewModel::connectDemo,
                        onDisconnect = viewModel::disconnect,
                    )
                }
            } else {
                CatalogProgress(ui.catalog)
                ShotGrid(ui.sections)
            }
        }
    }
}

@Composable
private fun CatalogProgress(state: CatalogState) {
    when (state.phase) {
        CatalogState.Phase.IDLE, CatalogState.Phase.LISTING -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            StatusText("一覧を取得中…")
        }
        CatalogState.Phase.LOADING -> {
            val fraction = if (state.totalObjects == 0) 0f else state.loadedObjects.toFloat() / state.totalObjects
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            StatusText(
                "読み込み中 ${state.loadedObjects} / ${state.totalObjects}" +
                    if (state.fromCache > 0) "（キャッシュ ${state.fromCache}）" else "",
            )
        }
        CatalogState.Phase.COMPLETE -> {
            if (state.failedObjects > 0) StatusText("${state.failedObjects} 件の情報を取得できなかった")
        }
        CatalogState.Phase.FAILED -> StatusText("一覧の取得に失敗: ${state.error}", MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StatusText(text: String, color: Color = Color.Unspecified) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 (E)", Locale.JAPAN)

@Composable
private fun ShotGrid(sections: List<DateSection>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (section in sections) {
            item(key = "header-${section.date}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    (section.date?.format(dateFormatter) ?: "日付不明") + "  ${section.items.size} 枚",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            items(section.items, key = { it.key }) { item -> ShotTile(item) }
        }
    }
}

@Composable
private fun ShotTile(item: ShotItem) {
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (item.thumbnail != null) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = item.shot.primary.info.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                item.shot.primary.info.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Text(
            item.badge,
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
