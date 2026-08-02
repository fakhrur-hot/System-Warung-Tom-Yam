package com.razstudio.pos.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * In LAN Mode, who serves whom is decided by `lan_server_url` — not by the stored role.
 *
 * ## The bug this pins
 *
 * `provideBackendGateway` is `@Singleton`: it resolves once, the first time anything injects a
 * `BackendGateway`, and that answer is kept for the life of the process. It used to branch on
 * `secureStorage.getRole() == ADMIN`.
 *
 * "Host this café" sets the role and opens the till — but `RealtimeService` has usually already
 * pulled the gateway by then, with the role still unset. So the host resolved to the *remote*
 * client, pointed at a LAN server that does not exist, and the owner's own till told them:
 *
 * > "Can't reach the admin device. Check it is switched on, its hotspot is running…"
 *
 * about the phone in their hand. The message was accurate for a Client and nonsense for a host, and
 * nothing on screen distinguished the two.
 *
 * `lan_server_url` has no such race. A Client acquires it by scanning the host's pairing QR or by
 * auto-discovery; a host never has one. It is written before the gateway can matter and does not
 * change underneath it.
 */
@RunWith(RobolectricTestRunner::class)
class LanBackendChoiceTest {

    private lateinit var config: AppConfigStore
    private lateinit var modes: ModeRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("lan_backend_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        config = AppConfigStore(ctx, prefs)
        modes = ModeRepository(config)
    }

    /** The real predicate, not a copy of it — a mirrored rule is one that can drift. */
    private fun servesItself(): Boolean = modes.isLanHost()

    @Test
    fun aHostServesItself() {
        // Nothing scanned, nothing discovered: this device is the café.
        config.setOperatingMode(OperatingMode.LAN)
        modes.setMode(OperatingMode.LAN)
        assertTrue("a host must use the in-process backend", servesItself())
    }

    @Test
    fun aClientTalksToTheHost() {
        config.setOperatingMode(OperatingMode.LAN)
        modes.setMode(OperatingMode.LAN)
        config.setLanServerUrl("http://192.168.43.1:8765")
        assertFalse("a client must keep speaking HTTP", servesItself())
    }

    @Test
    fun theAnswerDoesNotDependOnWhenItIsAsked() {
        // The whole point. A role write can land after the singleton resolves; a config write that
        // happened during pairing cannot.
        config.setOperatingMode(OperatingMode.LAN)
        modes.setMode(OperatingMode.LAN)
        assertTrue(servesItself())
        assertTrue("still true on a second read", servesItself())
    }

    @Test
    fun cloudAndKioskAreNeverLanHosts() {
        // `isLanHost` is asked in three places, one of which is the base-URL decision for every HTTP
        // call the app makes. A Cloud cafe answering true here would throw on every request.
        listOf(OperatingMode.CLOUD, OperatingMode.KIOSK).forEach {
            config.setOperatingMode(it)
            modes.setMode(it)
            assertFalse("$it is not a LAN host", servesItself())
        }
    }

    @Test
    fun aClientPromotedToHostStopsPointingAtItsOldServer() {
        // `SetupViewModel.beginHostingLocally` clears the URL for exactly this case: a spare staff
        // phone pressed into service as the counter device would otherwise try to forward its own
        // café to a host that may no longer exist.
        config.setOperatingMode(OperatingMode.LAN)
        modes.setMode(OperatingMode.LAN)
        config.setLanServerUrl("http://192.168.43.1:8765")
        assertFalse(servesItself())

        config.setLanServerUrl("")
        assertTrue("promotion must be complete, not partial", servesItself())
    }
}
