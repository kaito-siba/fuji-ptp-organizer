package io.github.kaitosiba.fujiptp.preview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import io.github.kaitosiba.fujiptp.browser.BrowserViewModel
import io.github.kaitosiba.fujiptp.browser.ImportSheet
import io.github.kaitosiba.fujiptp.browser.ShotItem
import io.github.kaitosiba.fujiptp.browser.formatBytes
import io.github.kaitosiba.fujiptp.browser.rememberStartImport
import io.github.kaitosiba.fujiptp.camera.ImportStatus
import java.time.format.DateTimeFormatter

/**
 * 全画面プレビュー。
 *
 * - 左右スワイプで前後の写真、ピンチ・ダブルタップで拡大縮小（拡大中はドラッグで移動）
 * - タップで上下の情報表示を切り替え（隠すとシステムバーも隠す）
 * - 端末の回転ロックに関係なく、端末の向きに合わせて画面を回転する
 */
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
    var overlayVisible by rememberSaveable { mutableStateOf(true) }
    val startImport = rememberStartImport(viewModel)

    val zoomStates = remember { mutableMapOf<String, ZoomState>() }
    fun zoomOf(key: String) = zoomStates.getOrPut(key) { ZoomState() }
    val currentZoomed = current?.let { zoomOf(it.key).isZoomed } == true

    // ページが変わったら、離れたページの拡大は戻しておく
    LaunchedEffect(pagerState.settledPage) {
        val settledKey = items.getOrNull(pagerState.settledPage)?.key
        zoomStates.forEach { (key, state) -> if (key != settledKey) state.reset() }
    }

    FollowDeviceRotation()
    ImmersiveMode(enabled = !overlayVisible)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            key = { items[it].key },
            userScrollEnabled = !currentZoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            PreviewPage(item, zoomOf(item.key), onTap = { overlayVisible = !overlayVisible })
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val buttonColors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            TopAppBar(
                title = { Text(current?.shot?.primary?.info?.name ?: "") },
                navigationIcon = { TextButton(onClick = onBack, colors = buttonColors) { Text("戻る") } },
                actions = {
                    if (current != null && !ui.importProgress.running) {
                        TextButton(onClick = { importKeys = setOf(current.key) }, colors = buttonColors) {
                            Text("取り込む")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = OVERLAY_ALPHA),
                    titleContentColor = Color.White,
                ),
            )
        }

        AnimatedVisibility(
            visible = overlayVisible && current != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            current?.let { InfoPanel(it) }
        }
    }

    importKeys?.let { keys ->
        ImportSheet(viewModel = viewModel, keys = keys, onStart = startImport, onDismiss = { importKeys = null })
    }
}

@Composable
private fun PreviewPage(item: ShotItem, zoom: ZoomState, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .zoomable(zoom, onTap),
    ) {
        // まずサムネイルを出し、本体（または RAF の埋め込み JPEG）が届いたら上に重ねる
        item.thumbnail?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        item.preview?.let { preview ->
            AsyncImage(
                model = preview,
                contentDescription = item.shot.primary.info.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            // 通常は画面サイズで読み込むので、拡大したら高解像度版を読んで重ねる（メモリを食うので拡大中のページだけ）
            if (zoom.scale > HIGH_RES_THRESHOLD) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(preview)
                        .size(HIGH_RES_SIZE)
                        .precision(Precision.EXACT)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
private fun InfoPanel(item: ShotItem) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = OVERLAY_ALPHA))
            .navigationBarsPadding()
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

/** この画面にいる間は、回転ロック中でも端末の向きに合わせて回転する。離れたら元の設定に戻す。 */
@Composable
private fun FollowDeviceRotation() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose { activity.requestedOrientation = previous }
    }
}

/** [enabled] の間はステータスバーとナビゲーションバーを隠す（端からスワイプで一時的に出せる）。 */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    DisposableEffect(window, enabled) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val OVERLAY_ALPHA = 0.6f
private const val HIGH_RES_THRESHOLD = 1.5f

/** 拡大表示用の長辺。ARGB で 4096×2731 ≒ 45 MB */
private const val HIGH_RES_SIZE = 4096
