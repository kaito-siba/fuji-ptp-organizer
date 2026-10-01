package io.github.kaitosiba.fujiptp.geotag

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Xml
import java.time.Instant

/** GPX フォルダ内のファイル。 */
data class GpxFile(val documentId: String, val name: String, val lastModified: Instant, val size: Long)

/**
 * SAF で選ばれた GPX フォルダ（GPSLogger の保存先）からトラックを読む。
 *
 * 一度読んだファイルは (ID, 更新日時, サイズ) が変わるまでメモリに残す。
 */
class GpxRepository(private val resolver: ContentResolver) {

    private val cache = mutableMapOf<GpxFile, List<TrackPoint>>()

    fun listFiles(treeUri: Uri): List<GpxFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val files = mutableListOf<GpxFile>()
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (!name.endsWith(".gpx", ignoreCase = true)) continue
                files += GpxFile(
                    documentId = cursor.getString(0),
                    name = name,
                    lastModified = Instant.ofEpochMilli(cursor.getLong(2)),
                    size = cursor.getLong(3),
                )
            }
        }
        return files.sortedBy { it.lastModified }
    }

    /**
     * [since] 以降に更新されたファイルのトラックをまとめて返す。
     *
     * 撮影時刻を含むファイルは必ず撮影時刻より後に更新されているので、それより前に更新されたものは読まない。
     */
    @Synchronized
    fun load(treeUri: Uri, since: Instant?, onProgress: (String) -> Unit = {}): GpxLoadResult {
        val files = listFiles(treeUri).filter { since == null || it.lastModified >= since }
        val points = mutableListOf<TrackPoint>()
        var failed = 0
        for (file in files) {
            val cached = cache[file]
            if (cached != null) {
                points += cached
                continue
            }
            onProgress(file.name)
            val parsed = try {
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId)
                resolver.openInputStream(uri)?.use { GpxParser.parse(it, Xml.newPullParser()) }.orEmpty()
            } catch (e: Exception) {
                failed++
                continue
            }
            cache[file] = parsed
            points += parsed
        }
        return GpxLoadResult(TrackIndex(points), files.size, failed)
    }
}

data class GpxLoadResult(val index: TrackIndex, val fileCount: Int, val failedFiles: Int)
