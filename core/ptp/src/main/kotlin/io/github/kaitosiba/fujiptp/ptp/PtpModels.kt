package io.github.kaitosiba.fujiptp.ptp

import kotlinx.serialization.Serializable

/** PTP DeviceInfo のうち、トランスポート実装から取得できる項目。 */
@Serializable
data class PtpDeviceInfo(
    val manufacturer: String?,
    val model: String?,
    val version: String?,
    val serialNumber: String?,
    val operationsSupported: List<Int> = emptyList(),
    val eventsSupported: List<Int> = emptyList(),
) {
    fun supportsOperation(code: Int): Boolean = code in operationsSupported
}

@Serializable
data class PtpStorageInfo(
    val storageId: Int,
    val description: String?,
    val volumeIdentifier: String?,
    val maxCapacity: Long,
    val freeSpace: Long,
)

/**
 * PTP ObjectInfo。
 *
 * [dateCreatedMillis] / [dateModifiedMillis] はトランスポート実装が PTP の日時文字列
 * (TZ なし) を解釈した値。android.mtp は端末のローカル TZ として解釈するため、
 * 撮影時刻の正確な扱い（ジオタグ等）には EXIF を使うこと。
 */
@Serializable
data class PtpObjectInfo(
    val handle: Int,
    val storageId: Int,
    val format: Int,
    val protectionStatus: Int = 0,
    val compressedSize: Long,
    val thumbFormat: Int = 0,
    val thumbCompressedSize: Long = 0,
    val thumbPixWidth: Long = 0,
    val thumbPixHeight: Long = 0,
    val imagePixWidth: Long = 0,
    val imagePixHeight: Long = 0,
    val imagePixDepth: Long = 0,
    val parent: Int,
    val associationType: Int = 0,
    val associationDesc: Int = 0,
    val sequenceNumber: Long = 0,
    val name: String,
    val dateCreatedMillis: Long = 0,
    val dateModifiedMillis: Long = 0,
    val keywords: String? = null,
) {
    val isFolder: Boolean get() = format == PtpObjectFormat.ASSOCIATION

    val extension: String get() = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
}
