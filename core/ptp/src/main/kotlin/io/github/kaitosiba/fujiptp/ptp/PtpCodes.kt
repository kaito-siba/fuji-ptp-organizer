package io.github.kaitosiba.fujiptp.ptp

/** PTP (ISO 15740) / MTP のオペレーションコード。ベンダー拡張は名前を持たず 16 進で表示する。 */
object PtpOperation {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_STORAGE_INFO = 0x1005
    const val GET_NUM_OBJECTS = 0x1006
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val DELETE_OBJECT = 0x100B
    const val SEND_OBJECT_INFO = 0x100C
    const val SEND_OBJECT = 0x100D
    const val INITIATE_CAPTURE = 0x100E
    const val FORMAT_STORE = 0x100F
    const val RESET_DEVICE = 0x1010
    const val SELF_TEST = 0x1011
    const val SET_OBJECT_PROTECTION = 0x1012
    const val POWER_DOWN = 0x1013
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016
    const val RESET_DEVICE_PROP_VALUE = 0x1017
    const val TERMINATE_OPEN_CAPTURE = 0x1018
    const val MOVE_OBJECT = 0x1019
    const val COPY_OBJECT = 0x101A
    const val GET_PARTIAL_OBJECT = 0x101B
    const val INITIATE_OPEN_CAPTURE = 0x101C

    const val MTP_GET_OBJECT_PROPS_SUPPORTED = 0x9801
    const val MTP_GET_OBJECT_PROP_DESC = 0x9802
    const val MTP_GET_OBJECT_PROP_VALUE = 0x9803
    const val MTP_SET_OBJECT_PROP_VALUE = 0x9804
    const val MTP_GET_OBJECT_PROP_LIST = 0x9805

    private val names: Map<Int, String> = mapOf(
        GET_DEVICE_INFO to "GetDeviceInfo",
        OPEN_SESSION to "OpenSession",
        CLOSE_SESSION to "CloseSession",
        GET_STORAGE_IDS to "GetStorageIDs",
        GET_STORAGE_INFO to "GetStorageInfo",
        GET_NUM_OBJECTS to "GetNumObjects",
        GET_OBJECT_HANDLES to "GetObjectHandles",
        GET_OBJECT_INFO to "GetObjectInfo",
        GET_OBJECT to "GetObject",
        GET_THUMB to "GetThumb",
        DELETE_OBJECT to "DeleteObject",
        SEND_OBJECT_INFO to "SendObjectInfo",
        SEND_OBJECT to "SendObject",
        INITIATE_CAPTURE to "InitiateCapture",
        FORMAT_STORE to "FormatStore",
        RESET_DEVICE to "ResetDevice",
        SELF_TEST to "SelfTest",
        SET_OBJECT_PROTECTION to "SetObjectProtection",
        POWER_DOWN to "PowerDown",
        GET_DEVICE_PROP_DESC to "GetDevicePropDesc",
        GET_DEVICE_PROP_VALUE to "GetDevicePropValue",
        SET_DEVICE_PROP_VALUE to "SetDevicePropValue",
        RESET_DEVICE_PROP_VALUE to "ResetDevicePropValue",
        TERMINATE_OPEN_CAPTURE to "TerminateOpenCapture",
        MOVE_OBJECT to "MoveObject",
        COPY_OBJECT to "CopyObject",
        GET_PARTIAL_OBJECT to "GetPartialObject",
        INITIATE_OPEN_CAPTURE to "InitiateOpenCapture",
        MTP_GET_OBJECT_PROPS_SUPPORTED to "GetObjectPropsSupported",
        MTP_GET_OBJECT_PROP_DESC to "GetObjectPropDesc",
        MTP_GET_OBJECT_PROP_VALUE to "GetObjectPropValue",
        MTP_SET_OBJECT_PROP_VALUE to "SetObjectPropValue",
        MTP_GET_OBJECT_PROP_LIST to "GetObjectPropList",
    )

    fun nameOf(code: Int): String = names[code] ?: "Vendor/Unknown(${code.toHex16()})"
}

/** PTP / MTP の ObjectFormat コード。 */
object PtpObjectFormat {
    const val UNDEFINED = 0x3000
    const val ASSOCIATION = 0x3001
    const val SCRIPT = 0x3002
    const val EXECUTABLE = 0x3003
    const val TEXT = 0x3004
    const val HTML = 0x3005
    const val DPOF = 0x3006
    const val AIFF = 0x3007
    const val WAV = 0x3008
    const val MP3 = 0x3009
    const val AVI = 0x300A
    const val MPEG = 0x300B
    const val ASF = 0x300C
    const val QUICKTIME = 0x300D
    const val UNDEFINED_IMAGE = 0x3800
    const val EXIF_JPEG = 0x3801
    const val TIFF_EP = 0x3802
    const val BMP = 0x3804
    const val GIF = 0x3807
    const val JFIF = 0x3808
    const val PNG = 0x380B
    const val TIFF = 0x380D
    // 以下 2 つは PTP 1.1 本体ではなく libgphoto2 等での慣用値。表示用にのみ使う。
    const val DNG = 0x3811
    const val HEIF = 0x3812
    const val MTP_MP4_CONTAINER = 0xB982
    const val MTP_3GP_CONTAINER = 0xB984

    private val names: Map<Int, String> = mapOf(
        UNDEFINED to "Undefined",
        ASSOCIATION to "Association(Folder)",
        SCRIPT to "Script",
        EXECUTABLE to "Executable",
        TEXT to "Text",
        HTML to "HTML",
        DPOF to "DPOF",
        AIFF to "AIFF",
        WAV to "WAV",
        MP3 to "MP3",
        AVI to "AVI",
        MPEG to "MPEG",
        ASF to "ASF",
        QUICKTIME to "QuickTime",
        UNDEFINED_IMAGE to "UndefinedImage",
        EXIF_JPEG to "EXIF/JPEG",
        TIFF_EP to "TIFF/EP",
        BMP to "BMP",
        GIF to "GIF",
        JFIF to "JFIF",
        PNG to "PNG",
        TIFF to "TIFF",
        DNG to "DNG",
        HEIF to "HEIF",
        MTP_MP4_CONTAINER to "MP4",
        MTP_3GP_CONTAINER to "3GP",
    )

    fun nameOf(code: Int): String = names[code] ?: "Vendor/Unknown(${code.toHex16()})"
}

fun Int.toHex16(): String = "0x%04X".format(this and 0xFFFF)

fun Int.toHex32(): String = "0x%08X".format(this)
