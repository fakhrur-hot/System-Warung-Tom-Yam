package com.razstudio.pos.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Logo processing pipeline for café branding.
 *
 * Produces two outputs from a picked image:
 * 1. Full JPEG (≤ 200 KB, square-cropped) — for website/branding display + Base64 upload.
 * 2. 1-bit monochrome bitmap (max 384px width, Floyd–Steinberg dither) — for ESC/POS
 *    receipt header printing. Prevents thermal printer buffer overflows and BT SPP stalls.
 */
object LogoPipeline {

    private const val MAX_JPEG_SIZE_BYTES = 200 * 1024 // 200 KB
    private const val PRINT_MAX_WIDTH = 384            // 58mm printer width in pixels
    private const val MONO_FILENAME = "print_logo.bin"
    private const val JPEG_FILENAME = "custom_logo.jpg"
    private const val QR_LOGO_FILENAME = "qr_card_logo.jpg"

    /**
     * Result of the logo pipeline processing.
     */
    data class LogoResult(
        val jpegBytes: ByteArray,         // Full JPEG ≤ 200 KB
        val jpegBase64: String,           // Base64 NO_WRAP of the JPEG
        val monoBitmapBytes: ByteArray,   // 1-bit monochrome raster (row-major, MSB first)
        val monoWidth: Int,               // Width of monochrome image in pixels
        val monoHeight: Int,              // Height of monochrome image in pixels
        val previewBitmap: Bitmap         // Square preview for UI display
    )

    /**
     * Run the full pipeline on a picked image URI.
     * 1. Decode → center crop to square
     * 2. Compress loop until ≤ 200 KB JPEG
     * 3. Downscale to max 384px width → grayscale → threshold to 1-bit mono
     * 4. Return both outputs
     *
     * @param context Android context for content resolver
     * @param imageUri URI from image picker
     * @return [LogoResult] or null if processing fails
     */
    fun process(context: Context, imageUri: Uri): LogoResult? {
        // 1. Decode bitmap
        val inputStream = context.contentResolver.openInputStream(imageUri) ?: return null
        val decoded = BitmapFactory.decodeStream(inputStream) ?: return null
        inputStream.close()

        // 1b. Flatten transparency onto white before anything else touches the image.
        //
        // JPEG has no alpha channel, and Android's encoder does not composite — it drops alpha and
        // writes the stored RGB. A premultiplied transparent pixel is stored as 0x00000000, so it
        // encodes as pure BLACK. Every logo uploaded with a cut-out background therefore came out
        // of this pipeline as a solid black square: on the customer website, and — via
        // `custom_logo.jpg` — as the receipt header, where a thermal head rendered it as a slab of
        // ink banded by the print head's own duty cycle.
        //
        // Nothing downstream could recover it, because the alpha was already gone on disk. This is
        // the same defect DantSu's own library carries (ESCPOS-ThermalPrinter-Android issue #332,
        // "transparent print is black") one layer further on, and the fix is the same: composite
        // onto white first, once, at the point the alpha still exists.
        val original = flattenOntoWhite(decoded)
        decoded.recycle()

        // 2. Center crop to square
        val square = centerCropSquare(original)
        if (square != original) original.recycle()

        // 3. Compress loop: scale down + JPEG compress until ≤ 200 KB
        val (jpegBytes, finalBitmap) = compressToTarget(square, MAX_JPEG_SIZE_BYTES)
        if (finalBitmap != square) square.recycle()

        // 4. Generate Base64
        val jpegBase64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        // 5. Generate monochrome print bitmap
        val (monoBytes, monoWidth, monoHeight) = generateMonochrome(finalBitmap)

        // 6. Save monochrome to internal storage for later ESC/POS use
        saveMonoToInternal(context, monoBytes)

        return LogoResult(
            jpegBytes = jpegBytes,
            jpegBase64 = jpegBase64,
            monoBitmapBytes = monoBytes,
            monoWidth = monoWidth,
            monoHeight = monoHeight,
            previewBitmap = finalBitmap
        )
    }

    /**
     * Center crop a bitmap to a square.
     */
    private fun centerCropSquare(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        return Bitmap.createBitmap(source, x, y, size, size)
    }

    /**
     * Composite onto white so no transparent pixel can later be read as black. See the note in
     * [process] for why this has to happen before the JPEG step rather than after it.
     */
    private fun flattenOntoWhite(source: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        return flat
    }

    /**
     * Scale down and compress a bitmap until it fits within [maxBytes] as JPEG.
     * Returns the JPEG byte array and the scaled bitmap (for preview).
     */
    private fun compressToTarget(source: Bitmap, maxBytes: Int): Pair<ByteArray, Bitmap> {
        var quality = 85
        var scale = 1.0f
        var current = source

        // Try compressing at current size first
        while (true) {
            val baos = ByteArrayOutputStream()
            current.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val bytes = baos.toByteArray()

            if (bytes.size <= maxBytes) {
                return bytes to current
            }

            // Reduce quality first
            if (quality > 40) {
                quality -= 10
            } else {
                // Scale down by 20%
                scale *= 0.8f
                quality = 80
                val newWidth = (source.width * scale).toInt().coerceAtLeast(64)
                val newHeight = (source.height * scale).toInt().coerceAtLeast(64)
                if (current != source) current.recycle()
                current = Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
            }

            // Safety: don't shrink below 64px
            if (current.width <= 64) {
                val baos2 = ByteArrayOutputStream()
                current.compress(Bitmap.CompressFormat.JPEG, 30, baos2)
                return baos2.toByteArray() to current
            }
        }
    }

