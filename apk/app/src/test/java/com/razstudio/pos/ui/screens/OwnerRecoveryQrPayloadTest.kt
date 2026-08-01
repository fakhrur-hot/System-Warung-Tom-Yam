package com.razstudio.pos.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.AppConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Owner Recovery QR carries the café's backend, so an owner who already holds their key never
 * has to walk the Setup Wizard.
 *
 * A recovery token names no café. Before this, a device that had never been set up could not act on
 * a perfectly valid owner key at all — there was nowhere to send it — so the wizard was effectively
 * compulsory even for an owner holding their QR. The QR now carries `api` and `key` beside
 * `recover`, and an unconfigured device adopts them and signs straight in.
 *
 * Two properties are load-bearing and tested here:
 *
 *  1. **Backwards compatibility.** Every QR already printed, saved as a PNG, or stuck to a wall
 *     lacks the new params. Those must keep working exactly as before on a configured device, and
 *     `extractRecoverToken` must be unaffected by the params being there or not.
 *  2. **The adoption boundary.** A QR may only configure a device that has *no* café. Allowing it to
 *     repoint a working till would turn any QR handed over by a stranger into a way to move the
 *     café's admin session onto a backend they control.
 */
@RunWith(RobolectricTestRunner::class)
class OwnerRecoveryQrPayloadTest {

    private val token = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val api = "https://jxxzdmabcdef.supabase.co"
    private val anon = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload-part.sig"

    private val legacyQr = "https://tani.example/join?recover=$token"
    private val enrichedQr =
        "https://tani.example/join?recover=$token" +
            "&${ApiClient.QR_PARAM_API}=https%3A%2F%2Fjxxzdmabcdef.supabase.co" +
            "&${ApiClient.QR_PARAM_KEY}=$anon"

    private lateinit var config: AppConfigStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("qr_payload_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        config = AppConfigStore(context, prefs)
    }

    // ── Parsing ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theTokenIsUnchangedByTheAddedParams() {
        // The regression that would break every existing café: if appending params disturbed the
        // token match, the QR would stop signing anyone in.
        assertEquals(token, extractRecoverToken(legacyQr))
        assertEquals(token, extractRecoverToken(enrichedQr))
    }

    @Test
    fun theBackendIsReadBackUrlDecoded() {
        assertEquals(api, queryParam(enrichedQr, ApiClient.QR_PARAM_API))
        assertEquals(anon, queryParam(enrichedQr, ApiClient.QR_PARAM_KEY))
    }

    @Test
    fun aLegacyQrCarriesNoBackend() {
        assertNull(queryParam(legacyQr, ApiClient.QR_PARAM_API))
        assertNull(queryParam(legacyQr, ApiClient.QR_PARAM_KEY))
    }

    @Test
    fun aBareTokenIsStillAcceptedAndCarriesNoBackend() {
        // Manual entry: the owner types the key. Nothing to adopt, which is why a typed key on an
        // unconfigured device still needs Setup — the key alone cannot name a café.
        assertEquals(token, extractRecoverToken(token))
        assertNull(queryParam(token, ApiClient.QR_PARAM_API))
    }

    @Test
    fun aParamNameIsNotMatchedAsASubstringOfAnother() {
        // "key=" must not be found inside "monkey=" — the match is anchored to ? or &.
        assertNull(queryParam("https://x/join?monkey=nope", ApiClient.QR_PARAM_KEY))
    }

    // ── The adoption boundary ─────────────────────────────────────────────────────────────────────

    @Test
    fun anUnconfiguredDeviceAdoptsTheBackend() {
        assertTrue(config.adoptBackendFromRecoveryQr(api, anon))
        assertEquals(api, config.supabaseUrl())
        assertEquals(anon, config.supabaseAnonKey())
    }

    @Test
    fun aConfiguredDeviceRefusesToBeRepointed() {
        config.adoptBackendFromRecoveryQr(api, anon)

        val hostile = "https://attacker.supabase.co"
        assertFalse(
            "a scanned QR must never move a working till to another backend",
            config.adoptBackendFromRecoveryQr(hostile, "attacker-anon-key"),
        )
        assertEquals("the café's own backend must survive untouched", api, config.supabaseUrl())
        assertEquals(anon, config.supabaseAnonKey())
    }

    @Test
    fun aHalfPresentPairIsRejectedOutright() {
        // A URL with no anon key would leave the device configured-looking but unable to
        // authenticate, and Setup would no longer offer itself — unrecoverable short of clearing
        // app data. Neither field is written unless both are present.
        assertFalse(config.adoptBackendFromRecoveryQr(api, ""))
        assertFalse(config.adoptBackendFromRecoveryQr("", anon))
        assertTrue("nothing may have been written", config.supabaseUrl().isBlank())
        assertTrue(config.supabaseAnonKey().isBlank())
    }

    @Test
    fun aTrailingSlashIsNormalisedAwayBeforeStorage() {
        // baseUrl() appends "/functions/v1"; a stored trailing slash would produce a double slash.
        assertTrue(config.adoptBackendFromRecoveryQr("$api/", anon))
        assertEquals(api, config.supabaseUrl())
    }

    // ── The café's Cloudflare site rides along in the link's own origin ───────────────────────────

    @Test
    fun theWebsiteOriginIsReadFromTheLinkItself() {
        // The backend builds the QR as "${WEBSITE_ORIGIN}/join?recover=…", so the Pages site is
        // already stated by the URL and needs no third parameter.
        assertEquals("https://tani.example", originOf(enrichedQr))
        assertEquals("https://tani.example", originOf(legacyQr))
    }

    @Test
    fun aBareTokenHasNoOrigin() {
        assertEquals("", originOf(token))
        assertEquals("", originOf("not a url at all"))
    }

    @Test
    fun adoptingBringsAcrossTheSiteAsWellAsTheBackend() {
        // Supabase alone is only half a till: without the site the device cannot print QR cards or
        // hand a customer an ordering link.
        assertTrue(config.adoptBackendFromRecoveryQr(api, anon, websiteUrl = "https://tani.example"))
        assertEquals("https://tani.example", config.websiteUrl())
    }

    @Test
    fun aLegacyQrStillAdoptsNothingAtAll() {
        // No api/key params means no adoption path was taken, so the site must not be written
        // either — a device holding a website but no backend is a state Setup cannot repair.
        assertNull(queryParam(legacyQr, ApiClient.QR_PARAM_API))
        assertTrue(config.websiteUrl().isBlank())
    }
}
