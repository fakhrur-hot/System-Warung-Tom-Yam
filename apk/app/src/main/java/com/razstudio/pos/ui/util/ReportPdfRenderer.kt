package com.razstudio.pos.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.razstudio.pos.data.local.ReportData
import com.razstudio.pos.ui.i18n.UiStrings

/**
 * Lays the sales report out as a paginated A4 PDF.
 *
 * ## Why this is not just `drawText` calls any more
 *
 * The previous renderer drew everything onto **one** page and guarded overflow with
 * `if (y > 790f) break` — so a café with more than a screenful of tables silently lost the rest of
 * its own report, and the sections after the truncation point vanished with it. A report that
 * quietly omits data is worse than one that runs long, because nothing on the page says anything is
 * missing.
 *
 * Everything here flows: when a section runs out of room it starts a new page and keeps going.
 *
 * ## Two passes, because the footer has to say "of N"
 *
 * A page cannot be told how many pages follow it until the whole document is laid out. Rather than
 * buffer pages in memory and stamp them afterwards, the layout runs twice: once counting, once
 * drawing with the total known. The layout is deterministic, so the second pass produces exactly
 * the page breaks the first one counted.
 */
object ReportPdfRenderer {

    // A4 at 72dpi, matching the PdfDocument page size used by the caller.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val CONTENT_R = PAGE_W - MARGIN
    private const val FOOTER_Y = PAGE_H - 24f

    /** Lowest baseline a row may occupy before it must move to the next page. */
    private const val BOTTOM_LIMIT = PAGE_H - 48f

    /**
     * Every size is the old value scaled by 0.85.
     *
     * Kept as one factor applied to the original numbers rather than as pre-multiplied constants,
     * so the relationship between the sizes stays visible and a future change to the scale is one
     * edit rather than five.
     */
    private const val FONT_SCALE = 0.85f

