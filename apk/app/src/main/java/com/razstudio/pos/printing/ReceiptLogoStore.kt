package com.razstudio.pos.printing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import java.io.File
import kotlin.math.roundToInt

/**
 * The receipt logo: a device-local image, and the reduction that turns it into something a
 * thermal head can actually print.
 *
 * ## Why this is separate from the café's branding logo
 *
 * The branding logo ([com.razstudio.pos.ui.util.LogoPipeline]) is a *web* asset. It is uploaded to
 * the backend, shown on the customer ordering page, and shared by every device the café owns. It is
 * chosen to look good on a phone screen — which for a real café usually means a full-colour
 * wordmark on a dark background.
 *
 * A thermal head has none of that. It has one colour, it burns dots onto paper, and it cannot
 * sustain large solid-black areas: a dark-background logo comes out as evenly-spaced black bars
 * with white gaps between them, because the head throttles between bands. That is not something
 * scaling the same image differently can fix — the image is simply wrong for the device. The
 * branding pipeline also centre-crops to a square, which quietly amputates both ends of a wide
 * wordmark; a receipt logo must keep its aspect ratio.
 *
 * So this is its own upload, stored on this device only, alongside the other things that describe
 * one physical terminal (paper width, drawer, cut).
 *
 * ## Why only the source is stored
 *
 * The file on disk is the picked image, flattened onto white and bounded — not the 1-bit result.
 * The print-ready bitmap is derived by [prepare] at the moment it is needed, because it depends on
 * the printer's dot width, and the printer can be changed or reconfigured long after the logo was
 * uploaded. Deriving it each time means the stored logo can never go stale against the hardware,
 * and the Settings preview can render at exactly the width the real printer will use.
 *
 * PNG, not JPEG: JPEG has no alpha channel and Android's encoder writes transparent pixels as
 * **black**, which is one of the two ways a perfectly good logo turns into a solid black slab.
 */
object ReceiptLogoStore {

    private const val SOURCE_FILENAME = "receipt_logo_src.png"

    /** Longest edge kept for the stored source. Plenty for any head width; bounds the file. */
    private const val MAX_SOURCE_PX = 1024

    /**
     * Tallest logo we will print, in printer dots (~30mm at 203dpi).
     *
     * A receipt logo is a letterhead, not an illustration: past this it pushes the order itself
     * off the top of what the customer sees, and every extra millimetre is paper the café pays
     * for on every single sale.
     */
    const val MAX_HEIGHT_DOTS = 240

    /** True when this device has its own receipt logo, without paying to decode it. */
    fun exists(context: Context): Boolean = File(context.filesDir, SOURCE_FILENAME).exists()

