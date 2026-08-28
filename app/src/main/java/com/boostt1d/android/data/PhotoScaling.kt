package com.boostt1d.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Avatar handling, matching the iOS rule.
 *
 * A picture straight from the gallery is several megabytes and base64 adds a third
 * on top, while nothing draws the avatar above 160dp. iOS caps the long edge at 512px
 * for exactly this reason — DataStore is no happier holding a full-resolution
 * original than UserDefaults was.
 */
object PhotoScaling {

    private const val MAX_DIMENSION = 512
    private const val JPEG_QUALITY = 80

    fun encodeAvatar(context: Context, uri: Uri): String? = runCatching {
        val source = context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null

        val longestEdge = maxOf(source.width, source.height)
        val scaled = if (longestEdge > MAX_DIMENSION) {
            val ratio = MAX_DIMENSION.toFloat() / longestEdge
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }

        val bytes = ByteArrayOutputStream().also {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }.toByteArray()

        if (scaled !== source) scaled.recycle()
        source.recycle()

        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    fun decodeAvatar(base64: String?): Bitmap? {
        if (base64.isNullOrEmpty()) return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}
