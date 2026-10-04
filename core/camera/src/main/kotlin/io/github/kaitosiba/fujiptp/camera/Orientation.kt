package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpClient
import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import kotlinx.coroutines.CancellationException
import kotlin.math.ceil

/**
 * 写真の向き（EXIF Orientation、1〜8）を本体ファイルの先頭から読む。
 *
 * GetThumb のサムネイルには EXIF が無い（X100VI で確認）ため、JPEG は先頭、RAF は埋め込み JPEG の先頭を
 * GetPartialObject で少しだけ読んで判定する。
 */
class OrientationReader(private val client: PtpClient) {

    /** @return 向き。判定できなければ null */
    suspend fun read(info: PtpObjectInfo, kind: MediaKind): Int? = try {
        when {
            kind == MediaKind.JPEG -> JpegExif.readOrientation(client.partialObject(info.handle, 0, HEAD_BYTES))
            kind == MediaKind.RAW && info.extension == "raf" -> {
                val location = RafHeader.embeddedJpeg(client.partialObject(info.handle, 0, RafHeader.SIZE))
                location?.let { (offset, _) ->
                    JpegExif.readOrientation(client.partialObject(info.handle, offset, HEAD_BYTES))
                }
            }
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        /** EXIF の IFD0 が収まる十分な長さ */
        const val HEAD_BYTES = 16 * 1024
    }
}

/** JPEG の APP1 (Exif) から Orientation を読む最小限のパーサ。 */
object JpegExif {
    private const val TAG_ORIENTATION = 0x0112

    fun readOrientation(bytes: ByteArray): Int? {
        if (bytes.size < 4 || bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) return null
        var i = 2
        while (i + 4 <= bytes.size) {
            if (bytes.u8(i) != 0xFF) return null
            val marker = bytes.u8(i + 1)
            when {
                marker == 0xFF -> { i++; continue } // fill byte
                marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01 -> { i += 2; continue }
                marker == 0xDA || marker == 0xD9 -> return null // 画像データに入ったら終わり
            }
            val length = bytes.u16be(i + 2)
            if (length < 2) return null
            if (marker == 0xE1 && isExifHeader(bytes, i + 4)) {
                return readTiffOrientation(bytes, i + 10, minOf(bytes.size, i + 2 + length))
            }
            i += 2 + length
        }
        return null
    }

    private fun isExifHeader(bytes: ByteArray, at: Int): Boolean =
        at + 6 <= bytes.size &&
            bytes[at] == 'E'.code.toByte() && bytes[at + 1] == 'x'.code.toByte() &&
            bytes[at + 2] == 'i'.code.toByte() && bytes[at + 3] == 'f'.code.toByte() &&
            bytes[at + 4] == 0.toByte() && bytes[at + 5] == 0.toByte()

    private fun readTiffOrientation(bytes: ByteArray, tiff: Int, end: Int): Int? {
        if (tiff + 8 > end) return null
        val little = when {
            bytes.u8(tiff) == 'I'.code && bytes.u8(tiff + 1) == 'I'.code -> true
            bytes.u8(tiff) == 'M'.code && bytes.u8(tiff + 1) == 'M'.code -> false
            else -> return null
        }
        fun u16(at: Int) = if (little) bytes.u16le(at) else bytes.u16be(at)
        fun u32(at: Int) = if (little) bytes.u32le(at) else bytes.u32be(at)

        if (u16(tiff + 2) != 42) return null
        val ifd0 = tiff + u32(tiff + 4).toInt()
        if (ifd0 < tiff || ifd0 + 2 > end) return null
        val count = u16(ifd0)
        for (n in 0 until count) {
            val entry = ifd0 + 2 + n * 12
            if (entry + 12 > end) return null
            if (u16(entry) == TAG_ORIENTATION) {
                val value = u16(entry + 8)
                return value.takeIf { it in 1..8 }
            }
        }
        return null
    }
}

/** RAF ファイルのヘッダ。 */
object RafHeader {
    /** 埋め込み JPEG の位置までを含むヘッダ長 */
    const val SIZE = 0x5C
    private const val MAGIC = "FUJIFILMCCD-RAW"
    private const val JPEG_OFFSET = 0x54
    private const val JPEG_LENGTH = 0x58

    /** @return 埋め込み JPEG の (オフセット, 長さ)。RAF でなければ null */
    fun embeddedJpeg(header: ByteArray): Pair<Long, Long>? {
        if (header.size < SIZE) return null
        if (String(header, 0, MAGIC.length, Charsets.ISO_8859_1) != MAGIC) return null
        val offset = header.u32be(JPEG_OFFSET)
        val length = header.u32be(JPEG_LENGTH)
        return if (offset > 0 && length > 0) offset to length else null
    }
}

/** 切り出し範囲（ピクセル）。 */
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * サムネイルの黒帯（レターボックス）を除いた範囲を返す。
 *
 * X100VI の GetThumb は 160×120（4:3）固定で、3:2 の写真では上下に約 7〜8 px の黒帯が入る。
 * 帯の境界は JPEG のにじみがあるので、計算上の帯より [inset] px 余分に削る。
 *
 * @return 切り出し不要なら null
 */
fun letterboxContent(thumbWidth: Int, thumbHeight: Int, imageWidth: Long, imageHeight: Long, inset: Int = 2): CropRect? {
    if (thumbWidth <= 0 || thumbHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
    val thumbAspect = thumbWidth.toDouble() / thumbHeight
    val imageAspect = imageWidth.toDouble() / imageHeight
    if (kotlin.math.abs(thumbAspect - imageAspect) < 0.02) return null
    return if (imageAspect > thumbAspect) {
        val margin = ceil((thumbHeight - thumbWidth / imageAspect) / 2).toInt() + inset
        if (margin * 2 >= thumbHeight) null else CropRect(0, margin, thumbWidth, thumbHeight - margin * 2)
    } else {
        val margin = ceil((thumbWidth - thumbHeight * imageAspect) / 2).toInt() + inset
        if (margin * 2 >= thumbWidth) null else CropRect(margin, 0, thumbWidth - margin * 2, thumbHeight)
    }
}

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
private fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
private fun ByteArray.u32be(at: Int): Long = (u16be(at).toLong() shl 16) or u16be(at + 2).toLong()
private fun ByteArray.u32le(at: Int): Long = u16le(at).toLong() or (u16le(at + 2).toLong() shl 16)
