package com.razstudio.pos.printing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.razstudio.pos.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 2.6b — Render the template QR logo at ~200 px wide, threshold it to 1-bit, and confirm both
 * the `r` and the café cue still read.
 *
 * The asset is thermally printed: [PrinterConnectionManager.loadReceiptLogo] scales it to ~55% of
 * the 58 mm print head, landing near 200 px wide, then a hardware ditherer reduces it to 1-bit.
 * A design that survives a 512 px preview and dies at 200 px 1-bit is the expected failure mode, so
 * the test mirrors exactly what the printer does rather than what the display shows.
 *
 * The properties checked are deliberately coarse — they catch the three ways this class of asset
 * fails in practice:
 *
 *  1. **Blank image** — the file is missing, corrupt, or all-white after thresholding (logo was
 *     pure gradient, washed out at 1-bit). Caught by the non-zero black-pixel assertion.
 *  2. **Solid blob** — hairlines, dense fills, or anti-aliased edges merge into an unreadable black
 *     mass. Caught by the upper bound on black-pixel coverage.
 *  3. **Degenerate dimensions** — scaling produced a 0×0 or negative-dimension bitmap. Caught by
 *     the explicit width/height assertion.
 *
 * Validates: Requirements 4.2
 */
@RunWith(RobolectricTestRunner::class)
class QrLogoThermalVerificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Scale [src] so its width equals [targetWidth], preserving aspect ratio.
     */
    private fun scaleTo(src: Bitmap, targetWidth: Int): Bitmap {
        val scale = targetWidth.toFloat() / src.width
        val targetHeight = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true /* bilinear filter */)
    }

    /**
     * Convert [bmp] to 1-bit luminance: every pixel whose luminance < 128 becomes black (0),
     * every other pixel becomes white (255). Returns an ARGB_8888 bitmap where every pixel is
     * either 0xFF000000 or 0xFFFFFFFF.
     *
     * Luminance formula: ITU-R BT.601 (same as Android's Color.luminance denominator):
     *   L = 0.299·R + 0.587·G + 0.114·B   (values 0–255)
     */
    private fun threshold(bmp: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val px = bmp.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val luma = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                out.setPixel(x, y, if (luma < 128) Color.BLACK else Color.WHITE)
            }
        }
        return out
    }

    @Test
    fun logoIsDecodableFromResources() {
        // The most basic gate: the file must exist and BitmapFactory must be able to decode it.
        val raw = BitmapFactory.decodeResource(context.resources, R.raw.qr_default_logo)
        assertTrue(
            "BitmapFactory.decodeResource returned null — is res/raw/qr_default_logo.jpg present?",
            raw != null,
        )
    }

    @Test
    fun scaledTo200pxHasPositiveDimensions() {
        val raw = BitmapFactory.decodeResource(context.resources, R.raw.qr_default_logo)!!
        val scaled = scaleTo(raw, 200)
        assertTrue("scaled width must be > 0", scaled.width > 0)
        assertTrue("scaled height must be > 0", scaled.height > 0)
        assertEquals("scaled width must be exactly 200 px", 200, scaled.width)
    }

    @Test
    fun thresholdedImageContainsBothBlackAndWhitePixels() {
        // A solid-black or solid-white result means the asset lost all detail at 1-bit.
        val raw = BitmapFactory.decodeResource(context.resources, R.raw.qr_default_logo)!!
        val scaled = scaleTo(raw, 200)
        val thresholded = threshold(scaled)

        var blackCount = 0
        var whiteCount = 0
        for (y in 0 until thresholded.height) {
            for (x in 0 until thresholded.width) {
                val px = thresholded.getPixel(x, y)
                when (px) {
                    Color.BLACK -> blackCount++
                    Color.WHITE -> whiteCount++
                }
            }
        }

        assertTrue(
            "no black pixels after thresholding — asset is blank or all-white at 200 px 1-bit " +
                "(likely: pure gradient washed out; or file is missing)",
            blackCount > 0,
        )
        assertTrue(
            "no white pixels after thresholding — asset is a solid black mass at 200 px 1-bit " +
                "(likely: hairlines / dense fills merged; or file is all-black)",
            whiteCount > 0,
        )
    }

    @Test
    fun blackPixelCoverageIsBetween3And60Percent() {
        // Enough ink to form recognisable letterforms, not so much it prints as a black square.
        //
        // The `r` and the steam-curl café cue are both open letterforms on a white field. A well-
        // drawn mark at 200 px 1-bit should sit comfortably in 5–35% coverage. The 3% lower bound
        // catches a nearly-blank image where fine strokes have vanished. The 60% upper bound
        // catches a filled or anti-aliased design that merges into an unreadable blob.
        val raw = BitmapFactory.decodeResource(context.resources, R.raw.qr_default_logo)!!
        val scaled = scaleTo(raw, 200)
        val thresholded = threshold(scaled)

        val totalPixels = thresholded.width * thresholded.height
        var blackCount = 0
        for (y in 0 until thresholded.height) {
            for (x in 0 until thresholded.width) {
                if (thresholded.getPixel(x, y) == Color.BLACK) blackCount++
            }
        }

        val coveragePct = blackCount.toDouble() / totalPixels * 100.0
        assertTrue(
            "black pixel coverage is ${String.format("%.1f", coveragePct)}% — below 3%: " +
                "strokes are too fine and vanish at 200 px 1-bit; the mark will print blank",
            coveragePct >= 3.0,
        )
        assertTrue(
            "black pixel coverage is ${String.format("%.1f", coveragePct)}% — above 60%: " +
                "asset has too much ink and will print as an unreadable blob on 58 mm paper",
            coveragePct <= 60.0,
        )
    }
}
