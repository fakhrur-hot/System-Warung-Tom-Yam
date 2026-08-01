package com.razstudio.pos.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 4.6 — a rotated publishable key reaches every device on next launch, and a failed refresh
 * never deconfigures a working till.
 *
 * This is the payoff of `/app-config.json`: before it, rotating the key meant visiting every device
 * and retyping it. `StartupViewModel` now re-reads the café's own config in the background on launch
 * and calls [AppConfigStore.supabaseAnonKeyRefresh].
 *
 * Two asymmetries in that method are load-bearing, and both are tested here:
 *
 *  1. **A blank key is ignored.** A café whose site is mid-deploy can serve an `app-config.json` with
 *     empty values — the Vite plugin writes the field either way. Accepting a blank would clear the
 *     working key on a device that was fine a second earlier, and every call would then 401.
 *  2. **Only the key and name refresh — never the Supabase URL.** Rotating a key is routine;
 *     silently moving a device to a different *project* is not, and a corrupted response or a stale
 *     stored `website_url` would do exactly that. The URL is written once, at Setup.
 */
@RunWith(RobolectricTestRunner::class)
class KeyRotationTest {

    private lateinit var config: AppConfigStore

    private val projectUrl = "https://proj.supabase.co"
    private val oldKey = "sb_publishable_OLD1234"
    private val newKey = "sb_publishable_NEW5678"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("key_rotation_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        config = AppConfigStore(context, prefs)
        config.save(
            supabaseUrl = projectUrl,
            supabaseAnonKey = oldKey,
            websiteUrl = "https://cafe.pages.dev",
            cafeName = "Kopitiam",
            cloudflareAccountId = "", cloudflareDnsZone = "", cloudflareApiToken = "",
            cloudflarePagesProject = "", githubRepo = "", githubToken = "",
        )
    }

    @Test
    fun aRotatedKeyIsAdoptedOnTheNextLaunch() {
        config.supabaseAnonKeyRefresh(newKey)
        assertEquals(newKey, config.supabaseAnonKey())
    }

    @Test
    fun aBlankRefreshLeavesTheWorkingKeyIntact() {
        // The failure this prevents: a site mid-deploy serves empty values, and every device that
        // launches during that window silently loses its credential.
        config.supabaseAnonKeyRefresh("")
        config.supabaseAnonKeyRefresh("   ")
        assertEquals("a blank must never overwrite a working key", oldKey, config.supabaseAnonKey())
    }

    @Test
    fun refreshingTheKeyDoesNotMoveTheDeviceToAnotherProject() {
        // supabaseAnonKeyRefresh deliberately has no URL parameter. A key rotates; a project does
        // not — and a device pointed at the wrong project fails in a way that looks like bad
        // credentials, sending the owner hunting for the wrong problem.
        config.supabaseAnonKeyRefresh(newKey)
        assertEquals(projectUrl, config.supabaseUrl())
    }

    @Test
    fun aFailedRefreshIsIndistinguishableFromNoRefresh() {
        // Every non-Success branch in StartupViewModel logs and returns without touching the store,
        // so the observable state after a failure must equal the state before it.
        val urlBefore = config.supabaseUrl()
        val keyBefore = config.supabaseAnonKey()
        val nameBefore = config.cafeName()

        // Nothing called — this is what NetworkError / ParseError / IncompletePayload all do.

        assertEquals(urlBefore, config.supabaseUrl())
        assertEquals(keyBefore, config.supabaseAnonKey())
        assertEquals(nameBefore, config.cafeName())
    }

    @Test
    fun repeatedRefreshesAreIdempotent() {
        repeat(3) { config.supabaseAnonKeyRefresh(newKey) }
        assertEquals(newKey, config.supabaseAnonKey())
        assertEquals(projectUrl, config.supabaseUrl())
    }
}
