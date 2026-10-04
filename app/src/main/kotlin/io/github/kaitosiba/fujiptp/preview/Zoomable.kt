package io.github.kaitosiba.fujiptp.preview

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * 1 ページ分の拡大状態。拡大中はページ送りを止め、1 本指のドラッグで移動する。
 *
 * 表示は中心基準の拡大（graphicsLayer）+ 平行移動で、指の位置の下にある点が動かないように平行移動を補正する。
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    internal var size: IntSize = IntSize.Zero

    val isZoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** [centroid]（画面上の点）を固定したまま [zoom] 倍し、[pan] だけ動かす。 */
    internal fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        if (newScale <= MIN_SCALE) {
            reset()
            return
        }
        val center = Offset(size.width / 2f, size.height / 2f)
        val fromCenter = centroid - center
        // 画面上の点 g = center + (p - center) * scale + offset が拡大の前後で同じになるよう offset を決める
        val keptOffset = fromCenter - (fromCenter - offset) * (newScale / scale)
        scale = newScale
        offset = clamp(keptOffset + pan)
    }

    /** ダブルタップ: 拡大中なら元に戻し、そうでなければタップした点を中心に拡大する。 */
    internal fun toggleAt(point: Offset) {
        if (isZoomed) {
            reset()
        } else {
            transform(centroid = point, pan = Offset.Zero, zoom = DOUBLE_TAP_SCALE)
        }
    }

    /** 画像の端が画面の内側に入り込まない範囲に収める */
    private fun clamp(value: Offset): Offset {
        val maxX = (scale - 1f) * size.width / 2f
        val maxY = (scale - 1f) * size.height / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

/**
 * ピンチで拡大縮小、拡大中のドラッグで移動、ダブルタップで拡大切り替え、シングルタップで [onTap]。
 *
 * 拡大していないときの 1 本指ドラッグは消費しないので、外側の HorizontalPager がページ送りに使える。
 */
fun Modifier.zoomable(state: ZoomState, onTap: () -> Unit): Modifier = this
    .onSizeChanged { state.size = it }
    .pointerInput(state) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { state.toggleAt(it) },
        )
    }
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2 || state.isZoomed) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) {
                        state.transform(event.calculateCentroid(useCurrent = false), pan, zoom)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }
