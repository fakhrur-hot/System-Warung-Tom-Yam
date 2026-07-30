package com.razstudio.pos.ui.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.razstudio.pos.data.local.Table
import java.io.File
import java.io.FileOutputStream

/**
 * Generates a print-shop-ready PDF of QR code cards for table ordering.
 *
 * Layout: A4 portrait pages with a 2×2 grid of A6 portrait cards.
 * Each card contains: optional logo, café name, QR code (60×60mm), and table label.
 * Hairline cut guides separate the cards.
 */
object QrPdfGenerator {

    // A4 portrait dimensions in points (72 dpi)
    private const val A4_WIDTH = 595.28f
    private const val A4_HEIGHT = 841.89f

    // A6 card dimensions (half of A4 in each direction)
    private const val CARD_WIDTH = A4_WIDTH / 2f    // 297.64 pt
    private const val CARD_HEIGHT = A4_HEIGHT / 2f  // 420.945 pt

    // QR code size: 60×60mm = ~170 pt
    private const val QR_SIZE_PT = 170f

    // Logo max size (~46mm) — a bit larger since in logo mode it replaces the café-name text.
    private const val LOGO_MAX_PT = 130f

    // Cards per page
    private const val CARDS_PER_PAGE = 4

    // Fixed dev-company footer printed on every card. Not configurable, not surfaced in the UI.
    private const val FOOTER_LINE1 = "Zero-Commitment POS by RAZStudio"
    private const val FOOTER_LINE2 = "+60 11-3260 5406"

    /**
     * Result of PDF generation.
     */
    data class PdfResult(
        val file: File?,
        val uri: Uri?,
        val pageCount: Int,
        val tableCount: Int
    )

    /**
     * Generate a multi-page QR PDF for the given tables.
     *
     * @param context Android context
     * @param tables List of tables to generate QR cards for
     * @param cafeName Café name to display on each card
     * @param logoBitmap Optional logo bitmap (will be scaled to ≤ 40×40mm)
     * @param baseUrl Base URL for QR codes (e.g., "https://warungtomyam.pages.dev")
     * @return [PdfResult] with file and URI info, or null values on failure
     */
    fun generatePdf(
        context: Context,
        tables: List<Table>,
        cafeName: String,          // header text — drawn only when non-blank (logo mode passes "")
        logoBitmap: Bitmap?,       // header logo — drawn when non-null (text mode passes null)
        baseUrl: String,
        tokenMap: Map<String, String> = emptyMap(),
        fileNameBase: String = cafeName.ifBlank { "QR" }
    ): PdfResult {
        if (tables.isEmpty()) {
            return PdfResult(null, null, 0, 0)
        }

        val pageCount = (tables.size + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE
        val document = PdfDocument()

        for (pageIdx in 0 until pageCount) {
            val pageInfo = PdfDocument.PageInfo.Builder(
                A4_WIDTH.toInt(),
                A4_HEIGHT.toInt(),
                pageIdx + 1
            ).create()

            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            // Draw up to 4 cards on this page
            for (cellIdx in 0 until CARDS_PER_PAGE) {
                val tableIdx = pageIdx * CARDS_PER_PAGE + cellIdx
                if (tableIdx >= tables.size) break

                val table = tables[tableIdx]
                val col = cellIdx % 2
                val row = cellIdx / 2
                val offsetX = col * CARD_WIDTH
                val offsetY = row * CARD_HEIGHT

                drawCard(
                    canvas = canvas,
                    table = table,
                    cafeName = cafeName,
                    logoBitmap = logoBitmap,
                    baseUrl = baseUrl,
                    qrToken = tokenMap[table.id],
                    offsetX = offsetX,
                    offsetY = offsetY
                )
            }

            // Draw hairline cut guides
            drawCutGuides(canvas)

            document.finishPage(page)
        }

        // Save to file
        val result = saveAndGetUri(context, document, fileNameBase)
        document.close()

        return PdfResult(
            file = result.first,
            uri = result.second,
            pageCount = pageCount,
            tableCount = tables.size
        )
    }

    /**
     * Draw a single QR card within the given cell offset.
     */
    private fun drawCard(
        canvas: Canvas,
        table: Table,
        cafeName: String,
        logoBitmap: Bitmap?,
        baseUrl: String,
        qrToken: String?,
        offsetX: Float,
        offsetY: Float
    ) {
        val centerX = offsetX + CARD_WIDTH / 2f

        // Layout from top of card:
        // - Top margin: ~40pt
        // - Logo (optional): up to 113pt
        // - Gap: 12pt
        // - Café name: ~20pt
        // - Gap: 16pt
        // - QR code: 170pt
        // - Gap: 16pt
        // - Table label: ~30pt

        var currentY = offsetY + 40f

        // 1. Logo (optional, centered, ≤ 113×113 pt)
        if (logoBitmap != null) {
            val scale = minOf(
                LOGO_MAX_PT / logoBitmap.width,
                LOGO_MAX_PT / logoBitmap.height,
                1f
            )
            val logoW = logoBitmap.width * scale
            val logoH = logoBitmap.height * scale
            val logoLeft = centerX - logoW / 2f

            val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                logoBitmap,
                null,
                android.graphics.RectF(logoLeft, currentY, logoLeft + logoW, currentY + logoH),
                logoPaint
            )
            currentY += logoH + 12f
        }

        // 2. Café name (bold, ~16pt) — drawn only in text mode (blank in logo mode).
        if (cafeName.isNotBlank()) {
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            currentY += namePaint.textSize
            canvas.drawText(cafeName, centerX, currentY, namePaint)
            currentY += 16f
        }

        // 3. QR code (170×170 pt, EC-H) with the table number embedded in the centre.
        // Prefer the opaque QR token so the printed link isn't a guessable table id.
        val qrUrl = "${baseUrl.trimEnd('/')}/order?table=${qrToken ?: table.id}"
        val qrBitmap = generateQrBitmap(qrUrl, QR_SIZE_PT.toInt())
        if (qrBitmap != null) {
            val qrLeft = centerX - QR_SIZE_PT / 2f
            val qrTop = currentY
            val qrPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                qrBitmap,
                null,
                android.graphics.RectF(qrLeft, qrTop, qrLeft + QR_SIZE_PT, qrTop + QR_SIZE_PT),
                qrPaint
            )
            qrBitmap.recycle()
            // Centre badge: a white square (its own quiet zone) with the table number in it.
            // EC level H (~30% recovery) keeps the code scannable behind this small overlay.
            drawCentreNumber(canvas, table.label, centerX, qrTop + QR_SIZE_PT / 2f)
            currentY = qrTop + QR_SIZE_PT + 16f
        }

