package io.github.kaitosiba.fujiptp.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import io.github.kaitosiba.fujiptp.camera.letterboxContent
import java.io.ByteArrayOutputStream

/** GetThumb のサムネイルから黒帯を除き、EXIF Orientation に合わせて回転する。 */
object ThumbnailNormalizer {

    /**
     * @param imageWidth 本体画像の幅（ObjectInfo、センサーの向き）
     * @param orientation EXIF Orientation（1〜8）。不明なら null
     * @return 加工が不要なら元のバイト列
     */
    fun normalize(bytes: ByteArray, imageWidth: Long, imageHeight: Long, orientation: Int?): ByteArray {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val crop = letterboxContent(source.width, source.height, imageWidth, imageHeight)
        val matrix = orientationMatrix(orientation)
        if (crop == null && matrix == null) return bytes

        val result = Bitmap.createBitmap(
            source,
            crop?.left ?: 0,
            crop?.top ?: 0,
            crop?.width ?: source.width,
            crop?.height ?: source.height,
            matrix ?: Matrix(),
            true,
        )
        return ByteArrayOutputStream().use { out ->
            result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private fun orientationMatrix(orientation: Int?): Matrix? = when (orientation) {
        2 -> Matrix().apply { setScale(-1f, 1f) }
        3 -> Matrix().apply { setRotate(180f) }
        4 -> Matrix().apply { setRotate(180f); postScale(-1f, 1f) }
        5 -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
        6 -> Matrix().apply { setRotate(90f) }
        7 -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
        8 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }

    private const val JPEG_QUALITY = 92
}
