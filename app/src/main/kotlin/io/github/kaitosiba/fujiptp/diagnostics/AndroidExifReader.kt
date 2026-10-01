package io.github.kaitosiba.fujiptp.diagnostics

import androidx.exifinterface.media.ExifInterface
import io.github.kaitosiba.fujiptp.ptp.diagnostics.ExifReader
import java.io.File

/** 診断で確認したい EXIF タグ（撮影時刻・TZ・機種・GPS）を読む。 */
object AndroidExifReader : ExifReader {
    private val tags = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP,
    )

    override fun read(file: File): Map<String, String> {
        val exif = ExifInterface(file)
        return tags.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
    }
}
