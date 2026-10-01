package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import io.github.kaitosiba.fujiptp.ptp.PtpObjectFormat
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** どの PTP カメラにも当てはまる既定の振る舞い。 */
open class GenericPtpProfile : CameraProfile {
    override val id: String = "generic.ptp"
    override val displayName: String = "汎用 PTP カメラ"
    override val verified: Boolean = false

    override fun match(usb: UsbIdentity?, info: PtpDeviceInfo?): Int = 1

    override val connectionGuide: ConnectionGuide = ConnectionGuide(
        steps = listOf(
            "カメラの USB 接続モードを PTP（または MTP / カードリーダー相当）にする",
            "USB ケーブルでスマートフォンと接続し、カメラの電源を入れる",
            "USB アクセスの許可ダイアログで「許可」を選ぶ",
        ),
        verified = false,
    )

    override fun classify(info: PtpObjectInfo): MediaKind {
        if (info.isFolder) return MediaKind.FOLDER
        extensionKinds[info.extension]?.let { return it }
        return when (info.format) {
            PtpObjectFormat.EXIF_JPEG, PtpObjectFormat.JFIF -> MediaKind.JPEG
            PtpObjectFormat.HEIF -> MediaKind.HEIF
            PtpObjectFormat.DNG, PtpObjectFormat.TIFF_EP -> MediaKind.RAW
            PtpObjectFormat.QUICKTIME, PtpObjectFormat.AVI, PtpObjectFormat.MPEG,
            PtpObjectFormat.MTP_MP4_CONTAINER, PtpObjectFormat.MTP_3GP_CONTAINER,
            -> MediaKind.VIDEO
            else -> MediaKind.OTHER
        }
    }

    override fun shotKey(info: PtpObjectInfo): String =
        "${info.storageId}:${info.parent}:${info.name.substringBeforeLast('.').uppercase()}"

    override fun hasPtpThumbnail(kind: MediaKind): Boolean = kind != MediaKind.FOLDER

    override fun captureWallClock(info: PtpObjectInfo, hostZone: ZoneId): LocalDateTime? =
        info.dateCreatedMillis.takeIf { it > 0 }
            ?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), hostZone) }

    override val previewStrategy: PreviewStrategy = PreviewStrategy.PTP_THUMBNAIL
    override val transport: TransportPreference = TransportPreference.FRAMEWORK
    override val clockPolicy: CameraClockPolicy = CameraClockPolicy(writesExifOffsetTime = null)
    override val quirks: Set<Quirk> = emptySet()

    companion object {
        private val extensionKinds: Map<String, MediaKind> = mapOf(
            "jpg" to MediaKind.JPEG,
            "jpeg" to MediaKind.JPEG,
            "hif" to MediaKind.HEIF,
            "heif" to MediaKind.HEIF,
            "heic" to MediaKind.HEIF,
            "raf" to MediaKind.RAW,
            "dng" to MediaKind.RAW,
            "mov" to MediaKind.VIDEO,
            "mp4" to MediaKind.VIDEO,
        )
    }
}

/** Fujifilm 共通。 */
open class FujifilmProfile : GenericPtpProfile() {
    override val id: String = "fujifilm"
    override val displayName: String = "Fujifilm（共通）"

    override fun match(usb: UsbIdentity?, info: PtpDeviceInfo?): Int {
        val byVendor = usb?.vendorId == FUJIFILM_VENDOR_ID
        val byName = info?.manufacturer?.contains("FUJIFILM", ignoreCase = true) == true
        return if (byVendor || byName) 50 else 0
    }

    override fun classify(info: PtpObjectInfo): MediaKind =
        if (info.format == FORMAT_RAF) MediaKind.RAW else super.classify(info)

    /** 動画（MOV）は GetThumb が失敗する（X100VI FW1.32 で確認） */
    override fun hasPtpThumbnail(kind: MediaKind): Boolean =
        kind != MediaKind.VIDEO && super.hasPtpThumbnail(kind)

    /**
     * Fujifilm は ObjectInfo の Keywords にカメラ時計の日時文字列（例: "20260922T052736"）を入れてくるので、
     * 端末 TZ の影響を受けないそちらを優先する。
     */
    override fun captureWallClock(info: PtpObjectInfo, hostZone: ZoneId): LocalDateTime? =
        info.keywords?.let { parsePtpDateTime(it) } ?: super.captureWallClock(info, hostZone)

    override val connectionGuide: ConnectionGuide = ConnectionGuide(
        steps = listOf(
            "カメラの MENU →「ネットワーク/USB設定」→「PC接続モード」を「USBカードリーダー」にする",
            "USB-C ケーブルでスマートフォンと接続し、カメラの電源を入れる",
            "USB アクセスの許可ダイアログで「許可」を選ぶ",
        ),
        verified = false,
    )

    companion object {
        const val FUJIFILM_VENDOR_ID: Int = 0x04CB

        /** RAF のベンダー ObjectFormat コード（X100VI FW1.32 で確認） */
        const val FORMAT_RAF: Int = 0xB103

        private val ptpDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        /** PTP の日時文字列（"YYYYMMDDThhmmss[.s][Z|±hhmm]"）の先頭 15 文字を壁時計時刻として読む */
        fun parsePtpDateTime(text: String): LocalDateTime? {
            if (text.length < 15) return null
            return try {
                LocalDateTime.parse(text.substring(0, 15), ptpDateTime)
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }
}

/**
 * FUJIFILM X100VI。
 *
 * FW1.32 の実機診断（fixtures/dumps/x100vi-fw132.json）で確認した内容:
 * PC接続モード「USBカードリーダー」で接続、USB PID 0x0305、RAF は ObjectFormat 0xB103、GetPartialObject 対応、全階層の一覧取得可、
 * JPEG / RAF は GetThumb 可・MOV は不可、EXIF に OffsetTimeOriginal あり。
 */
object FujifilmX100VIProfile : FujifilmProfile() {
    override val id: String = "fujifilm.x100vi"
    override val displayName: String = "FUJIFILM X100VI"
    override val verified: Boolean = true
    override val clockPolicy: CameraClockPolicy = CameraClockPolicy(writesExifOffsetTime = true)

    /** 「USBカードリーダー」モードで接続できることを確認済み */
    override val connectionGuide: ConnectionGuide = super.connectionGuide.copy(verified = true)

    const val USB_PRODUCT_ID: Int = 0x0305

    override fun match(usb: UsbIdentity?, info: PtpDeviceInfo?): Int {
        if (super.match(usb, info) == 0) return 0
        // "X100V" と取り違えないよう完全一致で判定する
        val model = info?.model?.trim()?.uppercase() ?: return 0
        return if (model == "X100VI") 100 else 0
    }
}
