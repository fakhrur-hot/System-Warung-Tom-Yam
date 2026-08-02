package com.razstudio.pos.data.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Wireless AP staff-invite QR carries an address **and** a token.
 *
 * ## The defect
 *
 * The Devices screen encoded `settingsState.invite.url`. In Cloud that is
 * `https://…/join?invite=<token>` — the token is in the URL, so encoding the URL is enough.
 * Off-cloud, `LocalBackend.getInvite` returns `http://<ip>:8765` as a *human-readable caption* and
 * hands the pairing token back separately, so the QR carried an address and nothing else.
 *
 * A staff phone scanning it got a code it could not act on: registration consumes a pairing token,
 * and `OrderingConnectScreen` decodes [PairingQrPayload], not a bare URL. Nothing failed loudly —
 * the QR rendered, the scanner read it, and the join simply did not happen.
 *
 * These pin the shape the scanner actually accepts, and the fact that the old content does not
 * satisfy it.
 */
@RunWith(RobolectricTestRunner::class)
class LanInviteQrTest {

    private val payload = PairingQrPayload(
        host = "192.168.1.6",
        port = PairingQrPayload.PORT,
        pairingToken = "abc123",
    )

    @Test
    fun theQrRoundTripsThroughTheScannersDecoder() {
        val decoded = PairingQrPayload.decode(payload.encode())
        assertNotNull("the scanner must be able to read what the admin screen writes", decoded)
        assertEquals("192.168.1.6", decoded?.host)
        assertEquals(8765, decoded?.port)
        assertEquals("abc123", decoded?.pairingToken)
    }

    @Test
    fun aBareAddressIsNotAValidPairingCode() {
        // Exactly what the Devices screen used to encode off-cloud. It decodes to nothing, which is
        // why a staff phone could scan the QR and still fail to join.
        assertNull(PairingQrPayload.decode("http://192.168.1.6:8765"))
    }

    @Test
    fun aCloudInviteLinkIsNotAPairingCodeEither() {
        // The two topologies genuinely need different QR content; one format cannot serve both.
        assertNull(PairingQrPayload.decode("https://cafe.pages.dev/join?invite=abc123"))
    }

    @Test
    fun theTokenIsActuallyPresentInTheEncodedForm() {
        // Guards the specific regression: an encoding that dropped the token would still produce a
        // scannable QR, and the failure would only appear as a staff phone that "won't connect".
        assertTrue(payload.encode().contains("abc123"))
    }

    @Test
    fun garbageDoesNotThrow() {
        // The scanner feeds this whatever the camera decoded, including QR codes from other apps.
        listOf("", "   ", "not json", "{", "{\"type\":\"something.else\"}").forEach {
            assertNull("must reject: $it", PairingQrPayload.decode(it))
        }
    }

    @Test
    fun thePortHasExactlyOneDefinition() {
        // It used to be written out in four files, each with a "must match" comment. If the QR and
        // the listener ever disagreed, every staff phone would scan a code aimed at a closed port
        // and nothing would say why.
        // LanServer, LocalBackend, LanPairingViewModel and LanServerLocator now all read this
        // constant rather than repeating the literal, so pinning it here pins all of them.
        assertEquals(8765, PairingQrPayload.PORT)
    }
}
