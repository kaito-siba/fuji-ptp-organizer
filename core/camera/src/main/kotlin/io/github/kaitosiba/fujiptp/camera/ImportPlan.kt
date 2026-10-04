package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import java.time.LocalDateTime

/** 取り込む形式。 */
enum class ImportFormat {
    /** すべて（動画を含む） */
    ALL,

    /** JPEG / HEIF のみ */
    DEVELOPED_ONLY,

    /** RAW のみ */
    RAW_ONLY,
    ;

    fun includes(kind: MediaKind): Boolean = when (this) {
        ALL -> kind != MediaKind.FOLDER
        DEVELOPED_ONLY -> kind == MediaKind.JPEG || kind == MediaKind.HEIF
        RAW_ONLY -> kind == MediaKind.RAW
    }
}

data class ImportItem(
    val info: PtpObjectInfo,
    val kind: MediaKind,
    val stableId: StableObjectId,
    /** 保存先フォルダの日付に使う（Shot の撮影日時） */
    val capturedAt: LocalDateTime?,
)

data class ImportPlan(
    val items: List<ImportItem>,
    /** 取込済みのためスキップしたファイル数 */
    val skippedImported: Int,
) {
    val totalBytes: Long get() = items.sumOf { it.info.compressedSize }
}

/**
 * 選択した Shot から取り込むファイルを決める。
 *
 * @param importedKeys 取込済みファイルの [StableObjectId.key]
 */
fun planImport(
    shots: List<Shot>,
    format: ImportFormat,
    cameraSerial: String,
    importedKeys: Set<String>,
    skipImported: Boolean = true,
): ImportPlan {
    var skipped = 0
    val items = shots.flatMap { shot ->
        shot.members
            .filter { format.includes(it.kind) }
            .sortedBy { it.info.name }
            .mapNotNull { member ->
                val id = StableObjectId.of(cameraSerial, member.info)
                if (skipImported && id.key in importedKeys) {
                    skipped++
                    null
                } else {
                    ImportItem(member.info, member.kind, id, shot.capturedAt)
                }
            }
    }
    return ImportPlan(items, skipped)
}

/** Shot の取込状況。 */
enum class ImportStatus { NONE, PARTIAL, ALL }

fun Shot.importStatus(cameraSerial: String, importedKeys: Set<String>): ImportStatus {
    val count = members.count { StableObjectId.of(cameraSerial, it.info).key in importedKeys }
    return when (count) {
        0 -> ImportStatus.NONE
        members.size -> ImportStatus.ALL
        else -> ImportStatus.PARTIAL
    }
}
