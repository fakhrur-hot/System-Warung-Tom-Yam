package com.razstudio.pos.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.google.CafeBundleStore
import com.razstudio.pos.data.google.CafeConfigPayload
import com.razstudio.pos.data.google.GoogleSignInService
import com.razstudio.pos.data.local.AppDatabase
import com.razstudio.pos.data.local.DatabaseBackupManager
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.data.local.Table
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 23.10 — the two properties the sign-in screen exists to satisfy.
 *
 * **Property 10: Google sign-in is never a gate.** Every path out reaches the entry screen. The
 * screen has no state a café owner can be stuck in, and Skip works before, during and after any
 * failure. This matters more than it sounds: the person opening the café at 7am may have no signal,
 * a phone with no Play Services, or a Google account that is not on the Testing allow-list — and in
 * all three cases the till must open.
 *
 * **Property 11: A restored café is indistinguishable from a configured one.** After a restore,
 * `isModeConfigured` must be true with no further input. A device that looks signed in and cannot
 * host is worse than one that plainly needs Setup, because nothing on screen says so.
 */
@RunWith(RobolectricTestRunner::class)
class SignInViewModelTest {

    private lateinit var config: AppConfigStore
    private lateinit var modes: ModeRepository
    private lateinit var db: AppDatabase
    private lateinit var backups: DatabaseBackupManager
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("sign_in_vm_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        config = AppConfigStore(ctx, prefs)
        // One instance, shared. Building a second repository here would leave the ViewModel writing
        // to a different cached mode than the one the assertions read — the trap already documented
        // in NoInternetGuardTest.
        modes = ModeRepository(config)

        // A real database and a real backup manager, not fakes. The setup-restore path is the whole
        // reason the bundle is useful off-cloud, and a fake serialiser would test the test.
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        backups = DatabaseBackupManager(
            db.menuDao(), db.orderDao(), db.tableDao(), db.settingsDao(),
            db.printerConfigDao(), db.pendingOrderDao(), db.printJobDao(),
        )
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    // ── Fakes ────────────────────────────────────────────────────────────────────────────────────

    private fun signInService(available: Boolean = true) =
        object : GoogleSignInService(ApplicationProvider.getApplicationContext()) {
            override fun isAvailable() = available
        }

    private fun bundleStore(load: CafeBundleStore.LoadResult) =
        object : CafeBundleStore(ApplicationProvider.getApplicationContext()) {
            override suspend fun load(accessToken: String, folderId: String) = load
        }

    private fun viewModel(
        available: Boolean = true,
        load: CafeBundleStore.LoadResult = CafeBundleStore.LoadResult.None,
    ) = SignInViewModel(
        signInService(available), bundleStore(load), config, modes, backups,
        com.razstudio.pos.data.google.GoogleAccountSession(ApplicationProvider.getApplicationContext()),
        com.razstudio.pos.data.local.LocalImageStore(ApplicationProvider.getApplicationContext()),
    )

    private fun cloudPayload(name: String = "Tani Tom Yam") = CafeConfigPayload(
        mode = OperatingMode.CLOUD,
        cafeName = name,
        supabaseUrl = "https://proj.supabase.co",
        supabaseAnonKey = "sb_publishable_abc",
        websiteUrl = "https://tani.pages.dev",
        ownerRecoveryQr = "https://tani.pages.dev/join?recover=tok",
    )

    /**
     * Reaches [SignInViewModel.restore] without a Google account, which no test can obtain.
     *
     * `restore` is `internal suspend` rather than private precisely so this can call it directly:
     * reflection cannot invoke a suspend function without hand-rolling a Continuation, and the
     * earlier reflective version silently failed to await the setup import — which is how the
     * detached-coroutine race in the production path was found.
     */
    private suspend fun restoreVia(vm: SignInViewModel, payload: CafeConfigPayload) {
        vm.restore(payload)
    }

    // ── Property 10: never a gate ────────────────────────────────────────────────────────────────

    @Test
    fun skipLeavesTheDeviceExactlyAsItWas() {
        val vm = viewModel()
        vm.settle()

        assertTrue("skip must be remembered, or it is asked again tomorrow", config.startupSignInSettled())
        OperatingMode.entries.forEach {
            assertFalse("skip must configure nothing", config.isModeConfigured(it))
        }
    }

    @Test
    fun aBuildWithNoOAuthClientNeverOffersTheScreen() {
        // A café that rebranded its applicationId has no registered Android client, so the button
        // could only ever fail. It is skipped rather than shown broken (task 23.11).
        config.setOperatingMode(OperatingMode.CLOUD)
        assertFalse(viewModel(available = false).shouldOfferSignIn())
    }

    @Test
    fun lanAndKioskNeverOfferTheScreen() {
        listOf(OperatingMode.LAN, OperatingMode.KIOSK).forEach { mode ->
            config.setOperatingMode(mode)
            modes.setMode(mode)
            assertFalse("$mode has no internet by definition", viewModel().shouldOfferSignIn())
        }
    }

    @Test
    fun cloudOffersIt() {
        config.setOperatingMode(OperatingMode.CLOUD)
        modes.setMode(OperatingMode.CLOUD)
        assertTrue(viewModel().shouldOfferSignIn())
    }

    @Test
    fun aProblemIsAlwaysDismissibleBackToSomethingUsable() {
        // The screen must have no dead end. Whatever went wrong, Idle is reachable and Skip works.
        val vm = viewModel()
        vm.dismissProblem()
        assertTrue(vm.state.value is SignInViewModel.State.Idle)
    }

    // ── Property 11: a restored café is a configured café ────────────────────────────────────────

    @Test
    fun aRestoredPayloadSatisfiesIsModeConfigured() = runTest {
        modes.setMode(OperatingMode.LAN)  // start somewhere else entirely
        val vm = viewModel()

        restoreVia(vm, cloudPayload())

        assertTrue(
            "a restored café must be ready with no further input",
            config.isModeConfigured(OperatingMode.CLOUD),
        )
        assertEquals("Tani Tom Yam", config.cafeName())
        assertEquals(OperatingMode.CLOUD, modes.currentMode())
        assertTrue(vm.state.value is SignInViewModel.State.Restored)
    }

    @Test
    fun anOffCloudPayloadRestoresWithNoBackendAtAll() = runTest {
        val vm = viewModel()
        restoreVia(
            vm,
            CafeConfigPayload(
                mode = OperatingMode.KIOSK,
                cafeName = "Kopitiam",
                supabaseUrl = "",
                supabaseAnonKey = "",
                websiteUrl = "",
                ownerRecoveryQr = "",
            ),
        )

        assertTrue(config.isModeConfigured(OperatingMode.KIOSK))
        assertTrue(vm.state.value is SignInViewModel.State.Restored)
    }

    @Test
    fun theModeIsWrittenBeforeTheFieldsAreJudged() = runTest {
        // isModeConfigured returns false for any mode other than the stored one, so writing the
        // fields first would leave a window where a fully restored café reads as unconfigured.
        modes.setMode(OperatingMode.KIOSK)
        restoreVia(viewModel(), cloudPayload())

        assertEquals(OperatingMode.CLOUD, config.operatingMode())
        assertTrue(config.isModeConfigured(OperatingMode.CLOUD))
        assertFalse("and the mode it left must lock", config.isModeConfigured(OperatingMode.KIOSK))
    }

    // ── Task 23.8: two cafés, and only the owner can say which ───────────────────────────────────

    @Test
    fun keepingTheDeviceCafeWritesNothing() = runTest {
        config.setOperatingMode(OperatingMode.CLOUD)
        modes.setMode(OperatingMode.CLOUD)
        config.save(
            supabaseUrl = "https://mine.supabase.co",
            supabaseAnonKey = "sb_publishable_mine",
            websiteUrl = "",
            cafeName = "My Own Café",
        )

        val vm = viewModel()
        vm.keepDeviceCafe()

        assertEquals("My Own Café", config.cafeName())
        assertEquals("https://mine.supabase.co", config.supabaseUrl())
    }

    @Test
    fun keepingTheAccountCafeReplacesTheDeviceOne() = runTest {
        config.setOperatingMode(OperatingMode.CLOUD)
        modes.setMode(OperatingMode.CLOUD)
        config.save(
            supabaseUrl = "https://mine.supabase.co",
            supabaseAnonKey = "sb_publishable_mine",
            websiteUrl = "",
            cafeName = "My Own Café",
        )

        val vm = viewModel()
        restoreVia(vm, cloudPayload("Tani Tom Yam"))

        assertEquals("Tani Tom Yam", config.cafeName())
        assertEquals("https://proj.supabase.co", config.supabaseUrl())
        assertTrue(config.isModeConfigured(OperatingMode.CLOUD))
    }

    // ── An account with no café gets one action, not three ───────────────────────────────────────

    // ── The setup half of a bundle: tables and menu, not just config ────────────────────────────

    @Test
    fun aBlankDeviceGetsItsTablesAndMenuBack() = runTest {
        // Off-cloud this is the entire value of the feature. A LAN cafe has no backend to sync
        // from, so a config-only restore hands a replacement device a correctly-named till with no
        // tables and an empty menu -- configured, and unable to sell anything.
        val setup = """
            {"version":2,
             "tables":[{"id":"T1","label":"1","sortOrder":0}],
             "menuItems":[{"id":"m1","category":"MAIN","price":5.0,"available":true,
                           "askMeDaily":false,"nameEn":"Nasi Goreng"}],
             "orders":[],"pendingOrders":[]}
        """.trimIndent()

        val vm = viewModel()
        restoreVia(vm, CafeConfigPayload(
            mode = OperatingMode.KIOSK, cafeName = "Kopitiam",
            supabaseUrl = "", supabaseAnonKey = "", websiteUrl = "",
            ownerRecoveryQr = "", setupData = setup,
        ))

        assertEquals("the menu must come back", 1, db.menuDao().getAll().size)
        assertTrue("and the cafe must be ready", config.isModeConfigured(OperatingMode.KIOSK))
    }

    @Test
    fun aDeviceThatAlreadyRunsACafeKeepsItsOwnData() = runTest {
        // applyImport wipes before it imports, including the order history. An owner may pick the
        // account's cafe in the conflict dialog while this device still holds a day of unsynced
        // orders; destroying them to import a menu is not a trade anyone consented to. Config
        // crosses over so the device points at the right cafe -- its data stays put.
        config.setOperatingMode(OperatingMode.CLOUD)
        modes.setMode(OperatingMode.CLOUD)
        config.save(
            supabaseUrl = "https://mine.supabase.co", supabaseAnonKey = "sb_publishable_mine",
            websiteUrl = "", cafeName = "My Own Cafe",
        )
        db.tableDao().insert(Table(id = "MINE", label = "9"))
        db.menuDao().upsertAll(listOf(MenuItem(
            id = "mine", category = "MAIN", price = 3.0,
            available = true, askMeDaily = false, nameEn = "My Item",
        )))

        val setup = """{"version":2,"tables":[],"menuItems":[],"orders":[],"pendingOrders":[]}"""
        restoreVia(viewModel(), cloudPayload("Theirs").copy(setupData = setup))

        assertEquals("this device's menu must survive", 1, db.menuDao().getAll().size)
        assertEquals("and its tables", 1, db.tableDao().getAll().size)
        assertEquals("but the config follows the account", "Theirs", config.cafeName())
    }

    @Test
    fun aBundleWithNoSetupDataStillRestoresItsConfig() = runTest {
        // Bundles written before setupData existed, and cafes whose export failed. Neither should
        // cost the owner their config restore.
        val vm = viewModel()
        restoreVia(vm, cloudPayload().copy(setupData = ""))

        assertTrue(config.isModeConfigured(OperatingMode.CLOUD))
        assertTrue(vm.state.value is SignInViewModel.State.Restored)
    }

    @Test
    fun theSettledFlagIsRecordedOnEveryExit() {
        // Including Skip and Demo. A decision not to sign in is still a decision; re-asking each
        // morning turns an optional convenience into a gate with extra steps.
        assertFalse(config.startupSignInSettled())
        viewModel().settle()
        assertTrue(config.startupSignInSettled())
    }
}
