package com.razstudio.pos.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One physical phone is one device, across any number of sign-outs.
 *
 * ## The bug
 *
 * `clearAll()` wiped every key, `KEY_DEVICE_ID` included, and `getDeviceId()` mints a fresh UUID
 * whenever it finds none. That id is written to `devices.device_identifier`, which is how the
 * backend tells "this handset again" from "a new handset" — so every sign-out enrolled the same
 * phone as a stranger.
 *
 * A café's Devices screen filled with revoked duplicates of one handset:
 *
 *     Infinix X6873  (this device)   Connected
 *     Infinix X6873                  Revoked   02/08 23:29
 *     Infinix X6873                  Revoked   02/08 22:51
 *     Infinix X6873                  Revoked   02/08 22:42
 *
 * The owner cannot tell which row is the phone in their hand, and the count is meaningless for
 * anything that reads it. Identity belongs to the hardware; signing out ends a session.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceIdentityTest {

    private lateinit var storage: SecureStorage

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("device_identity_test", Context.MODE_PRIVATE).edit().clear().commit()
        storage = SecureStorage(ctx)
        storage.clearAll()
    }

    @Test
    fun theSameDeviceKeepsOneIdentityAcrossSignOut() {
        val first = storage.getDeviceId()
        assertNotNull(first)

        storage.clearAll()

        assertEquals(
            "a sign-out must not enrol this phone as a new device",
            first,
            storage.getDeviceId(),
        )
    }

    @Test
    fun repeatedSignOutsDoNotAccumulateIdentities() {
        // Four sign-ins in one evening is exactly how the duplicates were produced.
        val original = storage.getDeviceId()
        repeat(4) { storage.clearAll() }

        assertEquals(original, storage.getDeviceId())
    }

    @Test
    fun theIdIsStableWithoutASignOutToo() {
        assertEquals(storage.getDeviceId(), storage.getDeviceId())
    }

    @Test
    fun credentialsAreStillCleared() {
        // The identity survives; nothing else may. A sign-out that left a session token behind
        // would be a far worse bug than the one being fixed.
        storage.setSessionToken("session-abc")
        storage.setApiKey("api-abc")
        storage.setRole(SecureStorage.Role.ADMIN)
        storage.saveAdminPin("1234")

        storage.clearAll()

        assertNull(storage.getSessionToken())
        assertNull(storage.getApiKey())
        assertNull(storage.getRole())
        assertNull(storage.getAdminPin())
    }

    @Test
    fun theServerAssignedIdIsClearedEvenThoughTheClientOneIsNot() {
        // `devices.id` belongs to a registration, not to the handset. Keeping a stale one would
        // point devices-status and attendance at a row this device no longer owns — the same
        // two-ids confusion that once 404'd staff joins forever.
        storage.setServerDeviceId("server-row-42")
        val clientId = storage.getDeviceId()

        storage.clearAll()

        assertEquals("the client id is the handset", clientId, storage.getDeviceId())
        assertEquals(
            "with no registration, the server id falls back to the client id",
            clientId,
            storage.getServerDeviceId(),
        )
    }
}
