package io.github.kaitosiba.fujiptp.geotag

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.ZoneId

data class GeotagConfig(
    /** GPSLogger の GPX フォルダ（SAF のツリー URI） */
    val gpxTreeUri: Uri? = null,
    /** カメラ時計の補正（カメラが遅れていれば正） */
    val clockCorrection: Duration = Duration.ZERO,
    /** EXIF にオフセットがないときに使うタイムゾーン */
    val fallbackZone: ZoneId = ZoneId.systemDefault(),
    val matchOptions: MatchOptions = MatchOptions(),
)

/** ジオタグの設定。値が少ないので SharedPreferences に置く。 */
class GeotagSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("geotag", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<GeotagConfig> = _config.asStateFlow()

    fun setGpxTree(uri: Uri) = update { it.copy(gpxTreeUri = uri) }

    fun setClockCorrection(correction: Duration) = update { it.copy(clockCorrection = correction) }

    private fun update(transform: (GeotagConfig) -> GeotagConfig) {
        val next = transform(_config.value)
        prefs.edit()
            .putString(KEY_GPX_TREE, next.gpxTreeUri?.toString())
            .putLong(KEY_CLOCK_CORRECTION_SECONDS, next.clockCorrection.seconds)
            .putString(KEY_FALLBACK_ZONE, next.fallbackZone.id)
            .apply()
        _config.value = next
    }

    private fun load() = GeotagConfig(
        gpxTreeUri = prefs.getString(KEY_GPX_TREE, null)?.let(Uri::parse),
        clockCorrection = Duration.ofSeconds(prefs.getLong(KEY_CLOCK_CORRECTION_SECONDS, 0)),
        fallbackZone = prefs.getString(KEY_FALLBACK_ZONE, null)
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault(),
    )

    private companion object {
        const val KEY_GPX_TREE = "gpx_tree_uri"
        const val KEY_CLOCK_CORRECTION_SECONDS = "clock_correction_seconds"
        const val KEY_FALLBACK_ZONE = "fallback_zone"
    }
}
