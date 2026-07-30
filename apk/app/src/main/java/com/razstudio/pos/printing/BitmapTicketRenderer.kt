package com.razstudio.pos.printing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Renders a kitchen slip / receipt — expressed in DantSu's line markup — to a 1-bit-friendly
 * monochrome [Bitmap] sized to the printer head width. This is the multilingual fallback: thermal
 * ESC/POS text only prints the printer's built-in code pages (Latin, sometimes GBK/Big5), so
 * Chinese/Tamil/Thai come out as blanks or garbage. Rendering the text with Android's font stack
 * (which substitutes Noto CJK/Tamil/Thai for missing glyphs) and printing the result as a raster
 * image makes any script print correctly, at the cost of speed and paper.
 *
 * Supported markup (the subset the documents actually emit):
 * - Leading `[C]` / `[L]` / `[R]` → centre / left / right alignment (default left).
 * - `<b>…</b>` → bold.
 * - `<font size='tall'>…</font>` (and `big`) → larger line.
 * - Any other tag is stripped. A line that is empty after stripping renders as vertical space.
 *
 * A monospace face is used so the documents' space-padded columns (item … price) stay aligned;
 * the base text size is chosen so `charWidth` monospace characters span the head width.
 */
object BitmapTicketRenderer {

    private data class Line(val text: String, val align: Paint.Align, val bold: Boolean, val scale: Float)

    /**
     * @param markup the DantSu-formatted payload
     * @param dotWidth printer head width in dots (384 for 58mm, 576 for 80mm)
     * @param charWidth characters per line for this paper (32 for 58mm, 48 for 80mm)
     */
    fun render(markup: String, dotWidth: Int, charWidth: Int): Bitmap {
        val lines = markup.split("\n").map { parseLine(it) }

        // Base text size so `charWidth` monospace glyphs fill the head width.
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 100f
        }
        val advPer100 = probe.measureText("M") // advance at textSize=100
        val baseTextSize = (dotWidth / (charWidth * (advPer100 / 100f))).coerceIn(18f, 48f)

        val padX = 4f
        val padY = 8f
        val lineGap = baseTextSize * 0.35f

        // First pass: measure total height.
        var totalHeight = padY * 2
        for (line in lines) {
            val ts = baseTextSize * line.scale
            totalHeight += ts + lineGap
        }

        val bitmap = Bitmap.createBitmap(dotWidth, totalHeight.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        var y = padY
        for (line in lines) {
            val ts = baseTextSize * line.scale
            paint.textSize = ts
            paint.typeface = if (line.bold) BOLD_MONO else Typeface.MONOSPACE
            paint.textAlign = line.align
            y += ts // advance to baseline
            if (line.text.isNotEmpty()) {
                val x = when (line.align) {
                    Paint.Align.CENTER -> dotWidth / 2f
                    Paint.Align.RIGHT -> dotWidth - padX
                    else -> padX
                }
                canvas.drawText(line.text, x, y, paint)
            }
            y += lineGap
        }
        return bitmap
    }

    private val BOLD_MONO: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private fun parseLine(raw: String): Line {
        var s = raw
        var align = Paint.Align.LEFT
        when {
            s.startsWith("[C]") -> { align = Paint.Align.CENTER; s = s.removePrefix("[C]") }
            s.startsWith("[R]") -> { align = Paint.Align.RIGHT; s = s.removePrefix("[R]") }
            s.startsWith("[L]") -> { s = s.removePrefix("[L]") }
        }
        val bold = s.contains("<b>")
        // 'tall' / 'big' font tags render a larger line; everything else is normal.
        val scale = if (Regex("<font[^>]*size='(tall|big)'", RegexOption.IGNORE_CASE).containsMatchIn(s)) 1.5f else 1f
        // Strip any remaining tags (bold, font, img, etc.).
        s = s.replace(Regex("<[^>]*>"), "").trim()
        return Line(s, align, bold, scale)
    }

    /** Code points that a typical thermal printer cannot render as text (needs the bitmap path). */
    fun needsBitmap(text: String): Boolean = text.any { ch ->
        val c = ch.code
        (c in 0x0E00..0x0E7F) ||   // Thai
        (c in 0x0B80..0x0BFF) ||   // Tamil
        (c in 0x4E00..0x9FFF) ||   // CJK Unified Ideographs
        (c in 0x3400..0x4DBF) ||   // CJK Extension A
        (c in 0xF900..0xFAFF)      // CJK Compatibility Ideographs
    }
}
