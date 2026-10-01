package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo

/** USB 層の識別子。Android の UsbDevice に依存しないよう素の値で持つ。 */
data class UsbIdentity(val vendorId: Int, val productId: Int)

enum class MediaKind { FOLDER, JPEG, HEIF, RAW, VIDEO, OTHER }

/** 接続待ち画面に出す手順。[verified] が false のものは実機未確認。 */
data class ConnectionGuide(val steps: List<String>, val verified: Boolean)

enum class PreviewStrategy {
    /** GetThumb でカメラが返すサムネイルを使う */
    PTP_THUMBNAIL,
}

enum class TransportPreference {
    /** android.mtp.MtpDevice */
    FRAMEWORK,

    /** 自前 PTP 実装（ベンダー拡張オペレーションが必要な場合。未実装） */
    RAW_USB,
}

/** カメラ時計の扱い。null は未確認。 */
data class CameraClockPolicy(val writesExifOffsetTime: Boolean?)

/** 実行時に検出できない機種固有の不具合・癖。 */
enum class Quirk {
    /** GetObjectHandles(parent=ALL) が使えず、フォルダを辿る必要がある */
    NO_RECURSIVE_OBJECT_HANDLES,
}

/**
 * 機種ごとの差分。
 *
 * DeviceInfo の対応オペレーションなど実行時に検出できることはここに書かず、検出できないことだけを持つ。
 */
interface CameraProfile {
    val id: String
    val displayName: String

    /** 実機で動作確認済みか */
    val verified: Boolean

    /** マッチ度。0 は不一致。[ProfileRegistry] が最大スコアのものを選ぶ */
    fun match(usb: UsbIdentity?, info: PtpDeviceInfo?): Int

    val connectionGuide: ConnectionGuide

    fun classify(info: PtpObjectInfo): MediaKind

    /** 同一ショット（JPEG + RAF 等）をまとめるためのキー */
    fun shotKey(info: PtpObjectInfo): String

    val previewStrategy: PreviewStrategy
    val transport: TransportPreference
    val clockPolicy: CameraClockPolicy
    val quirks: Set<Quirk>
}
