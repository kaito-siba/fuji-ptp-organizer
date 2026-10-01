package io.github.kaitosiba.fujiptp.geotag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class GeotagTest {
    private val points = javaClass.getResourceAsStream("/gpx/gpslogger.gpx")!!.use { GpxParser.parse(it, KXmlParser()) }
    private val index = TrackIndex(points)

    @Test
    fun `parses GPSLogger trackpoints and skips points without time`() {
        assertEquals(4, points.size)
        assertEquals(TrackPoint(Instant.parse("2026-09-30T17:00:00Z"), 49.2827, -123.1207, 10.0), points[0])
        assertEquals(Instant.parse("2026-09-30T17:30:00Z"), points[3].time)
        assertNull(points[3].elevation)
    }

    @Test
    fun `interpolates between close trackpoints`() {
        val match = index.locate(Instant.parse("2026-09-30T17:00:30Z"))!!
        assertEquals(MatchMethod.INTERPOLATED, match.method)
        assertEquals(49.2832, match.latitude, 1e-9)
        assertEquals(-123.1197, match.longitude, 1e-9)
        assertEquals(12.0, match.elevation!!, 1e-9)
        assertEquals(Duration.ofSeconds(30), match.timeDistance)
    }

    @Test
    fun `uses nearest point near a gap and gives up far from the track`() {
        // 17:02〜17:30 は 28 分空いているので補間しない
        val nearGap = index.locate(Instant.parse("2026-09-30T17:03:00Z"))!!
        assertEquals(MatchMethod.NEAREST, nearGap.method)
        assertEquals(49.2847, nearGap.latitude, 1e-9)
        assertNull(index.locate(Instant.parse("2026-09-30T17:15:00Z")))
        assertNull(index.locate(Instant.parse("2026-09-30T12:00:00Z")))
    }

    @Test
    fun `interpolates longitude across the date line`() {
        val dateLine = TrackIndex(
            listOf(
                TrackPoint(Instant.parse("2026-01-01T00:00:00Z"), 0.0, 179.0),
                TrackPoint(Instant.parse("2026-01-01T00:01:00Z"), 0.0, -179.0),
            ),
        )
        assertEquals(180.0, kotlin.math.abs(dateLine.locate(Instant.parse("2026-01-01T00:00:30Z"))!!.longitude), 1e-9)
    }

    @Test
    fun `photo taken in Vancouver with camera still on Japan time matches the track`() {
        // カメラは日本時間のまま（+09:00）。UTC 17:00:30 は日本時間 10/1 02:00:30
        val photo = PhotoTime.toInstant(
            ExifCaptureTime("2026:10:01 02:00:30", "19", "+09:00"),
            fallbackZone = ZoneId.of("America/Vancouver"),
        )!!
        assertEquals(TimeSource.EXIF_OFFSET, photo.source)
        assertEquals(Instant.parse("2026-09-30T17:00:30.190Z"), photo.instant)
        assertNotNull(index.locate(photo.instant))
    }

    @Test
    fun `missing offset falls back to the configured zone and clock correction applies`() {
        val photo = PhotoTime.toInstant(
            ExifCaptureTime("2026:09:30 10:00:00"),
            fallbackZone = ZoneId.of("America/Vancouver"),
            clockCorrection = Duration.ofSeconds(30),
        )!!
        assertEquals(TimeSource.ASSUMED_ZONE, photo.source)
        assertEquals(Instant.parse("2026-09-30T17:00:30Z"), photo.instant)
        assertNull(PhotoTime.toInstant(ExifCaptureTime("broken"), ZoneId.of("UTC")))
    }

    @Test
    fun `suggests an hour shift when the clock hands were set to local time with the Japan offset`() {
        // 時計の針だけ現地（UTC-7）に合わせ、TZ 設定は +09:00 のまま → UTC が 16 時間早くずれる
        val photos = listOf("17:00:10", "17:00:40", "17:01:20", "17:01:50").map {
            Instant.parse("2026-09-30T${it}Z").minus(Duration.ofHours(16))
        }
        val suggestion = ClockShiftEstimator.suggest(photos, index)!!
        assertEquals(Duration.ofHours(16), suggestion.shift)
        assertEquals(4, suggestion.matchedWithShift)
        assertEquals(0, suggestion.matchedWithoutShift)

        val onTrack = photos.map { it.plus(Duration.ofHours(16)) }
        assertNull(ClockShiftEstimator.suggest(onTrack, index))
    }
}
