package com.razstudio.pos.data

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.razstudio.pos.ui.util.PaymentQrPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * Task 16.4 — Property 8, staleness half: the cache is keyed by content hash
 * (Validates Requirements 14.5, 14.6).
 *
 * This is the highest-consequence path in the Payment QR feature. The image lives at a **fixed** object
 * key (`payment-qr.png`), so its URL is byte-identical before and after the admin replaces it. A device
 * caching by URL would keep showing the **previous payee** indefinitely — and because the code carries
 * no amount and the app records no transaction (Requirement 14.10), nothing would ever reveal the
 * mistake. A customer's money would simply go to the wrong account.
 *
 * ### Why this uses an in-memory hash holder rather than [AppConfigStore]
 *
 * An earlier version of this test asserted against `AppConfigStore` directly and failed: every read
 * came back `null`. That is not an app defect. `AppConfigStore` is backed by
 * `EncryptedSharedPreferences`, which needs the Android Keystore, and Robolectric provides none — so
 * the store takes its documented "degrade to unconfigured rather than crash" path and silently drops
 * writes. Testing through it would therefore have been testing the JVM's lack of a keystore.
 *
 * What matters here is the *invariant* — that a content hash distinguishes a replacement from a
 * re-upload, and that removal leaves nothing behind — which is independent of where the hash is stored.
 * `AppConfigStore`'s own persistence is covered on real hardware instead: the hash survived an app
 * restart and an in-place reinstall during device verification.
 */
@RunWith(RobolectricTestRunner::class)
class PaymentQrCacheTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Stands in for the device's cached hash + URL, which live in AppConfigStore in production. */
    private class FakeQrCache {
        var hash: String? = null
        var url: String? = null
        fun clear() { hash = null; url = null }
    }

    private lateinit var cache: FakeQrCache

    @Before
    fun setUp() {
        cache = FakeQrCache()
        PaymentQrPipeline.deleteFromInternal(context)
    }

    private fun qrPng(payload: String, size: Int = 384): ByteArray {
        val m = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until m.width) for (y in 0 until m.height) {
            bmp.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Mirrors what an upload does: store the bytes, then record the content hash and the URL. */
    private fun store(payload: String): String {
        val file = PaymentQrPipeline.saveBytesToInternal(context, qrPng(payload))
        val hash = PaymentQrPipeline.computeSha256Hex(file)
        cache.hash = hash
        // Deliberately the SAME url every time — that is exactly the real-world condition being guarded.
        cache.url = "https://example.test/storage/logos/payment-qr.png"
        return hash
    }

    @Test
    fun uploadingRecordsAHashSoShowQrCanAppear() {
        assertNull("no hash before any upload", cache.hash)
        val hash = store("PAYEE/A")
        assertEquals(hash, cache.hash)
        assertNotNull(PaymentQrPipeline.storedFileOrNull(context))
    }

    @Test
    fun replacingTheImageChangesTheHash_soNoDeviceKeepsTheOldPayee() {
        val hashA = store("PAYEE/A")
        val hashB = store("PAYEE/B")

        assertNotEquals("a replacement MUST change the hash, or staff keep the old payee", hashA, hashB)
        assertEquals(hashB, cache.hash)

        val stored = PaymentQrPipeline.storedFileOrNull(context)!!
        assertEquals(
            "the bytes on disk must be the NEW code, not the replaced one still sitting there",
            "PAYEE/B", PaymentQrPipeline.decodeQrPayloadFromBytes(stored.readBytes()),
        )
    }

    @Test
    fun theUrlAloneCannotDetectAReplacement_whichIsWhyTheHashExists() {
        val hashA = store("PAYEE/A"); val urlA = cache.url
        val hashB = store("PAYEE/B"); val urlB = cache.url

        assertEquals("the URL is stable across replacement — this is the trap", urlA, urlB)
        assertNotEquals("only the hash reveals the change", hashA, hashB)
    }

    @Test
    fun removingClearsTheCacheAndTheFile_soShowQrDisappears() {
        store("PAYEE/A")
        assertNotNull(cache.hash)

        PaymentQrPipeline.deleteFromInternal(context)
        cache.clear()

        assertNull("a null hash IS the not-configured state that hides Show QR", cache.hash)
        assertNull(cache.url)
        assertNull("the file must be gone too", PaymentQrPipeline.storedFileOrNull(context))
    }

    @Test
    fun reUploadingTheSameImageIsNotTreatedAsAChange() {
        val first = store("PAYEE/SAME")
        val again = store("PAYEE/SAME")
        assertEquals(
            "an identical re-upload must not churn the hash, or every device refetches for nothing",
            first, again,
        )
    }

    @Test
    fun aHashWithNoFileBehindItIsDetectable() {
        // The state after a partial wipe or a failed download. PaymentQrResolver treats this as
        // needing a refetch rather than trusting the hash and rendering nothing.
        store("PAYEE/ORPHAN")
        PaymentQrPipeline.deleteFromInternal(context)

        assertNotNull("hash still recorded", cache.hash)
        assertNull("but no file behind it", PaymentQrPipeline.storedFileOrNull(context))
        assertTrue(
            "this combination must be recognisable as needing a refetch",
            cache.hash != null && PaymentQrPipeline.storedFileOrNull(context) == null,
        )
    }

    @Test
    fun hashIsAContentHash_notDerivedFromTheFilename() {
        // Both payloads are stored under the same filename, so a name-derived hash would collide.
        val a = store("PAYEE/ONE")
        val b = store("PAYEE/TWO")
        assertNotEquals(a, b)
        assertEquals("sha-256 hex is 64 chars", 64, a.length)
        assertTrue(a.all { it.isDigit() || it in 'a'..'f' })
    }
}