    /** The stored source image, or null when this device has none and the caller should fall back. */
    fun loadSource(context: Context): Bitmap? = try {
        val file = File(context.filesDir, SOURCE_FILENAME)
        if (file.exists()) {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The logo this device will actually print at [dotWidth], already reduced to 1-bit.
     *
     * Resolution order: this device's own receipt logo, then the café's branding logo, then the
     * bundled default — so a café that never opens printer settings still gets a header.
     * [invertWhenCustom] applies only to the first; nobody chose an invert for the fallbacks, so
     * those are decided by [isDarkDominant], which is what stops a branding wordmark drawn
     * light-on-dark from printing as a slab of ink on a café that never touched any of this.
     *
     * One implementation, called by both the print path and the Settings preview, so what the
     * operator is shown cannot drift from what the paper does.
     */
    fun effectiveLogo(context: Context, dotWidth: Int, invertWhenCustom: Boolean): Bitmap? = try {
        val custom = loadSource(context)
        if (custom != null) {
            prepare(custom, dotWidth, invertWhenCustom).also { custom.recycle() }
        } else {
            val fallback = com.razstudio.pos.ui.util.LogoPipeline.loadJpegFromInternal(context)
                ?: BitmapFactory.decodeResource(
                    context.resources,
                    com.razstudio.pos.R.raw.qr_default_logo,
                )
            fallback?.let { bmp ->
                prepare(bmp, dotWidth, isDarkDominant(bmp)).also { bmp.recycle() }
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Forget it, so receipts fall back to the branding logo / bundled default. */
    fun clear(context: Context) {
        try {
            File(context.filesDir, SOURCE_FILENAME).delete()
        } catch (e: Exception) {
            // Non-fatal: worst case the old logo keeps printing until it is replaced.
        }
    }

    /**
     * Decode the picked image, flatten it onto white, bound it, and store it as the source.
     * Returns the stored bitmap, or null when the image cannot be read at all.
     */
    fun saveSourceFromUri(context: Context, uri: Uri): Bitmap? = try {
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        }
        if (decoded == null) {
            null
        } else {
            val bounded = boundLongestEdge(flattenOntoWhite(decoded), MAX_SOURCE_PX)
            decoded.recycle()
            File(context.filesDir, SOURCE_FILENAME).outputStream().use { out ->
                bounded.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bounded
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Is [source] mostly dark? Used to default the "invert" switch for the common case of a
     * wordmark drawn light-on-black, which is the single worst thing to hand a thermal head, and
     * to decide automatically for the fallback logos nobody explicitly configured.
     *
     * Sampled rather than exhaustive — this picks a default the operator immediately sees the
     * result of, not a value anything depends on being exact.
     */
    fun isDarkDominant(source: Bitmap): Boolean {
        val stepX = (source.width / 32).coerceAtLeast(1)
        val stepY = (source.height / 32).coerceAtLeast(1)
        var total = 0L
        var count = 0
        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val p = source.getPixel(x, y)
                // Transparent pixels are not "dark" — they become paper white once flattened, and
                // counting them as black is what would flip a perfectly good transparent-PNG logo.
                if (Color.alpha(p) >= 128) {
                    total += luminanceOf(p).toLong()
                    count++
                }
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false
        return total / count < 110
    }

    /**
     * Reduce [source] to something a thermal head can print: opaque, no wider than [dotWidth], no
     * taller than [MAX_HEIGHT_DOTS], and pure black-and-white.
     *
     * The steps, and why each one is here:
     *
     * 1. **Flatten onto white.** A PNG's transparent pixels carry colour 0x00000000 — fully
     *    transparent *black*. Any encoder that reads RGB and ignores alpha turns a transparent
     *    background into a solid black one. Compositing onto white first is what makes a logo with
     *    a cut-out background print as a logo rather than as a filled rectangle.
     *
     * 2. **Fit, width rounded down to a multiple of 8.** ESC * bit-image mode — which this app
     *    defaults to, because cheap 58mm units render nothing at all in GS v 0 raster mode — packs
     *    8 dots per byte along one axis. A width that is not a whole number of bytes leaves the
     *    encoder to pad the remainder, and a padding disagreement shifts every byte after the first
     *    on each row, which shears the image. Rounding here means there is never a remainder.
     *    Height is bounded too, and the width recomputed from it, so a tall logo shrinks rather
     *    than being cropped — nothing here ever crops.
     *
     * 3. **Optional invert.** See [isDarkDominant]. Inverting a light-on-dark mark turns a page of
     *    ink into a few strokes of ink: it prints faster, it does not band, and it is legible.
     *
     * 4. **Floyd–Steinberg dither to pure black/white.** Thermal printing is 1-bit whatever we do;
     *    doing it here — with error diffusion, rather than leaving a naive per-pixel threshold to
     *    do it downstream — is what keeps photographic detail and anti-aliased type from collapsing
     *    into blocks. It also leaves the downstream encoder's own threshold nothing to get wrong,
     *    since every pixel it receives is already exactly #000 or #FFF.
     */
    fun prepare(source: Bitmap, dotWidth: Int, invert: Boolean): Bitmap {
        // 1. Flatten any alpha onto white.
        val flat = flattenOntoWhite(source)

        // 2. Fit within the head width and the height cap, width a whole number of bytes.
        var targetW = minOf(flat.width, dotWidth)
        var targetH = (flat.height * targetW.toFloat() / flat.width).roundToInt().coerceAtLeast(1)
        if (targetH > MAX_HEIGHT_DOTS) {
            targetH = MAX_HEIGHT_DOTS
            targetW = (flat.width * targetH.toFloat() / flat.height).roundToInt().coerceAtLeast(1)
        }
        targetW = (targetW / 8 * 8).coerceAtLeast(8)

        val scaled = if (targetW == flat.width && targetH == flat.height) {
            flat
        } else {
            Bitmap.createScaledBitmap(flat, targetW, targetH, true).also {
                if (it !== flat) flat.recycle()
            }
        }

        // 3 + 4. Luminance (optionally inverted), then error-diffused down to 1-bit.
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val lum = luminanceOf(pixels[i]).toFloat()
            gray[i] = if (invert) 255f - lum else lum
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val old = gray[idx]
                val new = if (old > 128f) 255f else 0f
                gray[idx] = new
                val err = old - new
                if (x + 1 < width) gray[idx + 1] += err * 7f / 16f
                if (y + 1 < height) {
                    if (x - 1 >= 0) gray[(y + 1) * width + (x - 1)] += err * 3f / 16f
                    gray[(y + 1) * width + x] += err * 5f / 16f
                    if (x + 1 < width) gray[(y + 1) * width + (x + 1)] += err * 1f / 16f
                }
            }
        }

        for (i in pixels.indices) {
            pixels[i] = if (gray[i] < 128f) Color.BLACK else Color.WHITE
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()
        return out
    }

    /** Composite onto white so no transparent pixel can later be read as black. */
    private fun flattenOntoWhite(source: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        return flat
    }

    /** Downscale so the longest edge is at most [maxPx], preserving aspect. Never upscales. */
    private fun boundLongestEdge(source: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxPx) return source
        val scale = maxPx.toFloat() / longest
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true).also {
            if (it !== source) source.recycle()
        }
    }

    /** Rec. 601 luma, the weighting that matches how a thermal dot reads as "dark". */
    private fun luminanceOf(pixel: Int): Int =
        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
}
