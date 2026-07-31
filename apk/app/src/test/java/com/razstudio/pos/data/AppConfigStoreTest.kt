package com.razstudio.pos.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Closes the gap that previously made [AppConfigStore] entirely untestable.
 *
 * `EncryptedSharedPreferences` needs the Android Keystore, which Robolectric does not provide, so under
 * test the real store failed to initialise, took its degrade-to-unconfigured path, and silently dropped
 * every write. An earlier attempt to cover the Payment QR cache through it failed 4 of 7 assertions
 * with `expected:<hash> but was:<null>` — and, worse, *nothing* persisted here was covered at all:
 * `operating_mode` (which the whole three-mode feature branches on), the Payment QR hash (the staleness
 * guard that stops a stale payee), and the Supabase settings.
 *
 * These tests use the class's test-only constructor to substitute a plain `SharedPreferences`, so the
 * logic — defaulting, round-tripping, null-means-remove — is exercised for real. Production still
 * refuses to fall back to plaintext storage, which is the correct trade for a store holding Supabase
 * keys and Cloudflare tokens.
 */
@RunWith(RobolectricTestRunner::class)
class AppConfigStoreTest {

    private lateinit var store: AppConfigStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val plain = context.getSharedPreferences("test_app_config", Context.MODE_PRIVATE)
        plain.edit().clear().commit()
        store = AppConfigStore(context, plain)
    }

    // ── Operating mode — Requirement 1.1 / 1.2 ────────────────────────────────────────────────────

    @Test
    fun operatingModeDefaultsToCloudWhenAbsent() {
        // The behaviour-preserving guarantee: an install predating this feature has no stored mode and
        // must keep working exactly as before (Requirement 1.2). If this ever regresses, every existing
        // café silently switches topology on upgrade.
        assertEquals(OperatingMode.CLOUD, store.operatingMode())
    }

    @Test
    fun everyOperatingModeRoundTrips() {
        for (mode in OperatingMode.entries) {
            store.setOperatingMode(mode)
            assertEquals("mode $mode must survive a write/read cycle", mode, store.operatingMode())
        }
    }

    @Test
    fun anUnrecognisedStoredModeFallsBackToCloudRatherThanCrashing() {
        // Guards a downgrade path: a build that knew a fourth mode wrote its name here, then the user
        // installed an older APK. Crashing on launch would be far worse than reverting to Cloud.
        store.setOperatingMode(OperatingMode.LAN)
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_app_config", Context.MODE_PRIVATE)
            .edit().putString("operating_mode", "TELEPORT").commit()
        assertEquals(OperatingMode.CLOUD, store.operatingMode())
    }

    // ── Payment QR cache — Requirements 14.5 / 14.6 / 14.8 ────────────────────────────────────────

    @Test
    fun paymentQrHashIsNullUntilSet_andNullIsTheNotConfiguredState() {
        assertNull("absence of a hash IS the not-configured state that hides Show QR", store.paymentQrHash())
        store.setPaymentQrHash("a".repeat(64))
        assertEquals("a".repeat(64), store.paymentQrHash())
    }

    @Test
    fun settingPaymentQrHashToNullRemovesIt() {
        store.setPaymentQrHash("b".repeat(64))
        assertEquals("b".repeat(64), store.paymentQrHash())

        store.setPaymentQrHash(null)
        assertNull("removal must clear the key so the button disappears (Requirement 14.5)", store.paymentQrHash())
    }

    @Test
    fun replacingThePaymentQrHashOverwritesRatherThanAppends() {
        store.setPaymentQrHash("c".repeat(64))
        store.setPaymentQrHash("d".repeat(64))
        assertEquals(
            "a replacement must fully supersede the old hash, or a device keeps the old payee",
            "d".repeat(64), store.paymentQrHash(),
        )
    }

    @Test
    fun paymentQrUrlRoundTripsAndClears() {
        assertNull(store.paymentQrUrl())
        store.setPaymentQrUrl("https://example.test/storage/logos/payment-qr.png")
        assertEquals("https://example.test/storage/logos/payment-qr.png", store.paymentQrUrl())
        store.setPaymentQrUrl(null)
        assertNull(store.paymentQrUrl())
    }

    @Test
    fun hashAndUrlAreIndependentKeys() {
        // The resolver reads them separately; a write to one must not disturb the other.
        store.setPaymentQrHash("e".repeat(64))
        store.setPaymentQrUrl("https://example.test/x.png")
        store.setPaymentQrHash(null)
        assertNull(store.paymentQrHash())
        assertEquals("clearing the hash must not clear the URL", "https://example.test/x.png", store.paymentQrUrl())
    }

    // ── Café name ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun cafeNameRoundTripsAndIsBlankByDefault() {
        assertTrue("no café name before setup", store.cafeName().isBlank())
        store.setCafeName("Warung Tom Yam")
        assertEquals("Warung Tom Yam", store.cafeName())
    }

    @Test
    fun setCafeNameDoesNotDisturbTheOperatingMode() {
        // The café-rename flow uses a narrow setter precisely so it cannot clobber connection settings;
        // this pins that guarantee for the mode too.
        store.setOperatingMode(OperatingMode.KIOSK)
        store.setCafeName("Renamed Café")
        assertEquals(OperatingMode.KIOSK, store.operatingMode())
        assertEquals("Renamed Café", store.cafeName())
    }
}
