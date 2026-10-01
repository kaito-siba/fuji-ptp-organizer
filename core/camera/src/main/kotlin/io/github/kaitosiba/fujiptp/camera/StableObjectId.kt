package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo

/**
 * セッションをまたいで同じファイルを指す識別子。ObjectHandle は接続ごとに変わり得るので、
 * サムネイルのキャッシュや取込済み判定にはこちらを使う。
 */
data class StableObjectId(
    val cameraSerial: String,
    val fileName: String,
    val size: Long,
    /** カメラ由来の日時（Keywords、なければ ObjectInfo の日時） */
    val captureStamp: String,
) {
    val key: String get() = "$cameraSerial/$fileName/$size/$captureStamp"

    companion object {
        fun of(cameraSerial: String, info: PtpObjectInfo) = StableObjectId(
            cameraSerial = cameraSerial,
            fileName = info.name,
            size = info.compressedSize,
            captureStamp = info.keywords?.takeIf { it.isNotBlank() } ?: info.dateCreatedMillis.toString(),
        )
    }
}
