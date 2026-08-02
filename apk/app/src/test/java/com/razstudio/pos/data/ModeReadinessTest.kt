package com.razstudio.pos.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The home screen enables exactly one mode button — the one whose Setup was completed and saved —
 * and greys the other two with the reason.
 *
 * That gate rests entirely on [AppConfigStore.isModeConfigured], so what it counts as "configured"
 * is the whole feature. Two ways it could go wrong, both tested here:
 *
 *  1. **Too lenient.** A button lights up for a café that cannot actually run — the operator taps
 *     it mid-setup and lands somewhere broken, which is worse than the button being greyed.
 *  2. **Leaking across modes.** Switching mode clears the fields the new topology does not use, but
 *     a device carrying leftovers from a previous life must not present itself as a working café in
 *     the mode it no longer runs.
 */
@RunWith(RobolectricTestRunner::class)
class ModeReadinessTest {

    private lateinit var config: AppConfigStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("mode_ready_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        config = AppConfigStore(context, prefs)
    }

    private fun saveCloud(url: String = "https://p.supabase.co", key: String = "sb_publishable_x", name: String = "Kopitiam") {
        config.setOperatingMode(OperatingMode.CLOUD)
        config.save(supabaseUrl = url, supabaseAnonKey = key, websiteUrl = "", cafeName = name)
    }

    // ── Nothing is unlocked on a fresh device ─────────────────────────────────────────────────────

    @Test
    fun aFreshDeviceUnlocksNothing() {
        OperatingMode.entries.forEach {
            assertFalse("$it must be locked before any setup", config.isModeConfigured(it))
        }
    }

    // ── Cloud needs a backend it can reach, plus a name ───────────────────────────────────────────

    @Test
    fun cloudNeedsUrlKeyAndName() {
        saveCloud()
        assertTrue(config.isModeConfigured(OperatingMode.CLOUD))
    }

    @Test
    fun cloudStaysLockedWithoutAKey() {
        saveCloud(key = "")
        assertFalse("a URL alone is not a working café", config.isModeConfigured(OperatingMode.CLOUD))
    }

    @Test
    fun cloudStaysLockedWithoutAName() {
        // isConfigured() never looked at the café name, which is why it could not back this gate.
        saveCloud(name = "")
        assertFalse(config.isModeConfigured(OperatingMode.CLOUD))
    }

    // ── Off-cloud stores no backend, so the name is the whole requirement ─────────────────────────

    @Test
    fun lanAndKioskNeedOnlyAName() {
        listOf(OperatingMode.LAN, OperatingMode.KIOSK).forEach { mode ->
            config.setOperatingMode(mode)
            config.save(supabaseUrl = "", supabaseAnonKey = "", websiteUrl = "", cafeName = "Kopitiam")
            assertTrue("$mode should be ready with just a name", config.isModeConfigured(mode))
        }
    }

    @Test
    fun offCloudStaysLockedWithoutAName() {
        listOf(OperatingMode.LAN, OperatingMode.KIOSK).forEach { mode ->
            config.setOperatingMode(mode)
            config.save(supabaseUrl = "", supabaseAnonKey = "", websiteUrl = "", cafeName = "")
            assertFalse("$mode must not unlock unnamed", config.isModeConfigured(mode))
        }
    }

    // ── Exactly one mode is ever unlocked ────────────────────────────────────────────────────────

    @Test
    fun onlyTheSavedModeIsUnlocked() {
        saveCloud()

        assertTrue(config.isModeConfigured(OperatingMode.CLOUD))
        assertFalse(config.isModeConfigured(OperatingMode.LAN))
        assertFalse(config.isModeConfigured(OperatingMode.KIOSK))
    }

    @Test
    fun leftoverCloudValuesDoNotUnlockCloudAfterSwitchingAway() {
        // The dangerous case. A café that was Cloud and is now Kiosk still has a Supabase URL in
        // storage until the mode switch clears it — and even if clearing failed, the mode alone must
        // decide. Otherwise a Kiosk till would offer a Cloud button that signs into a café it left.
        saveCloud()
        config.setOperatingMode(OperatingMode.KIOSK)

        assertFalse(
            "a mode the device no longer runs must never be presented as ready",
            config.isModeConfigured(OperatingMode.CLOUD),
        )
    }

    @Test
    fun switchingModeMovesTheUnlockedButtonAcross() {
        // The mode is persisted only by SetupViewModel.save(), so a stored mode means a completed
        // save. The café name deliberately carries across — it names the café, not the topology —
        // so a Cloud café that becomes a Kiosk is immediately a configured Kiosk, and the unlocked
        // button simply moves.
        saveCloud()
        config.setOperatingMode(OperatingMode.KIOSK)

        assertTrue(config.isModeConfigured(OperatingMode.KIOSK))
        assertFalse("and the mode it left must lock", config.isModeConfigured(OperatingMode.CLOUD))
    }

    @Test
    fun switchingToAModeWhoseFieldsAreBlankUnlocksNothing() {
        // The other order: a device with no café name yet cannot unlock anything by switching mode.
        config.setOperatingMode(OperatingMode.KIOSK)
        config.save(supabaseUrl = "", supabaseAnonKey = "", websiteUrl = "", cafeName = "")

        OperatingMode.entries.forEach {
            assertFalse("$it must stay locked", config.isModeConfigured(it))
        }
    }
}
