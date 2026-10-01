package io.github.kaitosiba.fujiptp.browser

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kaitosiba.fujiptp.camera.CatalogState
import io.github.kaitosiba.fujiptp.camera.ImportPlan
import io.github.kaitosiba.fujiptp.camera.ImportStatus
import io.github.kaitosiba.fujiptp.connection.ConnectionState
import io.github.kaitosiba.fujiptp.importer.ImportProgress
import io.github.kaitosiba.fujiptp.ui.ConnectionCard
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenPreview: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenGeotag: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val connected = ui.connection as? ConnectionState.Connected
    val selecting = selection.isNotEmpty()
    var showImportSheet by remember { mutableStateOf(false) }
    val startImport = rememberStartImport(viewModel)

    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selection.size} 枚選択") },
                    navigationIcon = { TextButton(onClick = viewModel::clearSelection) { Text("解除") } },
                    actions = {
                        TextButton(onClick = viewModel::selectAllNotImported) { Text("未取込を全選択") }
                        TextButton(onClick = { showImportSheet = true }) { Text("取り込む") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(connected?.session?.deviceInfo?.model ?: "Fuji PTP") },
                    actions = {
                        if (connected != null) {
                            TextButton(onClick = viewModel::reload) { Text("再読込") }
                            TextButton(onClick = viewModel::disconnect) { Text("切断") }
                        }
                        TextButton(onClick = onOpenGeotag) { Text("ジオタグ") }
                        TextButton(onClick = onOpenDiagnostics) { Text("診断") }
                    },
                )
            }
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
                ImportProgressBar(ui.importProgress, viewModel::cancelImport, viewModel::dismissImportMessage)
                CatalogProgress(ui.catalog)
                ShotGrid(
                    sections = ui.sections,
                    selection = selection,
                    onTap = { item ->
                        if (selecting) {
                            viewModel.toggleSelection(item.key)
                        } else {
                            viewModel.openPreview(item.key)
                            onOpenPreview(item.key)
                        }
                    },
                    onLongPress = { item -> viewModel.toggleSelection(item.key) },
                    onHeaderTap = { section -> if (selecting) viewModel.toggleSection(section) },
                )
            }
        }
    }

    if (showImportSheet) {
        ImportSheet(
            viewModel = viewModel,
            keys = selection,
            onStart = startImport,
            onDismiss = { showImportSheet = false },
        )
    }
}

@Composable
private fun ImportProgressBar(progress: ImportProgress, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (!progress.running && progress.message == null) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (progress.running) {
                        val index = progress.completedFiles + progress.failedFiles + 1
                        "取り込み中 $index / ${progress.totalFiles}  ${progress.currentFile ?: ""}"
                    } else {
                        progress.message ?: ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (progress.running) {
                    TextButton(onClick = onCancel) { Text("中止") }
                } else {
                    TextButton(onClick = onDismiss) { Text("閉じる") }
                }
            }
            if (progress.running) {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
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
private fun ShotGrid(
    sections: List<DateSection>,
    selection: Set<String>,
    onTap: (ShotItem) -> Unit,
    onLongPress: (ShotItem) -> Unit,
    onHeaderTap: (DateSection) -> Unit,
) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHeaderTap(section) }
                        .padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            items(section.items, key = { it.key }) { item ->
                ShotTile(
                    item = item,
                    selected = item.key in selection,
                    onTap = { onTap(item) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShotTile(item: ShotItem, selected: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
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
        Label(item.badge, Modifier.align(Alignment.BottomStart))
        when (item.importStatus) {
            ImportStatus.ALL -> Label("取込済", Modifier.align(Alignment.TopStart), Color(0xFF2E7D32))
            ImportStatus.PARTIAL -> Label("一部取込", Modifier.align(Alignment.TopStart), Color(0xFF8D6E00))
            ImportStatus.NONE -> Unit
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    .border(3.dp, MaterialTheme.colorScheme.primary),
            )
            Label("✓", Modifier.align(Alignment.TopEnd), MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier, background: Color = Color.Black.copy(alpha = 0.55f)) {
    Text(
        text,
        color = Color.White,
        fontSize = 10.sp,
        modifier = modifier
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** 通知の権限を確認してから取り込みを始める（拒否されても取り込みは行う）。 */
@Composable
fun rememberStartImport(viewModel: BrowserViewModel): (ImportPlan) -> Unit {
    var pending by remember { mutableStateOf<ImportPlan?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let(viewModel::startImport)
        pending = null
    }
    return { plan ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pending = plan
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startImport(plan)
        }
    }
}
