package io.github.kaitosiba.fujiptp.geotag

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** GPX のトラックポイント。 */
data class TrackPoint(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
)

/**
 * GPX（1.0 / 1.1）からトラックポイント（`trkpt`）を読む。時刻のない点は捨てる。
 *
 * パーサは呼び出し側が用意する（Android は `android.util.Xml.newPullParser()`、JVM のテストは kxml2）。
 */
object GpxParser {

    fun parse(input: InputStream, parser: XmlPullParser): List<TrackPoint> {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val points = mutableListOf<TrackPoint>()
        var lat: Double? = null
        var lon: Double? = null
        var ele: Double? = null
        var time: Instant? = null
        var inPoint = false
        var textTarget: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "trkpt" -> {
                        inPoint = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        ele = null
                        time = null
                    }
                    "ele", "time" -> if (inPoint) textTarget = localName(parser.name)
                }
                XmlPullParser.TEXT -> when (textTarget) {
                    "ele" -> ele = parser.text.trim().toDoubleOrNull()
                    "time" -> time = parseTime(parser.text.trim())
                }
                XmlPullParser.END_TAG -> when (localName(parser.name)) {
                    "trkpt" -> {
                        val t = time
                        val la = lat
                        val lo = lon
                        if (t != null && la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                            points += TrackPoint(t, la, lo, ele)
                        }
                        inPoint = false
                    }
                    "ele", "time" -> textTarget = null
                }
            }
            event = parser.next()
        }
        return points
    }

    /** "2026-09-30T19:52:52Z" や小数秒・オフセット付きを受け付ける。 */
    fun parseTime(text: String): Instant? = try {
        OffsetDateTime.parse(text).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

    private fun localName(name: String): String = name.substringAfter(':')
}
