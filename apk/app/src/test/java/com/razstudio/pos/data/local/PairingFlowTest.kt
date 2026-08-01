package com.razstudio.pos.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 5.1 / 5.2 — LAN Mode pairing, at the DAO level (Validates Requirements 5.1, 5.3, 5.4, 5.6).
 *
 * These cover the rules that decide whether a staff phone can take orders at all, and the one that
 * an earlier draft of `LocalBackend.register` got wrong in a way nothing would have reported.
 *
 * ### The bug these exist because of
 *
 * A Client learns its credential exactly once, from the `devices-status` poll that
 * `PendingApprovalScreen` runs while it waits — `PendingApprovalViewModel` reads `apiKey` from that
 * response and hands it to `SecureStorage`. The first implementation minted a credential, stored its
 * hash, and returned `apiKey = null` with the comment "API key is not stored locally". The result:
 * the phone pairs, the admin approves it, the screen advances — and the device holds no credential,
 * so every subsequent request is rejected. There is no error at the point of failure, and the café
 * sees a phone that "just doesn't work".
 *
 * `LocalBackend` itself needs seven collaborators to construct, so these tests exercise the storage
 * contract the fix depends on rather than the class; the delivery-once behaviour is asserted here as
 * the state transition it actually is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PairingFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var devices: PairedDeviceDao
    private lateinit var tokens: PairingTokenDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        devices = db.pairedDeviceDao()
        tokens = db.pairingTokenDao()
    }

    @After
    fun tearDown() = db.close()

    private fun device(
        id: String = "dev-1",
        status: String = "PENDING",
        pending: String? = "cred-abc",
    ) = PairedDevice(
        id = id,
        name = "Pixel 6a",
        model = "Pixel 6a",
        role = "ORDERING",
        status = status,
        credentialHash = "hash-of-$pending",
        lastSeenMs = 1_754_000_000_000L,
        pendingCredential = pending,
    )

    // ── Token validity: expiry and single use (Requirements 5.1, 5.3) ─────────────────────────────

    @Test
    fun aFreshTokenIsValidAndAnExpiredOneIsNot() = runTest {
        val now = 1_000_000L
        tokens.insert(PairingToken(token = "t-live", expiresAtMs = now + 60_000, createdAtMs = now))
        tokens.insert(PairingToken(token = "t-dead", expiresAtMs = now - 1, createdAtMs = now - 60_000))

        assertNotNull("an unexpired token must validate", tokens.getValidToken("t-live", now))
        assertNull("an expired token must not validate", tokens.getValidToken("t-dead", now))
    }

    @Test
    fun aTokenStopsValidatingOnceUsed_soAPhotographedCodeCannotEnrolASecondDevice() = runTest {
        val now = 1_000_000L
        tokens.insert(PairingToken(token = "t-1", expiresAtMs = now + 60_000, createdAtMs = now))
        assertNotNull(tokens.getValidToken("t-1", now))

        tokens.markUsed("t-1", now)

        assertNull(
            "single-use is the whole protection — a code left on screen must not pair a second phone",
            tokens.getValidToken("t-1", now),
        )
    }

    @Test
    fun expiryIsEvaluatedAgainstTheCallersClockNotInsertionTime() = runTest {
        val now = 1_000_000L
        tokens.insert(PairingToken(token = "t-1", expiresAtMs = now + 10_000, createdAtMs = now))

        assertNotNull(tokens.getValidToken("t-1", now + 9_999))
        assertNull("the same token must be dead one millisecond later", tokens.getValidToken("t-1", now + 10_001))
    }

    // ── Regenerate rotates the code without disturbing approved devices (Requirement 5.6) ─────────

    @Test
    fun rotatingTheCodeLeavesApprovedDevicesAlone() = runTest {
        val now = 1_000_000L
        devices.insert(device(id = "approved-1", status = "APPROVED", pending = null))
        tokens.insert(PairingToken(token = "t-old", expiresAtMs = now + 60_000, createdAtMs = now))

        // What regenerateInvite does: drop the live token, mint a replacement.
        tokens.deleteToken("t-old")
        val fresh = tokens.generateToken()

        assertNull("the leaked code must stop working", tokens.getValidToken("t-old", now))
        assertNotNull("a replacement code must be usable", tokens.getValidToken(fresh.token, System.currentTimeMillis()))

        val stillThere = devices.getById("approved-1")
        assertEquals(
            "rotating a pairing code must not knock working staff phones off the system",
            "APPROVED", stillThere?.status,
        )
    }

    // ── Credential delivery: exactly once, only after approval (Requirements 5.3, 5.4) ────────────

    @Test
    fun aPendingDeviceHoldsItsCredentialButHasNotBeenApproved() = runTest {
        devices.insert(device(status = "PENDING"))
        val row = devices.getById("dev-1")!!

        assertEquals("PENDING", row.status)
        assertNotNull("the credential is minted at registration", row.pendingCredential)
    }

    @Test
    fun theCredentialIsClearedWhenDelivered_soItIsHandedOverExactlyOnce() = runTest {
        devices.insert(device(status = "APPROVED", pending = "cred-abc"))

        // What pollDeviceStatus does on the first poll after approval.
        val before = devices.getById("dev-1")!!
        assertEquals("cred-abc", before.pendingCredential)
        devices.update(before.copy(pendingCredential = null))

        val after = devices.getById("dev-1")!!
        assertNull("a second poll must not re-issue the credential", after.pendingCredential)
        assertEquals(
            "the hash must remain as the only record (Requirement 5.4)",
            before.credentialHash, after.credentialHash,
        )
    }

    @Test
    fun rejectingADeviceDropsItsUndeliveredCredential() = runTest {
        devices.insert(device(status = "PENDING", pending = "cred-abc"))

        // What patchDevice REJECT/REVOKE does.
        val row = devices.getById("dev-1")!!
        devices.update(row.copy(status = "REVOKED", pendingCredential = null))

        val after = devices.getById("dev-1")!!
        assertEquals("REVOKED", after.status)
        assertNull(
            "a device refused while still polling must not be able to collect a credential afterwards",
            after.pendingCredential,
        )
    }

    @Test
    fun aRejectedDeviceIsKeptRatherThanDeleted() = runTest {
        devices.insert(device(status = "PENDING"))
        val row = devices.getById("dev-1")!!
        devices.update(row.copy(status = "REVOKED", pendingCredential = null))

        assertNotNull(
            "the row must survive so the admin can see the device was refused — deleting it would " +
                "let the same device re-register into a fresh PENDING state, indistinguishable from " +
                "a first attempt",
            devices.getById("dev-1"),
        )
        assertEquals(1, devices.getAllOnce().size)
    }

    @Test
    fun everyPairedDeviceIsListedForTheDevicesScreen() = runTest {
        devices.insert(device(id = "a", status = "APPROVED", pending = null))
        devices.insert(device(id = "b", status = "PENDING"))
        devices.insert(device(id = "c", status = "REVOKED", pending = null))

        val all = devices.getAllOnce()
        assertEquals(3, all.size)
        assertTrue(
            "revoked devices must still be listed, or an admin cannot tell a refusal from a device " +
                "that never tried to pair",
            all.any { it.status == "REVOKED" },
        )
    }
}
