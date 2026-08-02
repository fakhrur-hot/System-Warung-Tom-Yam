package com.razstudio.pos.data.google

import com.razstudio.pos.data.OperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 23.10 — **a partial or corrupt payload restores nothing rather than half a café.**
 *
 * This is the property the whole restore path rests on. A payload that parses "as far as it can"
 * produces the worst outcome available: `AppConfigStore` reports the mode configured, the home
 * screen unlocks its button, the owner puts the device on the counter, and the failure surfaces at
 * the first order instead of at startup where somebody was still watching.
 *
 * So [CafeConfigPayload.parse] is all-or-nothing, and these pin every way it could stop being that.
 *
 * Robolectric because `org.json` is an Android framework class — the JVM stub throws.
 */
@RunWith(RobolectricTestRunner::class)
class CafeConfigPayloadTest {

    private fun cloud() = CafeConfigPayload(
        mode = OperatingMode.CLOUD,
        cafeName = "Tani Tom Yam",
        supabaseUrl = "https://proj.supabase.co",
        supabaseAnonKey = "sb_publishable_abc",
        websiteUrl = "https://tani.pages.dev",
        ownerRecoveryQr = "https://tani.pages.dev/join?recover=tok",
        savedAtMs = 1_785_000_000_000L,
        savedByDevice = "Pixel 7",
    )

    // ── It survives the round trip, or it is not a backup ────────────────────────────────────────

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        val back = CafeConfigPayload.parse(cloud().toJson())
        assertEquals(cloud(), back)
    }

    @Test
    fun theRecoveryKeyIsCarried() {
        // The one field that makes this a café key and not a preferences blob. If it silently
        // dropped, a restored owner would be locked out of their own café with no error anywhere.
        val back = CafeConfigPayload.parse(cloud().toJson())
        assertEquals("https://tani.pages.dev/join?recover=tok", back?.ownerRecoveryQr)
    }

    // ── Nothing half-formed gets through ─────────────────────────────────────────────────────────

    @Test
    fun malformedJsonIsRejectedRatherThanThrowing() {
        // The caller is a restore path on an owner's phone. A crash here is a café that will not open.
        listOf("", "   ", "not json", "{", "[]", "{\"mode\":}").forEach {
            assertNull("must reject: $it", CafeConfigPayload.parse(it))
        }
    }

    @Test
    fun aCloudPayloadWithoutABackendIsRejected() {
        // Half a café: this would restore, report configured, and fail at the first request.
        assertNull(CafeConfigPayload.parse(cloud().copy(supabaseUrl = "").toJson()))
        assertNull(CafeConfigPayload.parse(cloud().copy(supabaseAnonKey = "").toJson()))
    }

    @Test
    fun aPayloadWithoutACafeNameIsRejectedInEveryMode() {
        // `isModeConfigured` requires the name in all three modes, so a nameless payload would
        // restore to a device that stays locked — the exact "looks signed in, cannot host" failure.
        OperatingMode.entries.forEach { mode ->
            val p = cloud().copy(mode = mode, cafeName = "")
            assertNull("$mode must be rejected without a name", CafeConfigPayload.parse(p.toJson()))
        }
    }

    @Test
    fun anUnknownModeIsRejected() {
        val json = cloud().toJson().replace("\"CLOUD\"", "\"SATELLITE\"")
        assertNull(CafeConfigPayload.parse(json))
    }

    @Test
    fun aPayloadFromANewerVersionIsRejectedRatherThanGuessedAt() {
        // A future version may have made a field mandatory that this build reads as blank. Parsing
        // it as v1 would produce something that looks complete and is not.
        val json = cloud().toJson().replace("\"version\":1", "\"version\":2")
        assertNull(CafeConfigPayload.parse(json))
    }

    // ── Off-cloud stores no backend, and must not be judged as if it did ─────────────────────────

    @Test
    fun lanAndKioskNeedOnlyTheirName() {
        listOf(OperatingMode.LAN, OperatingMode.KIOSK).forEach { mode ->
            val p = CafeConfigPayload(
                mode = mode,
                cafeName = "Kopitiam",
                supabaseUrl = "",
                supabaseAnonKey = "",
                websiteUrl = "",
                ownerRecoveryQr = "",
            )
            val back = CafeConfigPayload.parse(p.toJson())
            assertNotNull("$mode must round-trip with no backend", back)
            assertEquals(mode, back?.mode)
        }
    }

    // ── Forward compatibility in the direction that is safe ──────────────────────────────────────

    @Test
    fun unknownFieldsFromANewerBuildAreIgnoredNotRejected() {
        // An owner who upgrades one device and not the other must still restore on both. Ignoring
        // additions is safe; ignoring a *required* field is not, which is what the version gate is for.
        val json = cloud().toJson().dropLast(1) + ",\"future_field\":\"whatever\"}"
        val back = CafeConfigPayload.parse(json)
        assertNotNull(back)
        assertEquals("Tani Tom Yam", back?.cafeName)
    }

    @Test
    fun theOptionalFieldsAreGenuinelyOptional() {
        // Cloudflare names and the recovery QR are informational or unavailable on some devices;
        // requiring them would refuse to save a café that is otherwise complete.
        val lean = cloud().copy(ownerRecoveryQr = "", cloudflareDomain = "", cloudflareProject = "")
        assertNotNull(CafeConfigPayload.parse(lean.toJson()))
    }

    @Test
    fun whitespaceOnlyValuesCountAsMissing() {
        // A field padded with spaces reads as present to a naive check and is not.
        val json = cloud().toJson().replace("\"Tani Tom Yam\"", "\"   \"")
        assertNull(CafeConfigPayload.parse(json))
    }
}

/**
 * Task 23.4 — **LAN and Kiosk never attempt sign-in** (Requirements 15.9, 11.1).
 *
 * Stated as a property on the mode itself, because it is decided in three places — the start
 * destination, the sign-in screen and the save card — and each one reading the mode differently is
 * how a LAN café ends up waiting on a network call that `NoInternetGuard` was always going to refuse.
 */
class GoogleSignInModeGateTest {

    @Test
    fun onlyCloudEverAttemptsSignIn() {
        assertTrue(OperatingMode.CLOUD.attemptsGoogleSignIn())
    }

    @Test
    fun theOffCloudModesDoNot() {
        listOf(OperatingMode.LAN, OperatingMode.KIOSK).forEach {
            assertTrue("$it must never attempt sign-in", !it.attemptsGoogleSignIn())
        }
    }
}
