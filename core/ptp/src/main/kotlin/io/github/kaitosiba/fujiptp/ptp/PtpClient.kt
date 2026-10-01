package io.github.kaitosiba.fujiptp.ptp

import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * カメラとの PTP セッション。
 *
 * PTP は 1 セッションで同時に 1 トランザクションしか流せないため、実装は呼び出しを直列化すること。
 * 失敗は [PtpException] で通知する。
 */
interface PtpClient : Closeable {
    suspend fun deviceInfo(): PtpDeviceInfo

    suspend fun storageIds(): List<Int>

    suspend fun storageInfo(storageId: Int): PtpStorageInfo

    /**
     * @param format 0 なら全形式
     * @param parent [PARENT_ALL] で全階層、[PARENT_ROOT] でルート直下、それ以外はそのフォルダ直下
     */
    suspend fun objectHandles(storageId: Int, format: Int = 0, parent: Int = PARENT_ALL): List<Int>

    suspend fun objectInfo(handle: Int): PtpObjectInfo

    /** GetThumb。カメラが返したサムネイル（通常 JPEG）のバイト列。 */
    suspend fun thumbnail(handle: Int): ByteArray

    /** GetPartialObject。[PtpDeviceInfo.supportsOperation] で対応を確認してから呼ぶこと。 */
    suspend fun partialObject(handle: Int, offset: Long, size: Int): ByteArray

    /** オブジェクト全体を [destination] に書き出す。 */
    suspend fun downloadTo(handle: Int, destination: File)

    companion object {
        /** GetObjectHandles の parent 引数: 全階層のオブジェクト。 */
        const val PARENT_ALL: Int = 0

        /** GetObjectHandles の parent 引数: ストレージのルート直下。 */
        const val PARENT_ROOT: Int = -1 // 0xFFFFFFFF
    }
}

class PtpException(message: String, cause: Throwable? = null) : IOException(message, cause)
