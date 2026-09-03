package com.boostt1d.android.food

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photo sizing for the two places a meal image goes: the upload, which the proxy's body limit
 * caps, and the row thumbnail, which lives in the database. Ported from the iOS
 * UIImage+Thumbnail helpers and APIService's upload sizing.
 */
object FoodImage {

    /** Decodes a picked or captured photo with its EXIF orientation applied. */
    fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    fun decode(bytes: ByteArray): Bitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    /** Longest edge to at most [maxEdge] pixels. */
    fun downscaled(image: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(image.width, image.height)
        if (longest <= maxEdge || longest <= 0) return image
        val factor = maxEdge.toDouble() / longest
        return Bitmap.createScaledBitmap(image, (image.width * factor).roundToInt().coerceAtLeast(1), (image.height * factor).roundToInt().coerceAtLeast(1), true)
    }

    private fun jpeg(image: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /**
     * JPEG sized for the food-analysis POST: base64 plus JSON must stay under strict server
     * body limits, so the image shrinks until it fits 320 KB.
     */
    fun jpegForUpload(image: Bitmap): ByteArray? {
        val maxPayloadBytes = 320_000
        var maxEdge = 1024
        var img = downscaled(image, maxEdge)
        val qualities = listOf(72, 58, 45, 35, 28)
        repeat(6) {
            for (q in qualities) {
                val data = jpeg(img, q)
                if (data.size <= maxPayloadBytes) return data
            }
            maxEdge = max(384, maxEdge * 3 / 4)
            img = downscaled(image, maxEdge)
        }
        return jpeg(img, 22)
    }

    /** The row thumbnail: 256 px, under 80 KB. */
    fun thumbnailJpeg(image: Bitmap): ByteArray? {
        val maxBytes = 80_000
        var img = downscaled(image, 256)
        for (q in listOf(65, 50, 38, 28)) {
            val data = jpeg(img, q)
            if (data.size <= maxBytes) return data
        }
        img = downscaled(image, 192)
        return jpeg(img, 35)
    }
}
