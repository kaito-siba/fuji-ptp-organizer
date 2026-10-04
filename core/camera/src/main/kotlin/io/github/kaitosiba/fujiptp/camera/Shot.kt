package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * UI 上の 1 枚。RAW + JPEG のように同じ撮影から生成された複数ファイルをまとめる。
 *
 * @property capturedAt カメラ時計の壁時計時刻（メンバーのうち最も早いもの）。日付グルーピングと並び順に使う
 */
data class Shot(val key: String, val members: List<Member>, val capturedAt: LocalDateTime? = null) {
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
fun groupIntoShots(
    objects: List<PtpObjectInfo>,
    profile: CameraProfile,
    hostZone: ZoneId = ZoneId.systemDefault(),
): List<Shot> =
    objects
        .filterNot { it.isFolder }
        .groupBy { profile.shotKey(it) }
        .map { (key, infos) ->
            Shot(
                key = key,
                members = infos.map { Shot.Member(it, profile.classify(it)) },
                capturedAt = infos.mapNotNull { profile.captureWallClock(it, hostZone) }.minOrNull(),
            )
        }

/** 新しい順（撮影日時の降順、日時不明は末尾）に並べる。 */
fun List<Shot>.sortedNewestFirst(): List<Shot> =
    sortedWith { a, b ->
        val ta = a.capturedAt
        val tb = b.capturedAt
        when {
            ta != null && tb != null && ta != tb -> tb.compareTo(ta)
            ta == null && tb != null -> 1
            ta != null && tb == null -> -1
            else -> b.primary.info.name.compareTo(a.primary.info.name)
        }
    }
