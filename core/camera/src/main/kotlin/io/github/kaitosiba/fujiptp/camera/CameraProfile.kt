package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import java.time.LocalDateTime
import java.time.ZoneId

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

    /** GetThumb でサムネイルが取れる種類か。false の種類は UI でプレースホルダを出す */
    fun hasPtpThumbnail(kind: MediaKind): Boolean

    /**
     * ObjectInfo の日時をカメラ時計の壁時計時刻（TZ なし）として返す。日付ごとのグルーピング用。
     *
     * android.mtp は PTP の日時文字列を端末の TZ で解釈するため、[hostZone] で元に戻す。
     * 撮影時刻の正確な値（ジオタグ等）には EXIF の DateTimeOriginal を使うこと。
     */
    fun captureWallClock(info: PtpObjectInfo, hostZone: ZoneId): LocalDateTime?

    val previewStrategy: PreviewStrategy
    val transport: TransportPreference
    val clockPolicy: CameraClockPolicy
    val quirks: Set<Quirk>
}