        // 4. (Table number is now inside the QR — no separate label below.)

        // 5. Fixed dev-company footer, anchored to the bottom of each card (two centered lines).
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 8f
            textAlign = Paint.Align.CENTER
        }
        val cardBottom = offsetY + CARD_HEIGHT
        canvas.drawText(FOOTER_LINE1, centerX, cardBottom - 22f, footerPaint)
        canvas.drawText(FOOTER_LINE2, centerX, cardBottom - 12f, footerPaint)
    }

    /**
     * Draw the table number centred inside the QR code, on its own white "quiet-zone" badge.
     * The badge is ~30% of the QR side (≈9% of area), comfortably within EC-level-H's ~30%
     * error-recovery budget, so the code still scans. Text auto-shrinks to fit longer labels.
     */
    private fun drawCentreNumber(canvas: Canvas, label: String, cx: Float, cy: Float) {
        val badge = QR_SIZE_PT * 0.30f
        val half = badge / 2f
        val radius = badge * 0.15f
        val rect = android.graphics.RectF(cx - half, cy - half, cx + half, cy + half)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, border)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = badge * 0.6f
        }
        val maxW = badge * 0.8f
        while (textPaint.measureText(label) > maxW && textPaint.textSize > 6f) {
            textPaint.textSize -= 1f
        }
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, baseline, textPaint)
    }

    /**
     * Draw hairline dashed cut guides at the horizontal and vertical midpoints.
     */
    private fun drawCutGuides(canvas: Canvas) {
        val guidePaint = Paint().apply {
            color = Color.GRAY
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }

        // Vertical midline
        canvas.drawLine(
            A4_WIDTH / 2f, 0f,
            A4_WIDTH / 2f, A4_HEIGHT,
            guidePaint
        )

        // Horizontal midline
        canvas.drawLine(
            0f, A4_HEIGHT / 2f,
            A4_WIDTH, A4_HEIGHT / 2f,
            guidePaint
        )
    }

    /**
     * Generate a QR code bitmap using ZXing.
     *
     * @param content URL to encode
     * @param sizePx Output bitmap size in pixels
     * @return Bitmap or null on failure
     */
    private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 1
            )
            val bitMatrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints
            )

            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save the PDF document and return a file + content URI for sharing.
     * Uses MediaStore on API 29+, FileProvider fallback on older versions.
     */
    private fun saveAndGetUri(
        context: Context,
        document: PdfDocument,
        cafeName: String
    ): Pair<File?, Uri?> {
        val fileName = "QR_Cards_${cafeName.replace(" ", "_")}_${System.currentTimeMillis()}.pdf"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, document, fileName)
        } else {
            saveViaFileProvider(context, document, fileName)
        }
    }

    /**
     * API 29+: Save PDF to Downloads via MediaStore.
     */
    // Only called from the Build.VERSION.SDK_INT >= Q branch below — @RequiresApi lets lint
    // correlate that guard with this function instead of flagging EXTERNAL_CONTENT_URI as
    // unreachable-on-minSdk-26 (a false positive; it can't trace the guard across the call site).
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        document: PdfDocument,
        fileName: String
    ): Pair<File?, Uri?> {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null to null

            resolver.openOutputStream(uri)?.use { outputStream ->
                document.writeTo(outputStream)
            }

            // We don't have a direct File reference with MediaStore, but the URI is shareable
            null to uri
        } catch (e: Exception) {
            null to null
        }
    }

    /**
     * API 26-28: Save to app's external files directory and provide via FileProvider.
     */
    private fun saveViaFileProvider(
        context: Context,
        document: PdfDocument,
        fileName: String
    ): Pair<File?, Uri?> {
        return try {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "qr_cards")
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, fileName)
            FileOutputStream(file).use { fos ->
                document.writeTo(fos)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            file to uri
        } catch (e: Exception) {
            null to null
        }
    }

    /**
     * Create a share intent for the generated PDF.
     *
     * @param context Android context
     * @param uri Content URI of the PDF
     * @return Chooser intent ready to start
     */
    fun createShareIntent(context: Context, uri: Uri): Intent {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(shareIntent, "Share QR Cards PDF")
    }
}
