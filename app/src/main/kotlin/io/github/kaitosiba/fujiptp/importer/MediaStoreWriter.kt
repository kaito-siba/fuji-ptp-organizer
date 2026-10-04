package io.github.kaitosiba.fujiptp.importer

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.kaitosiba.fujiptp.camera.ImportItem
import io.github.kaitosiba.fujiptp.camera.MediaKind
import java.io.IOException
import java.io.OutputStream

/**
 * 取り込んだファイルを MediaStore に保存する。
 *
 * 保存先は `Pictures/FujiPTP/<撮影日>/`（動画は `Movies/...`）。書き込み中は IS_PENDING にしておき、
 * 完了したら公開、失敗したら削除する。画像コレクションが受け付けない MIME だった場合は Downloads に保存する。
 */
class MediaStoreWriter(private val resolver: ContentResolver) {

    data class Target(val uri: Uri, val relativePath: String)

    fun create(item: ImportItem): Target {
        val date = item.capturedAt?.toLocalDate()?.toString() ?: "unknown-date"
        val mime = mimeTypeOf(item.info.extension)
        val primary = if (item.kind == MediaKind.VIDEO) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
        }
        val fallback = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            Environment.DIRECTORY_DOWNLOADS

        for ((collection, topDir) in listOf(primary, fallback)) {
            val relativePath = "$topDir/$APP_DIR/$date/"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.info.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(collection, values)
            } catch (e: IllegalArgumentException) {
                null
            }
            if (uri != null) return Target(uri, relativePath)
        }
        throw IOException("保存先を作れない: ${item.info.name}")
    }

    fun open(target: Target): OutputStream =
        resolver.openOutputStream(target.uri, "w") ?: throw IOException("書き込めない: ${target.uri}")

    fun publish(target: Target) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(target.uri, values, null, null)
    }

    fun discard(target: Target) {
        runCatching { resolver.delete(target.uri, null, null) }
    }

    companion object {
        const val APP_DIR = "FujiPTP"

        fun mimeTypeOf(extension: String): String = when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "raf" -> "image/x-fuji-raf"
            "hif", "heif", "heic" -> "image/heif"
            "dng" -> "image/x-adobe-dng"
            "mov" -> "video/quicktime"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
