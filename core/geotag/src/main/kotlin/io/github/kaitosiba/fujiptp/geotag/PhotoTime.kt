package io.github.kaitosiba.fujiptp.geotag

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * EXIF の撮影時刻。
 *
 * @property dateTimeOriginal "yyyy:MM:dd HH:mm:ss"
 * @property subSecTimeOriginal 小数秒の数字列（"19" なら 0.19 秒）
 * @property offsetTimeOriginal "+09:00" など
 */
data class ExifCaptureTime(
    val dateTimeOriginal: String?,
    val subSecTimeOriginal: String? = null,
    val offsetTimeOriginal: String? = null,
)

/** 撮影時刻（UTC）の求め方。 */
enum class TimeSource {
    /** EXIF の OffsetTimeOriginal を使った */
    EXIF_OFFSET,

    /** オフセットがないので設定のタイムゾーンで解釈した */
    ASSUMED_ZONE,
}

data class PhotoInstant(val instant: Instant, val source: TimeSource, val localTime: LocalDateTime)

object PhotoTime {
    private val exifFormat = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * EXIF の撮影時刻を UTC にする。
     *
     * @param fallbackZone OffsetTimeOriginal がないときに使うカメラのタイムゾーン
     * @param clockCorrection カメラ時計のずれの補正（カメラが 30 秒遅れていれば +30 秒）
     */
    fun toInstant(exif: ExifCaptureTime, fallbackZone: ZoneId, clockCorrection: Duration = Duration.ZERO): PhotoInstant? {
        val local = exif.dateTimeOriginal?.let { parseLocal(it) } ?: return null
        val withSubSec = exif.subSecTimeOriginal
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.let { local.plusNanos(("0.$it".toDouble() * 1_000_000_000).toLong()) }
            ?: local
        val offset = exif.offsetTimeOriginal?.let { parseOffset(it) }
        val instant = if (offset != null) withSubSec.toInstant(offset) else withSubSec.atZone(fallbackZone).toInstant()
        return PhotoInstant(
            instant = instant.plus(clockCorrection),
            source = if (offset != null) TimeSource.EXIF_OFFSET else TimeSource.ASSUMED_ZONE,
            localTime = withSubSec,
        )
    }

    private fun parseLocal(text: String): LocalDateTime? = try {
        LocalDateTime.parse(text.trim(), exifFormat)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun parseOffset(text: String): ZoneOffset? = try {
        ZoneOffset.of(text.trim())
    } catch (e: Exception) {
        null
    }
}
