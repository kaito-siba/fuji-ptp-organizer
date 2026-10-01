package io.github.kaitosiba.fujiptp.geotag

import java.time.Duration
import java.time.Instant

data class ClockShiftSuggestion(
    /** この時間だけ撮影時刻をずらすと GPX に収まる */
    val shift: Duration,
    val matchedWithShift: Int,
    val matchedWithoutShift: Int,
    val total: Int,
)

/**
 * カメラの時計を時間単位で間違えていないか（例: TZ は日本のまま、時計の針だけ現地に合わせた）を推定する。
 *
 * 時間単位のずらし（±24 時間まで、30 分刻み）を試し、そのままより大幅に多くの写真が GPX に収まるものを提案する。
 */
object ClockShiftEstimator {

    fun suggest(
        photos: List<Instant>,
        index: TrackIndex,
        options: MatchOptions = MatchOptions(),
        minImprovementRatio: Double = 0.5,
    ): ClockShiftSuggestion? {
        if (photos.isEmpty() || index.size == 0) return null
        fun matched(shift: Duration) = photos.count { index.locate(it.plus(shift), options) != null }

        val base = matched(Duration.ZERO)
        val candidates = (-48..48).filter { it != 0 }.map { Duration.ofMinutes(it * 30L) }
        val best = candidates.map { it to matched(it) }.maxByOrNull { it.second } ?: return null
        val (shift, count) = best
        // ずらした方が対象の半分以上多く収まる場合だけ提案する
        return if (count - base >= photos.size * minImprovementRatio) {
            ClockShiftSuggestion(shift, count, base, photos.size)
        } else {
            null
        }
    }
}
