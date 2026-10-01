package io.github.kaitosiba.fujiptp.geotag

import java.time.Duration
import java.time.Instant

/** 位置の決め方。 */
enum class MatchMethod {
    /** 前後のトラックポイントの間を線形補間した */
    INTERPOLATED,

    /** 最も近いトラックポイントをそのまま使った（記録の切れ目付近） */
    NEAREST,
}

data class GeoMatch(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double?,
    val method: MatchMethod,
    /** 使ったトラックポイントと撮影時刻の差（補間なら前後の近い方） */
    val timeDistance: Duration,
)

data class MatchOptions(
    /** トラックポイントの間隔がこれ以下なら補間する */
    val maxInterpolationGap: Duration = Duration.ofMinutes(5),
    /** 補間できないとき、最も近い点との時間差がこれ以下なら採用する */
    val nearestTolerance: Duration = Duration.ofMinutes(2),
)

/** 複数の GPX をまとめて時刻順に並べた索引。撮影時刻（UTC）から位置を引く。 */
class TrackIndex(points: List<TrackPoint>) {

    private val sorted: List<TrackPoint> = points.sortedBy { it.time }.distinctBy { it.time }
    private val times: LongArray = LongArray(sorted.size) { sorted[it].time.toEpochMilli() }

    val size: Int get() = sorted.size
    val start: Instant? get() = sorted.firstOrNull()?.time
    val end: Instant? get() = sorted.lastOrNull()?.time
    val points: List<TrackPoint> get() = sorted

    /** [from]〜[to] のトラックポイント（地図表示用） */
    fun pointsBetween(from: Instant, to: Instant): List<TrackPoint> {
        val lo = lowerBound(from.toEpochMilli())
        val hi = lowerBound(to.toEpochMilli() + 1)
        return sorted.subList(lo, hi)
    }

    fun locate(time: Instant, options: MatchOptions = MatchOptions()): GeoMatch? {
        if (sorted.isEmpty()) return null
        val t = time.toEpochMilli()
        val i = lowerBound(t) // times[i] >= t となる最初の位置

        if (i < sorted.size && times[i] == t) return sorted[i].toMatch(MatchMethod.INTERPOLATED, Duration.ZERO)

        val before = sorted.getOrNull(i - 1)
        val after = sorted.getOrNull(i)
        if (before != null && after != null) {
            val gap = Duration.between(before.time, after.time)
            if (gap <= options.maxInterpolationGap) {
                val ratio = (t - times[i - 1]).toDouble() / (times[i] - times[i - 1])
                return GeoMatch(
                    latitude = before.latitude + (after.latitude - before.latitude) * ratio,
                    longitude = interpolateLongitude(before.longitude, after.longitude, ratio),
                    elevation = if (before.elevation != null && after.elevation != null) {
                        before.elevation + (after.elevation - before.elevation) * ratio
                    } else {
                        null
                    },
                    method = MatchMethod.INTERPOLATED,
                    timeDistance = Duration.ofMillis(minOf(t - times[i - 1], times[i] - t)),
                )
            }
        }

        val nearest = listOfNotNull(before, after).minBy { kotlin.math.abs(it.time.toEpochMilli() - t) }
        val distance = Duration.ofMillis(kotlin.math.abs(nearest.time.toEpochMilli() - t))
        return if (distance <= options.nearestTolerance) nearest.toMatch(MatchMethod.NEAREST, distance) else null
    }

    private fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun TrackPoint.toMatch(method: MatchMethod, distance: Duration) =
        GeoMatch(latitude, longitude, elevation, method, distance)

    private companion object {
        /** 日付変更線をまたぐ場合は短い方向に補間する */
        fun interpolateLongitude(a: Double, b: Double, ratio: Double): Double {
            var delta = b - a
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            var result = a + delta * ratio
            if (result > 180) result -= 360
            if (result < -180) result += 360
            return result
        }
    }
}
