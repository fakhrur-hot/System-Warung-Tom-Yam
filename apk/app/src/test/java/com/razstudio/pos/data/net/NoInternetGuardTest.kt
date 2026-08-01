package com.razstudio.pos.data.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.UnknownHostException

/**
 * Task 18.1 — **Property 3: no internet traffic originates in LAN or Kiosk Mode**
 * (Validates Requirements 11.1, 11.2, 11.2.1).
 *
 * The property this guards is absolute and easy to breach by accident: one HTTP client somebody
 * forgot to wire, one leftover `https://` URL on a menu row. A café that believes it is offline —
 * because it chose Kiosk Mode, or because it is on a hotspot with no upstream — must not be quietly
 * reaching a server somewhere.
 *
 * These tests use real hostnames that resolve to fixed literals without a network round trip
 * (`localhost`, and IP literals, which `Dns.SYSTEM` returns as-is), so nothing here depends on DNS
 * being available in the test environment.
 */
@RunWith(RobolectricTestRunner::class)
class NoInternetGuardTest {

    private lateinit var config: AppConfigStore
    private lateinit var guard: NoInternetGuard

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("guard_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        config = AppConfigStore(context, prefs)
        guard = NoInternetGuard(ModeRepository(config))
    }

    private fun mode(m: OperatingMode) {
        config.setOperatingMode(m)
        guard = NoInternetGuard(ModeRepository(config))
    }

    // ── Off-cloud: local is allowed, the internet is not ──────────────────────────────────────────

    @Test
    fun aLanAddressIsAllowedInLanMode() {
        mode(OperatingMode.LAN)
        // The Server Device on a typical Android hotspot subnet.
        assertEquals(1, guard.lookup("192.168.43.1").size)
    }

    @Test
    fun everyPrivateRangeIsTreatedAsLocal() {
        mode(OperatingMode.KIOSK)
        // RFC 1918 in full, plus loopback and link-local — a café router could hand out any of them.
        listOf("10.0.0.5", "172.16.4.9", "192.168.1.20", "127.0.0.1", "169.254.10.10").forEach {
            assertEquals("$it must be treated as local", 1, guard.lookup(it).size)
        }
    }

    @Test
    fun apublicAddressIsBlockedInLanMode() {
        mode(OperatingMode.LAN)
        val error = runCatching { guard.lookup("8.8.8.8") }.exceptionOrNull()

        assertTrue("must be an UnknownHostException so callers handle it as a network failure",
            error is UnknownHostException)
        assertTrue(
            "the message must name the guard, or this is undiagnosable in the field",
            error!!.message!!.contains("NoInternetGuard"),
        )
    }

    @Test
    fun apublicAddressIsBlockedInKioskMode() {
        mode(OperatingMode.KIOSK)
        assertTrue(runCatching { guard.lookup("1.1.1.1") }.exceptionOrNull() is UnknownHostException)
    }

    // ── Cloud Mode is untouched ───────────────────────────────────────────────────────────────────

    @Test
    fun aPublicAddressIsAllowedInCloudMode() {
        mode(OperatingMode.CLOUD)
        // Behaviour-preserving for every existing café: the guard has no opinions on cloud.
        assertEquals(1, guard.lookup("8.8.8.8").size)
    }

    @Test
    fun cloudModeShortCircuitsBeforeInspectingAnything() {
        mode(OperatingMode.CLOUD)
        listOf("8.8.8.8", "1.1.1.1", "127.0.0.1").forEach {
            assertEquals("$it must pass untouched in Cloud Mode", 1, guard.lookup(it).size)
        }
    }

    // ── The failure this task exists to prevent ───────────────────────────────────────────────────

    @Test
    fun aLeftoverCloudImageHostWouldBeBlockedOffCloud() {
        // The concrete scenario in Requirement 11.2.1: a café switches to LAN, and its menu rows
        // still hold https://<project>.supabase.co/storage/... URLs. Coil fetches those on the next
        // menu open. Resolving a public literal stands in for that host without needing real DNS.
        mode(OperatingMode.LAN)
        assertTrue(
            "a leftover cloud image URL must not leave the café network",
            runCatching { guard.lookup("93.184.216.34") }.exceptionOrNull() is UnknownHostException,
        )
    }

    @Test
    fun switchingModeChangesTheAnswerWithoutRebuildingClients() {
        // The guard reads the mode per lookup rather than capturing it at construction, so a café
        // that switches topology is protected immediately — not after the next app restart, by which
        // time it has already made the calls the property forbids. That matters because the OkHttp
        // clients hold a single guard instance for the process lifetime.
        //
        // Driven through ModeRepository.setMode, which is the only path production uses (SetupViewModel
        // calls it). Writing to AppConfigStore directly would not update the repository's cached
        // value, and asserting against that would be testing a route no code takes.
        config.setOperatingMode(OperatingMode.CLOUD)
        val repo = ModeRepository(config)
        val shared = NoInternetGuard(repo)

        assertEquals(1, shared.lookup("8.8.8.8").size)

        repo.setMode(OperatingMode.KIOSK)
        assertTrue(
            "the same guard instance must start blocking once the mode changes",
            runCatching { shared.lookup("8.8.8.8") }.exceptionOrNull() is UnknownHostException,
        )
    }

    @Test
    fun theProtectedClientListNamesEveryHttpClientInTheApp() {
        // Documentation with a test behind it: the whole failure mode of 18.1 is a client nobody
        // wired. If one is added, this list should grow with it.
        assertEquals(7, NoInternetGuard.PROTECTED_CLIENTS.size)
        assertTrue(NoInternetGuard.PROTECTED_CLIENTS.contains("Coil ImageLoader"))
        assertTrue(
            "PaymentQrResolver is not in task 18.1's list but is the riskiest client of the five",
            NoInternetGuard.PROTECTED_CLIENTS.contains("PaymentQrResolver"),
        )
        assertTrue(
            "AppConfigFetcher was added later and shipped unguarded — this list is what caught it",
            NoInternetGuard.PROTECTED_CLIENTS.contains("AppConfigFetcher"),
        )
    }
}
