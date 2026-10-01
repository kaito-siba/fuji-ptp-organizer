package io.github.kaitosiba.fujiptp.geotag

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** 取り込んだファイルから撮影時刻の EXIF を読む（JPEG / RAF / HEIF）。 */
object ExifTimeReader {
    fun read(resolver: ContentResolver, uri: Uri): ExifCaptureTime? = try {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            ExifCaptureTime(
                dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                subSecTimeOriginal = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            )
        }
    } catch (e: Exception) {
        null
    }
}
