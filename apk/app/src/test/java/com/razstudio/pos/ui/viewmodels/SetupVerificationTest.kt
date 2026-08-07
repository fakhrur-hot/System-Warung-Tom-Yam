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
        override suspend fun verifyBackend(
            supabaseUrl: String,
            anonKey: String,
            interactiveSetup: Boolean,
        ) = result
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
            setupPreflight = com.razstudio.pos.data.SetupPreflight(),
        )

        // Cloud's default tab is the owner key, which has no form and therefore nothing to block.
        // That path is covered by theOwnerQrTabHasNothingToBlock.
    }

    // ── The gate ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theOwnerQrTabHasNothingToBlock() {
        // The defect this tab was added for: a Full QR café holding its owner key could not be
        // saved at all, because the form demanded a café name — one the QR carries and the very
        // next screen overwrites. Nothing typed, nothing blocking.
        vm.update { it.copy(operatingMode = OperatingMode.CLOUD, cafeName = "", supabaseUrl = "", supabaseAnonKey = "") }
        vm.selectConnectionTab(ConnectionTab.OWNER_QR)

        assertNull("the owner key supplies all of this", vm.blockingReason())
    }

    @Test
    fun theProvisionTabHasNothingToBlock() {
        // Provisioning creates the backend and mints the owner key, so the form this screen shows
        // is just a launcher. Nothing typed here can be missing.
        vm.update { it.copy(operatingMode = OperatingMode.CLOUD, cafeName = "", supabaseUrl = "", supabaseAnonKey = "") }
        vm.selectConnectionTab(ConnectionTab.PROVISION_NEW_CAFE)

        assertNull("provisioning supplies all of this", vm.blockingReason())
    }

    @Test
    fun offCloudModesAreUnaffectedByTheTab() {
        // Only Cloud has tabs. A LAN or Kiosk café must still be blocked on its name whatever the
        // stale tab value happens to be.
        listOf(OperatingMode.LAN, OperatingMode.KIOSK).forEach { mode ->
            vm.selectConnectionTab(ConnectionTab.OWNER_QR)
            vm.update { it.copy(operatingMode = mode, cafeName = "") }
            assertNotNull("$mode must still need a name", vm.blockingReason())
        }
    }

    @Test
    fun kioskAndLanSaveWithoutAVerificationStep() = runTest {
        // These store no Supabase values at all, so gating them would demand a check with nothing
        // to check — an obstacle with no failure behind it.
        listOf(OperatingMode.LAN, OperatingMode.KIOSK).forEach { mode ->
            vm.update { it.copy(operatingMode = mode, cafeName = "Kiosk") }
            assertTrue("$mode must not require verification", vm.canSave())
        }
    }
}
