package io.github.kaitosiba.fujiptp.geotag

import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** 地図に GPX のトラックと、写真の付与予定位置を描く。 */
class TrackMapController(private val map: MapLibreMap, private val style: Style) {

    init {
        style.addSource(GeoJsonSource(TRACK_SOURCE))
        style.addSource(GeoJsonSource(INTERPOLATED_SOURCE))
        style.addSource(GeoJsonSource(NEAREST_SOURCE))
        style.addLayer(
            LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
                PropertyFactory.lineColor(TRACK_COLOR),
                PropertyFactory.lineWidth(3f),
            ),
        )
        style.addLayer(pointLayer(INTERPOLATED_LAYER, INTERPOLATED_SOURCE, INTERPOLATED_COLOR))
        style.addLayer(pointLayer(NEAREST_LAYER, NEAREST_SOURCE, NEAREST_COLOR))
    }

    /** @param fit true なら全体が収まるようにカメラを動かす */
    fun show(track: List<TrackPoint>, matches: List<GeoMatch>, fit: Boolean) {
        val line = if (track.size >= 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(track.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else {
            emptyList()
        }
        style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(line))
        style.getSourceAs<GeoJsonSource>(INTERPOLATED_SOURCE)
            ?.setGeoJson(points(matches.filter { it.method == MatchMethod.INTERPOLATED }))
        style.getSourceAs<GeoJsonSource>(NEAREST_SOURCE)
            ?.setGeoJson(points(matches.filter { it.method == MatchMethod.NEAREST }))

        if (fit) {
            val all = matches.map { LatLng(it.latitude, it.longitude) }.ifEmpty { track.map { LatLng(it.latitude, it.longitude) } }
            when {
                all.size >= 2 -> map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(all).build(), FIT_PADDING_PX))
                all.size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(all.first(), FOCUS_ZOOM))
            }
        }
    }

    fun focus(match: GeoMatch) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(match.latitude, match.longitude), FOCUS_ZOOM))
    }

    private fun points(matches: List<GeoMatch>) =
        FeatureCollection.fromFeatures(matches.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })

    private fun pointLayer(id: String, source: String, color: String) = CircleLayer(id, source).withProperties(
        PropertyFactory.circleRadius(6f),
        PropertyFactory.circleColor(color),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(1.5f),
    )

    companion object {
        /** OpenFreeMap（キー不要・無料）。表示には帰属表示が出る */
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

        private const val TRACK_SOURCE = "gpx-track"
        private const val INTERPOLATED_SOURCE = "photos-interpolated"
        private const val NEAREST_SOURCE = "photos-nearest"
        private const val TRACK_LAYER = "gpx-track-line"
        private const val INTERPOLATED_LAYER = "photos-interpolated-points"
        private const val NEAREST_LAYER = "photos-nearest-points"
        private const val TRACK_COLOR = "#3F51B5"
        const val INTERPOLATED_COLOR = "#2E7D32"
        const val NEAREST_COLOR = "#EF6C00"
        private const val FIT_PADDING_PX = 80
        private const val FOCUS_ZOOM = 16.0
    }
}
