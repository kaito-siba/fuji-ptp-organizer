package io.github.kaitosiba.fujiptp.geotag

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** MapLibre の地図。画面のライフサイクルを MapView に伝える。 */
@Composable
fun TrackMap(
    track: List<TrackPoint>,
    matches: List<GeoMatch>,
    focus: GeoMatch?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var controller by remember { mutableStateOf<TrackMapController?>(null) }
    var fitted by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(TrackMapController.STYLE_URL)) { style ->
                controller = TrackMapController(map, style)
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            // 画面を離れるときは、呼んだ分だけ逆順に止めてから破棄する
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(controller, track, matches) {
        val c = controller ?: return@LaunchedEffect
        c.show(track, matches, fit = !fitted)
        fitted = true
    }
    LaunchedEffect(controller, focus) {
        val c = controller ?: return@LaunchedEffect
        focus?.let(c::focus)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
