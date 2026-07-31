package com.razstudio.pos.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * Task 15.4 — Property 8: a stored Payment QR is scannable, current, and unaltered
 * (Validates Requirements 14.3, 14.4).
 *
 * These matter more than most tests in this project. The Payment QR is a *static payee* code: it
 * carries no amount and no order reference, the customer keys the sum in their own banking app, and
 * nothing in the app records the transaction (Requirement 14.10). So there is **no audit trail** that
 * would reveal a degraded or altered code after the fact — a QR that stops decoding, or decodes to a
 * different payee, would simply take a customer's money somewhere unintended and leave no trace.
 * That makes these invariants the only line of defence.
 */
@RunWith(RobolectricTestRunner::class)
class PaymentQrPipelineTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A real QR, generated with the same ZXing that the pipeline validates with. */
    private fun qrBitmap(payload: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bmp
    }

    private fun png(bmp: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun jpeg(bmp: Bitmap, quality: Int = 90): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    // ── Property 8a — an accepted image decodes ───────────────────────────────────────────────────

    @Test
    fun decodesAGenuineQr_png() {
        val payload = "DUITNOW/PAYEE/RAZSTUDIO-8A"
        assertEquals(payload, PaymentQrPipeline.decodeQrPayloadFromBytes(png(qrBitmap(payload))))
    }

    @Test
    fun decodesAGenuineQr_jpeg() {
        val payload = "DUITNOW/PAYEE/RAZSTUDIO-8A-JPEG"
        assertEquals(payload, PaymentQrPipeline.decodeQrPayloadFromBytes(jpeg(qrBitmap(payload))))
    }

    // ── Property 8b — the payload survives storage ────────────────────────────────────────────────

    @Test
    fun storedBytesStillDecodeToTheSamePayload() {
        val payload = "DUITNOW/PAYEE/RAZSTUDIO-8B"
        val original = png(qrBitmap(payload))

        val stored = PaymentQrPipeline.saveBytesToInternal(context, original)
        assertTrue("stored file should exist", stored.exists())

        val readBack = stored.readBytes()
        assertEquals(
            "the payload must survive a storage round-trip — a changed payload is a changed payee",
            payload, PaymentQrPipeline.decodeQrPayloadFromBytes(readBack),
        )
        assertTrue("PNG input must be stored byte-identically, not re-encoded", original.contentEquals(readBack))
    }

    @Test
    fun aPngIsKeptAsAPngAndNotReEncoded() {
        // Re-encoding a dense QR as lossy JPEG can smear its modules until a scanner fails on it, so
        // the pipeline must not silently convert. Verified by the PNG magic bytes surviving.
        val bytes = png(qrBitmap("DUITNOW/PAYEE/LOSSLESS"))
        val stored = PaymentQrPipeline.saveBytesToInternal(context, bytes).readBytes()
        assertTrue(
            "stored file lost its PNG signature — something re-encoded it",
            stored.size > 8 && stored[0] == 0x89.toByte() && stored[1] == 'P'.code.toByte() &&
                stored[2] == 'N'.code.toByte() && stored[3] == 'G'.code.toByte(),
        )
    }

    // ── Property 8c — a non-QR image is rejected ──────────────────────────────────────────────────

    @Test
    fun rejectsAnImageWithNoQrInIt() {
        val plain = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.LTGRAY)
        }
        assertNull(
            "a photo with no QR must be rejected rather than stored as an unscannable picture",
            PaymentQrPipeline.decodeQrPayloadFromBytes(png(plain)),
        )
    }

    @Test
    fun rejectsBytesThatAreNotAnImageAtAll() {
        assertNull(
            "garbage input must be rejected, not crash",
            PaymentQrPipeline.decodeQrPayloadFromBytes("this is not an image".toByteArray()),
        )
    }

    @Test
    fun rejectsATruncatedImage() {
        // The shape of a failed or proxy-mangled download — exactly what PaymentQrResolver re-verifies
        // before caching, so that a staff device cannot end up showing a half-downloaded code.
        val full = png(qrBitmap("DUITNOW/PAYEE/TRUNCATED"))
        assertNull(PaymentQrPipeline.decodeQrPayloadFromBytes(full.copyOfRange(0, full.size / 3)))
    }

    // ── Storage lifecycle ─────────────────────────────────────────────────────────────────────────

    @Test
    fun savingReplacesAnyPreviousImageRatherThanLeavingBothFormats() {
        // A PNG replacing a JPEG must not leave the old file behind for storedFileOrNull() to prefer —
        // that is how a device would keep serving a superseded payee.
        val jpegBytes = jpeg(qrBitmap("DUITNOW/PAYEE/OLD"))
        PaymentQrPipeline.saveBytesToInternal(context, jpegBytes)

        val newPayload = "DUITNOW/PAYEE/NEW"
        PaymentQrPipeline.saveBytesToInternal(context, png(qrBitmap(newPayload)))

        val stored = PaymentQrPipeline.storedFileOrNull(context)
        assertNotNull("a stored file should exist after saving", stored)
        assertEquals(
            "the surviving file must be the NEW code, not the replaced one",
            newPayload, PaymentQrPipeline.decodeQrPayloadFromBytes(stored!!.readBytes()),
        )
    }

    @Test
    fun deleteRemovesTheStoredImage() {
        PaymentQrPipeline.saveBytesToInternal(context, png(qrBitmap("DUITNOW/PAYEE/DELETE-ME")))
        assertNotNull(PaymentQrPipeline.storedFileOrNull(context))

        PaymentQrPipeline.deleteFromInternal(context)
        assertNull(
            "removal must leave nothing behind, so the Show QR button disappears",
            PaymentQrPipeline.storedFileOrNull(context),
        )
    }

    @Test
    fun hashChangesWhenTheImageChangesAndIsStableWhenItDoesNot() {
        // The staleness guard depends on this: a content hash must distinguish two different codes,
        // and must NOT churn when the same image is re-uploaded.
        val a = PaymentQrPipeline.saveBytesToInternal(context, png(qrBitmap("PAYEE/A")))
        val hashA1 = PaymentQrPipeline.computeSha256Hex(a)
        val a2 = PaymentQrPipeline.saveBytesToInternal(context, png(qrBitmap("PAYEE/A")))
        val hashA2 = PaymentQrPipeline.computeSha256Hex(a2)
        val b = PaymentQrPipeline.saveBytesToInternal(context, png(qrBitmap("PAYEE/B")))
        val hashB = PaymentQrPipeline.computeSha256Hex(b)

        assertEquals("re-uploading an identical image must not change the hash", hashA1, hashA2)
        assertTrue("a different payee must produce a different hash", hashA1 != hashB)
    }

    @Test
    fun bitmapLoadedBackFromStorageStillDecodes() {
        val payload = "DUITNOW/PAYEE/ROUNDTRIP"
        PaymentQrPipeline.saveBytesToInternal(context, png(qrBitmap(payload)))
        val loaded = PaymentQrPipeline.loadFromInternal(context)
        assertNotNull("loadFromInternal must return the stored image", loaded)
        val reEncoded = png(loaded!!)
        assertEquals(
            "the bitmap the dialog displays must itself still be scannable",
            payload, PaymentQrPipeline.decodeQrPayloadFromBytes(reEncoded),
        )
    }

    @Test
    fun bitmapFactoryCanReadWhatWeStored() {
        // Guards against storing something that decodes as a QR but that Android cannot render — the
        // dialog would then show a blank card with no error.
        PaymentQrPipeline.saveBytesToInternal(context, png(qrBitmap("PAYEE/RENDERABLE")))
        val f = PaymentQrPipeline.storedFileOrNull(context)!!
        assertNotNull(BitmapFactory.decodeFile(f.absolutePath))
    }
}