    private val cafePaint = Paint().apply {
        color = Color.BLACK; textSize = 22f * FONT_SCALE; typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val titlePaint = Paint().apply {
        color = Color.BLACK; textSize = 18f * FONT_SCALE; typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val headerPaint = Paint().apply {
        color = Color.DKGRAY; textSize = 14f * FONT_SCALE; typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val bodyPaint = Paint().apply {
        color = Color.BLACK; textSize = 11f * FONT_SCALE; typeface = Typeface.DEFAULT
        isAntiAlias = true
    }
    private val thPaint = Paint().apply {
        color = Color.BLACK; textSize = 11f * FONT_SCALE; typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    /**
     * Money, in a monospaced face.
     *
     * Proportional digits make a column of prices ragged — "11.00" is narrower than "88.00" — so a
     * reader scanning for the big number has to read every row instead of seeing the shape of the
     * column. Monospace plus right-alignment makes the decimal points line up, which is the whole
     * point of putting money in a column.
     */
    private val moneyPaint = Paint().apply {
        color = Color.BLACK; textSize = 11f * FONT_SCALE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }
    private val moneyBoldPaint = Paint().apply {
        color = Color.BLACK; textSize = 11f * FONT_SCALE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }
    private val rulePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
    private val footerPaint = Paint().apply {
        color = Color.GRAY; textSize = 9f * FONT_SCALE; typeface = Typeface.DEFAULT
        isAntiAlias = true; textAlign = Paint.Align.CENTER
    }

    /**
     * Render [report] into [document].
     *
     * @param logo the café's own logo, drawn top-left with the name and title set to its right.
     *   Null simply left-aligns the text, so a café without a logo loses nothing but the picture.
     */
    fun render(
        document: PdfDocument,
        report: ReportData,
        cafeName: String,
        logo: Bitmap?,
        strings: UiStrings,
    ) {
        val total = layout(null, report, cafeName, logo, strings, totalPages = 0)
        layout(document, report, cafeName, logo, strings, totalPages = total)
    }

    /**
     * One pass over the whole report.
     *
     * With [document] null nothing is drawn and the return value is just the page count — that is
     * the counting pass. With it non-null the same walk draws, using [totalPages] for the footer.
     */
    private fun layout(
        document: PdfDocument?,
        report: ReportData,
        cafeName: String,
        logo: Bitmap?,
        strings: UiStrings,
        totalPages: Int,
    ): Int {
        val pen = Pen(document, totalPages, strings)
        pen.startPage()

        drawMasthead(pen, report, cafeName, logo, strings)

        // ── Summary ──────────────────────────────────────────────────────────────
        // Average order value is gone: it is the one figure here nobody acts on, and it is
        // recoverable from the two lines above it anyway. What an owner counting the till actually
        // wants is how much of the day arrived as cash versus as QR, so those are promoted out of
        // the old Payment Split section and into the summary — which lets that section disappear
        // entirely rather than repeating itself two inches lower.
        pen.section(strings.summaryTitle)
        pen.tableHeader(strings.colMetric, strings.colValue)
        pen.row(strings.totalOrdersLabel, report.totalOrders.toString())
        pen.money(strings.grossTotalLabel, report.totalRevenue, bold = true)
        pen.money(strings.totalPayByFormat.format(strings.cashLabel), report.paymentSplit.cashTotal)
        pen.money(strings.totalPayByFormat.format(strings.qrLabel), report.paymentSplit.qrTotal)
        // Any acquirer-backed method that ever produced takings still has to appear, or the rows
        // stop adding up to total revenue.
        report.paymentSplit.byMethod
            .filterNot { it.method.equals("CASH", true) || it.method.equals("QR", true) }
            .forEach { pen.money(strings.totalPayByFormat.format(it.method), it.revenue) }
        pen.money(strings.cancelledLabel, report.cancelledSummary.totalValue)
        pen.rule()

        // ── Top items, one table for the whole menu ──────────────────────────────
        // Three per category, merged. Separate tables per category made the reader compare columns
        // across headings to answer "what sold"; one table with the category beside each row keeps
        // that comparison on a single axis.
        val top = report.topNPerCategory
            .flatMap { (category, items) -> items.take(3).map { category to it } }
        if (top.isNotEmpty()) {
            pen.section(strings.topItemsMergedTitle)
            pen.tableHeader3(strings.colCategory, strings.colItem, strings.colQty, strings.colRevenue)
            top.forEach { (category, item) ->
                pen.row4(category, item.name, item.quantity.toString(), item.revenue)
            }
            pen.rule()
        }

        // ── Cancellations ────────────────────────────────────────────────────────
        pen.section(strings.cancelledOrdersTitle)
        pen.tableHeader(strings.colSource, strings.colCount)
        pen.row(strings.adminLabel, report.cancelledSummary.byAdmin.toString())
        pen.row(strings.customerLabel, report.cancelledSummary.byCustomer.toString())
        pen.row(strings.staffLabel, report.cancelledSummary.byStaff.toString())
        pen.row(strings.totalPrefix, report.cancelledSummary.totalCount.toString())
        pen.rule()

        // ── Cash drawer ──────────────────────────────────────────────────────────
        report.drawerOpeningCount?.let {
            pen.section(strings.cashDrawerTitle)
            pen.tableHeader(strings.colMetric, strings.colValue)
            pen.row(strings.drawerOpeningsLabel, it.toString())
            pen.rule()
        }

        // ── Per-table breakdown — deliberately last ──────────────────────────────
        // This is the only section whose length is unbounded: it grows with the floor plan, and a
        // café with sixty tables pushes it over several pages. Putting it last means that overflow
        // costs nothing — no other section gets stranded on a later page behind it, and the reader
        // knows everything else is on page one.
        if (report.perTableBreakdown.isNotEmpty()) {
            pen.section(strings.perTableBreakdownTitle)
            pen.tableHeader3(strings.colTable, "", strings.colOrders, strings.colRevenue)
            report.perTableBreakdown.forEach { tb ->
                pen.row4(tb.tableLabel, "", tb.orderCount.toString(), tb.revenue)
            }
        }

        pen.finish()
        return pen.pageCount
    }

    private fun drawMasthead(
        pen: Pen,
        report: ReportData,
        cafeName: String,
        logo: Bitmap?,
        strings: UiStrings,
    ) {
        val logoSize = 46f
        var textLeft = MARGIN

        if (logo != null) {
            pen.canvas?.drawBitmap(
                logo,
                null,
                Rect(
                    MARGIN.toInt(),
                    pen.y.toInt() - 4,
                    (MARGIN + logoSize).toInt(),
                    (pen.y + logoSize).toInt() - 4,
                ),
                null,
            )
            // The name and title move right of the mark rather than under it, so the block reads as
            // one letterhead instead of a picture with a caption.
            textLeft = MARGIN + logoSize + 12f
        }

        var y = pen.y + 12f
        if (cafeName.isNotBlank()) {
            pen.canvas?.drawText(cafeName, textLeft, y, cafePaint)
            y += 20f * FONT_SCALE + 6f
        }
        pen.canvas?.drawText(strings.salesReportTitle, textLeft, y, titlePaint)
        y += 16f
        pen.canvas?.drawText("${report.startDate}  to  ${report.endDate}", textLeft, y, bodyPaint)

        pen.y = maxOf(y, pen.y + logoSize) + 14f
        pen.rule()
    }

    /**
     * A cursor over a growing PDF: owns the current page, the vertical position, and the rule that
     * a row which will not fit starts a new one.
     */
    private class Pen(
        val document: PdfDocument?,
        val totalPages: Int,
        val strings: UiStrings,
    ) {
        var canvas: Canvas? = null
        var y = MARGIN
        var pageCount = 0
        private var page: PdfDocument.Page? = null

        fun startPage() {
            pageCount++
            if (document != null) {
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageCount).create()
                )
                canvas = page?.canvas
            }
            y = MARGIN
        }

        fun finish() {
            drawFooter()
            page?.let { document?.finishPage(it) }
            page = null
            canvas = null
        }

        /** Room for [needed] points, or turn the page first. */
        private fun ensure(needed: Float) {
            if (y + needed <= BOTTOM_LIMIT) return
            finish()
            startPage()
        }

        private fun drawFooter() {
            // "of N" is unknown during the counting pass, so the footer is simply not drawn then —
            // it occupies fixed space below BOTTOM_LIMIT either way, so its absence cannot change
            // where the page breaks fall.
            if (totalPages <= 0) return
            canvas?.drawText(
                strings.pageOfFormat.format(pageCount, totalPages),
                PAGE_W / 2f,
                FOOTER_Y,
                footerPaint,
            )
        }

        fun section(title: String) {
            ensure(34f)
            y += 8f
            canvas?.drawText(title, MARGIN, y, headerPaint)
            y += 15f
        }

        fun rule() {
            ensure(10f)
            y += 4f
            canvas?.drawLine(MARGIN, y, CONTENT_R, y, rulePaint)
            y += 8f
        }

        fun tableHeader(left: String, right: String) {
            ensure(16f)
            canvas?.drawText(left, MARGIN, y, thPaint)
            canvas?.drawText(right, CONTENT_R - 60f, y, thPaint)
            y += 4f
            canvas?.drawLine(MARGIN, y, CONTENT_R, y, rulePaint)
            y += 12f
        }

        fun tableHeader3(c1: String, c2: String, c3: String, c4: String) {
            ensure(16f)
            canvas?.drawText(c1, MARGIN, y, thPaint)
            canvas?.drawText(c2, MARGIN + 130f, y, thPaint)
            canvas?.drawText(c3, CONTENT_R - 130f, y, thPaint)
            canvas?.drawText(c4, CONTENT_R, y, thPaint.rightAligned())
            y += 4f
            canvas?.drawLine(MARGIN, y, CONTENT_R, y, rulePaint)
            y += 12f
        }

        fun row(label: String, value: String) {
            ensure(14f)
            canvas?.drawText(label, MARGIN, y, bodyPaint)
            canvas?.drawText(value, CONTENT_R - 60f, y, bodyPaint)
            y += 13f
        }

        /** A money row: label left, amount right-aligned in the monospaced face. */
        fun money(label: String, amount: Double, bold: Boolean = false) {
            ensure(14f)
            canvas?.drawText(label, MARGIN, y, bodyPaint)
            canvas?.drawText(
                "RM %.2f".format(amount),
                CONTENT_R,
                y,
                if (bold) moneyBoldPaint else moneyPaint,
            )
            y += 13f
        }

        fun row4(c1: String, c2: String, c3: String, amount: Double) {
            ensure(14f)
            canvas?.drawText(c1.take(22), MARGIN, y, bodyPaint)
            canvas?.drawText(c2.take(28), MARGIN + 130f, y, bodyPaint)
            canvas?.drawText(c3, CONTENT_R - 130f, y, bodyPaint)
            canvas?.drawText("RM %.2f".format(amount), CONTENT_R, y, moneyPaint)
            y += 13f
        }

        private fun Paint.rightAligned(): Paint =
            Paint(this).apply { textAlign = Paint.Align.RIGHT }
    }
}