    /**
     * Convert a bitmap to 1-bit monochrome using threshold at 128.
     * Downscales to max [PRINT_MAX_WIDTH] pixels wide first.
     * Returns packed bytes (MSB-first, row-major), width, and height.
     */
    private fun generateMonochrome(source: Bitmap): Triple<ByteArray, Int, Int> {
        // Downscale to max 384px width, maintaining aspect ratio
        val scaledWidth: Int
        val scaledHeight: Int
        if (source.width > PRINT_MAX_WIDTH) {
            scaledWidth = PRINT_MAX_WIDTH
            scaledHeight = (source.height.toFloat() / source.width * PRINT_MAX_WIDTH).toInt()
        } else {
            scaledWidth = source.width
            scaledHeight = source.height
        }

        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        // Convert to grayscale
        val grayscale = toGrayscale(scaled)
        if (scaled != source) scaled.recycle()

        // Apply Floyd-Steinberg dithering and threshold to 1-bit
        val pixels = IntArray(grayscale.width * grayscale.height)
        grayscale.getPixels(pixels, 0, grayscale.width, 0, 0, grayscale.width, grayscale.height)

        val width = grayscale.width
        val height = grayscale.height

        // Work with float error diffusion array
        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            gray[i] = r.toFloat() // already grayscale, R=G=B
        }

        // Floyd-Steinberg dither
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val oldPixel = gray[idx]
                val newPixel = if (oldPixel > 128f) 255f else 0f
                gray[idx] = newPixel
                val error = oldPixel - newPixel

                if (x + 1 < width) gray[idx + 1] += error * 7f / 16f
                if (y + 1 < height) {
                    if (x - 1 >= 0) gray[(y + 1) * width + (x - 1)] += error * 3f / 16f
                    gray[(y + 1) * width + x] += error * 5f / 16f
                    if (x + 1 < width) gray[(y + 1) * width + (x + 1)] += error * 1f / 16f
                }
            }
        }

        // Pack into 1-bit bytes (MSB first, 8 pixels per byte)
        // ESC/POS expects: 0 = white (paper), 1 = black (ink)
        val bytesPerRow = (width + 7) / 8
        val monoBytes = ByteArray(bytesPerRow * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val isBlack = gray[idx] < 128f
                if (isBlack) {
                    val byteIdx = y * bytesPerRow + (x / 8)
                    val bitIdx = 7 - (x % 8) // MSB first
                    monoBytes[byteIdx] = (monoBytes[byteIdx].toInt() or (1 shl bitIdx)).toByte()
                }
            }
        }

        grayscale.recycle()

        return Triple(monoBytes, width, height)
    }

    /**
     * Convert a bitmap to grayscale.
     */
    private fun toGrayscale(source: Bitmap): Bitmap {
        val grayscale = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscale)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return grayscale
    }

    /**
     * Save the monochrome raster to internal storage for ESC/POS receipt printing.
     */
    private fun saveMonoToInternal(context: Context, monoBytes: ByteArray) {
        try {
            val file = File(context.filesDir, MONO_FILENAME)
            file.writeBytes(monoBytes)
        } catch (e: Exception) {
            // Non-fatal: print will just skip logo
        }
    }

    /**
     * Load previously saved monochrome raster from internal storage.
     * Returns null if not available.
     */
    fun loadMonoFromInternal(context: Context): ByteArray? {
        return try {
            val file = File(context.filesDir, MONO_FILENAME)
            if (file.exists()) file.readBytes() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Persist the full-quality JPEG so the café's own uploaded logo — not just the bundled
     * placeholder — is what prints on receipts and renders on QR-card PDFs. Called only after the
     * backend confirms the branding save succeeded (matches this screen's staged-edit contract:
     * nothing takes effect until Save), so a picked-but-cancelled logo never lingers here.
     */
    fun saveJpegToInternal(context: Context, jpegBytes: ByteArray) {
        try {
            File(context.filesDir, JPEG_FILENAME).writeBytes(jpegBytes)
        } catch (e: Exception) {
            // Non-fatal: printed/generated output just falls back to the bundled default logo
        }
    }

    /**
     * Load the café's uploaded logo for printing/PDF generation. Returns null if the café hasn't
     * uploaded one yet, so callers fall back to the bundled default (res/raw/qr_default_logo).
     */
    fun loadJpegFromInternal(context: Context): Bitmap? = try {
        val file = File(context.filesDir, JPEG_FILENAME)
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    } catch (e: Exception) {
        null
    }

    /**
     * Persist a logo picked specifically for the printable table-QR cards, distinct from the
     * café-wide branding logo ([saveJpegToInternal]). Generate Table QR previously kept the picked
     * bitmap only in the screen's ViewModel state, so it vanished the moment the admin left the
     * screen or regenerated the PDF a second time — this is what makes that pick stick.
     */
    fun saveQrLogoToInternal(context: Context, jpegBytes: ByteArray) {
        try {
            File(context.filesDir, QR_LOGO_FILENAME).writeBytes(jpegBytes)
        } catch (e: Exception) {
            // Non-fatal: the PDF just falls back to the branding logo / bundled default.
        }
    }

    /** The QR-card-specific logo, if one was ever picked — see [saveQrLogoToInternal]. */
    fun loadQrLogoFromInternal(context: Context): Bitmap? = try {
        val file = File(context.filesDir, QR_LOGO_FILENAME)
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    } catch (e: Exception) {
        null
    }

    /** Drop the QR-card-specific logo so the screen falls back to branding / the bundled default. */
    fun clearQrLogoFromInternal(context: Context) {
        try {
            File(context.filesDir, QR_LOGO_FILENAME).delete()
        } catch (e: Exception) {
            // Non-fatal: worst case the stale file lingers and keeps being used until overwritten.
        }
    }
}
