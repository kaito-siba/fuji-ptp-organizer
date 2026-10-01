package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import io.github.kaitosiba.fujiptp.ptp.PtpObjectFormat
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo

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
    }
}

/** FUJIFILM X100VI。値の多くは M0 の実機診断で確定させる。 */
object FujifilmX100VIProfile : FujifilmProfile() {
    override val id: String = "fujifilm.x100vi"
    override val displayName: String = "FUJIFILM X100VI"

    override fun match(usb: UsbIdentity?, info: PtpDeviceInfo?): Int {
        if (super.match(usb, info) == 0) return 0
        // "X100V" と取り違えないよう完全一致で判定する
        val model = info?.model?.trim()?.uppercase() ?: return 0
        return if (model == "X100VI") 100 else 0
    }
}
