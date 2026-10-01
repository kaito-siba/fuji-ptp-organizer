package io.github.kaitosiba.fujiptp.ptp.dump

import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import io.github.kaitosiba.fujiptp.ptp.PtpStorageInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 診断画面が書き出すカメラ情報のダンプ。
 *
 * 実機から採取したダンプはそのまま [io.github.kaitosiba.fujiptp.ptp.fake.FakePtpClient] の
 * フィクスチャとして使えるため、実機なしでの UI 開発・テストに流用する。
 */
@Serializable
data class DeviceDump(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** ISO-8601（オフセット付き） */
    val createdAt: String,
    /** "usb" または "demo" */
    val source: String,
    val host: HostInfo? = null,
    val usb: UsbDump? = null,
    val deviceInfo: PtpDeviceInfo,
    val matchedProfileId: String? = null,
    val storages: List<StorageDump> = emptyList(),
    val probes: List<ProbeResult> = emptyList(),
    val thumbnails: List<ThumbnailSample> = emptyList(),
    val exifSamples: List<ExifSample> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class HostInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val appVersion: String,
    val timeZone: String,
)

@Serializable
data class UsbDump(
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String? = null,
    val productName: String? = null,
    val version: String? = null,
    val deviceClass: Int = 0,
    val deviceSubclass: Int = 0,
    val deviceProtocol: Int = 0,
    val interfaces: List<UsbInterfaceDump> = emptyList(),
)

@Serializable
data class UsbInterfaceDump(
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointDump> = emptyList(),
)

@Serializable
data class UsbEndpointDump(
    val address: Int,
    val type: Int,
    val direction: Int,
    val maxPacketSize: Int,
    val interval: Int,
)

@Serializable
data class StorageDump(
    val info: PtpStorageInfo,
    /** GetObjectHandles(parent=ALL) の件数。失敗時 null */
    val handleCountAll: Int? = null,
    /** GetObjectHandles(parent=ROOT) の件数。失敗時 null */
    val handleCountRoot: Int? = null,
    /** 取得できた ObjectInfo（件数上限あり） */
    val objects: List<PtpObjectInfo> = emptyList(),
    val objectsTruncated: Boolean = false,
)

@Serializable
enum class ProbeStatus { OK, FAILED, SKIPPED, INFO }

@Serializable
data class ProbeResult(
    val id: String,
    val title: String,
    val status: ProbeStatus,
    val detail: String,
    val durationMs: Long? = null,
)

@Serializable
data class ThumbnailSample(
    val handle: Int,
    val objectFormat: Int,
    val fileName: String,
    val size: Int,
    /** サムネイル本体。共有時に画像を含めない設定では null */
    val base64: String? = null,
)

@Serializable
data class ExifSample(
    val handle: Int,
    val fileName: String,
    val tags: Map<String, String>,
)

object DumpJson {
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(dump: DeviceDump): String = json.encodeToString(DeviceDump.serializer(), dump)

    fun decode(text: String): DeviceDump = json.decodeFromString(DeviceDump.serializer(), text)
}
