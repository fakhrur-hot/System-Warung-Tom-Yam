package com.warungtomyam.pos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Client-side image prep for menu item thumbnails: center-crop to 5:4, downscale to a
 * fixed small size, and JPEG-encode. Done on-device so only a ~15-30KB thumbnail ever
 * reaches Supabase Storage (Free tier: 1GB storage / 5GB egress) — never the original.
 */
object ImageUtils {
    const val THUMBNAIL_WIDTH = 320
    const val THUMBNAIL_HEIGHT = 256 // 5:4
    private const val JPEG_QUALITY = 80

    /**
     * Reads the image at [uri], center-crops it to 5:4, downscales to
     * [THUMBNAIL_WIDTH]x[THUMBNAIL_HEIGHT], and returns base64-encoded JPEG bytes
     * (no line wraps — safe to embed directly in a JSON string).
     */
    fun prepareThumbnailBase64(context: Context, uri: Uri): String? {
        val bitmap = decodeBitmap(context, uri) ?: return null
        val cropped = centerCropToAspect(bitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
        if (cropped !== bitmap) bitmap.recycle()

        val bytes = ByteArrayOutputStream().use { stream ->
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            stream.toByteArray()
        }
        cropped.recycle()

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decodeBitmap(context: Context, uri: Uri): Bitmap? =
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }

    private fun centerCropToAspect(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val targetRatio = targetWidth.toFloat() / targetHeight
        val sourceRatio = source.width.toFloat() / source.height

        val (cropWidth, cropHeight) = if (sourceRatio > targetRatio) {
            // Source is wider than target — crop the sides.
            (source.height * targetRatio).toInt() to source.height
        } else {
            // Source is taller than target — crop top/bottom.
            source.width to (source.width / targetRatio).toInt()
        }

        val x = (source.width - cropWidth) / 2
        val y = (source.height - cropHeight) / 2
        val cropped = Bitmap.createBitmap(source, x, y, cropWidth, cropHeight)

        return if (cropped.width == targetWidth && cropped.height == targetHeight) {
            cropped
        } else {
            val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
            if (scaled !== cropped) cropped.recycle()
            scaled
        }
    }
}
