package com.razstudio.pos.ui.viewmodels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.razstudio.pos.data.AppConfigFetcher
import com.razstudio.pos.data.AppConfigFetcher.VerifyResult
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.net.NoInternetGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 6.7 — a gated Setup must not be bypassable, and must not lose what was typed.
 *
 * These are the two ways a stepped wizard quietly regresses into the single form it replaced:
 *
 *  1. **Bypass.** If `save()` writes without a verification, the gate is decoration. Worse, the
 *     screen then shows "Saved ✓" for credentials nobody ever tried — which is a stronger false
 *     reassurance than the old flow gave, because it looks like something was checked.
 *  2. **Verification going stale.** If editing a field leaves `verified` set, an operator can verify
 *     a working pair, retype the key, and still save. That makes the check worthless in exactly the
 *     case it exists for.
 *
 * `verifyBackend` is not exercised here — it needs a live host, and the failure matrix already
 * covers its outcomes. What is tested is the *gate*: what `save()` will and will not persist.
 */
@RunWith(RobolectricTestRunner::class)
class SetupVerificationTest {

    private lateinit var config: AppConfigStore
    private lateinit var vm: SetupViewModel

    /**
     * Substitutes the network probe so the *gate* can be tested without a live host.
     *
     * Note there is deliberately no way to set `verified` directly: `update()` clears it on every
     * edit, which is the invalidation this suite exists to prove. Verification can only be reached
     * the way production reaches it — by running [SetupViewModel.verifyConnection].
     */
    private class FakeFetcher(
        guard: NoInternetGuard,
        var result: VerifyResult = VerifyResult.Ok,
    ) : AppConfigFetcher(guard) {
        override suspend fun verifyBackend(supabaseUrl: String, anonKey: String) = result
    }

    private lateinit var fetcher: FakeFetcher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("setup_verify_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        config = AppConfigStore(context, prefs)
        val modeRepo = ModeRepository(config)
        vm = SetupViewModel(
            appConfig = config,
            modeRepository = modeRepo,
            secureStorage = com.razstudio.pos.data.SecureStorage(context, prefs),
            appConfigFetcher = FakeFetcher(NoInternetGuard(modeRepo)).also { fetcher = it },
        )
    }

    private fun fillCloudFields() = vm.update {
        it.copy(
            operatingMode = OperatingMode.CLOUD,
            supabaseUrl = "https://proj.supabase.co",
            supabaseAnonKey = "sb_publishable_abc123",
            cafeName = "Kopitiam",
        )
    }

    // ── The gate ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun anUnverifiedCloudConnectionCannotBeSaved() {
        fillCloudFields()

        assertFalse("save must be blocked before verification", vm.canSave())
        vm.save()

        assertTrue("nothing may be written", config.supabaseUrl().isBlank())
        assertTrue(config.supabaseAnonKey().isBlank())
        assertFalse("and it must not claim to have saved", vm.state.value.saved)
    }

    @Test
    fun blockedSaveExplainsItselfRatherThanFailingSilently() {
        fillCloudFields()
        vm.save()

        val err = vm.state.value.verifyError
        assertNotNull("a blocked save must say why", err)
        assertTrue("and must point at the remedy: $err", err!!.contains("Check the connection"))
    }

    @Test
    fun aVerifiedConnectionSaves() = runTest {
        fillCloudFields()
        vm.verifyConnection()

        assertTrue(vm.canSave())
        vm.save()

        assertEquals("https://proj.supabase.co", config.supabaseUrl())
        assertEquals("sb_publishable_abc123", config.supabaseAnonKey())
    }

    // ── Verification must go stale on edit ────────────────────────────────────────────────────────

    @Test
    fun editingAnyFieldInvalidatesAPriorVerification() = runTest {
        fillCloudFields()
        vm.verifyConnection()
        assertTrue(vm.canSave())

        // The operator changes the key after checking — the classic way a gate becomes decoration.
        vm.update { it.copy(supabaseAnonKey = "sb_publishable_DIFFERENT") }

        assertFalse("a prior verification must not survive an edit", vm.canSave())
        assertFalse(vm.state.value.verified)
    }

    @Test
    fun editingAlsoClearsTheStaleErrorSoTheScreenIsNotMisleading() {
        fillCloudFields()
        vm.save() // produces a verifyError
        assertNotNull(vm.state.value.verifyError)

        vm.update { it.copy(supabaseUrl = "https://other.supabase.co") }
        assertNull("an error about the previous value must not linger", vm.state.value.verifyError)
    }

    // ── Values must survive navigation ────────────────────────────────────────────────────────────

    @Test
    fun goingBackAndForthPreservesWhatWasEntered() {
        // "Back" in this flow is toggling the manual disclosure — the only navigation that can drop
        // state. Everything typed must still be there afterwards.
        fillCloudFields()
        vm.update { it.copy(websiteUrl = "https://cafe.pages.dev") }

        vm.toggleManualFields()
        vm.toggleManualFields()

        val s = vm.state.value
        assertEquals("https://cafe.pages.dev", s.websiteUrl)
        assertEquals("https://proj.supabase.co", s.supabaseUrl)
        assertEquals("sb_publishable_abc123", s.supabaseAnonKey)
        assertEquals("Kopitiam", s.cafeName)
    }

    // ── Off-cloud has nothing to verify ───────────────────────────────────────────────────────────

    @Test
    fun kioskAndLanSaveWithoutAVerificationStep() = runTest {
        // These store no Supabase values at all, so gating them would demand a check with nothing
        // to check — an obstacle with no failure behind it.
        listOf(OperatingMode.LAN, OperatingMode.KIOSK).forEach { mode ->
            vm.update { it.copy(operatingMode = mode, cafeName = "Kiosk") }
            assertTrue("$mode must not require verification", vm.canSave())
        }
    }

    @Test
    fun switchingToCloudReimposesTheGate() {
        vm.update { it.copy(operatingMode = OperatingMode.KIOSK) }
        assertTrue(vm.canSave())

        vm.update { it.copy(operatingMode = OperatingMode.CLOUD) }
        assertFalse("cloud always needs a live check", vm.canSave())
    }

    // ── A website fetch is not a backend verification ─────────────────────────────────────────────

    @Test
    fun fetchingTheConfigDoesNotCountAsVerifyingTheBackend() = runTest {
        // Step 1 proves the *website* served a payload. It says nothing about whether those
        // credentials are accepted by the project they name — that is step 2's entire purpose.
        fillCloudFields()
        vm.verifyConnection()
        vm.update { it.copy(supabaseUrl = "https://from-fetch.supabase.co") }

        assertFalse(vm.canSave())
    }

    @Test
    fun aRefusedKeyBlocksTheSaveAndSaysSo() = runTest {
        fillCloudFields()
        fetcher.result = AppConfigFetcher.VerifyResult.BadKey("refused the publishable key")
        vm.verifyConnection()

        assertFalse(vm.canSave())
        assertTrue(vm.state.value.verifyError!!.contains("refused"))
        vm.save()
        assertTrue("a refused key must write nothing", config.supabaseUrl().isBlank())
    }
}
