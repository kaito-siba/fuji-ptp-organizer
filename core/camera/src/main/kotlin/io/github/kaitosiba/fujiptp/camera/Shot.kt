package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo

/** UI 上の 1 枚。RAW + JPEG のように同じ撮影から生成された複数ファイルをまとめる。 */
data class Shot(val key: String, val members: List<Member>) {
    data class Member(val info: PtpObjectInfo, val kind: MediaKind)

    /** プレビューに使うファイル（JPEG > HEIF > RAW > VIDEO > その他） */
    val primary: Member get() = members.minBy { previewPriority.indexOf(it.kind) }

    val kinds: Set<MediaKind> get() = members.map { it.kind }.toSet()

    private companion object {
        val previewPriority = listOf(
            MediaKind.JPEG, MediaKind.HEIF, MediaKind.RAW, MediaKind.VIDEO, MediaKind.OTHER, MediaKind.FOLDER,
        )
    }
}

/** フォルダを除いたオブジェクトを Shot にまとめる。並び順は入力順（先頭メンバー基準）を保つ。 */
fun groupIntoShots(objects: List<PtpObjectInfo>, profile: CameraProfile): List<Shot> =
    objects
        .filterNot { it.isFolder }
        .groupBy { profile.shotKey(it) }
        .map { (key, infos) -> Shot(key, infos.map { Shot.Member(it, profile.classify(it)) }) }
