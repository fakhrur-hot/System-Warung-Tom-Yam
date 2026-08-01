package com.razstudio.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 8.6 — no endpoint can hand a café owner a bare status code.
 *
 * The fallback used to be `ApiResult.Error("UNKNOWN", "Server error: ${response.code}")`, repeated at
 * 41 call sites, i.e. on **every** endpoint. Any status nobody had specifically handled arrived at
 * the counter as a number. That is exactly how the staff-join defect was reported — "error 404
 * straight after scanning" — and the number was true, told nobody anything, and hid a device-id
 * conflation for as long as it took someone to read the source.
 *
 * Two of those sites were worse still: they appended the raw response body, so a server stack trace
 * or an HTML error page would have been shown verbatim.
 *
 * Requirement 8.2 forbids a raw status *as the message*, not any mention of one — support
 * conversations need the number. So every branch below must name a cause and an action, and must
 * carry a code the UI can branch on.
 */
class UnexpectedStatusTest {

    private fun msg(code: Int) = ApiClient.unexpectedStatus(code).message
    private fun code(code: Int) = ApiClient.unexpectedStatus(code).code

    // ── The four join outcomes get distinguishable codes ──────────────────────────────────────────

    @Test
    fun the404ThatStartedThisIsNamedAndExplained() {
        assertEquals("NOT_FOUND", code(404))
        val m = msg(404)
        assertTrue("must mention the address, which is what was actually wrong", m.contains("address"))
        assertTrue("still names the status for support", m.contains("404"))
    }

    @Test
    fun serverFaultsAndClientRejectionsAreDifferentCodes() {
        // These send the operator in opposite directions: wait and retry, versus re-register.
        assertEquals("SERVER_ERROR", code(500))
        assertEquals("REJECTED", code(400))
        assertNotEquals(code(500), code(400))
    }

    @Test
    fun transientConditionsAreCalledOutSeparately() {
        assertEquals("TIMEOUT", code(408))
        assertEquals("TIMEOUT", code(504))
        assertEquals("RATE_LIMITED", code(429))
        assertEquals("UNAVAILABLE", code(502))
        assertEquals("UNAVAILABLE", code(503))
    }

    @Test
    fun everyBranchIsReachableAndNothingFallsThroughToUnknown() {
        listOf(400, 404, 408, 418, 429, 500, 502, 503, 504, 599, 200, 302).forEach {
            assertNotEquals("status $it must not resolve to the old catch-all", "UNKNOWN", code(it))
        }
    }

    // ── The property, stated directly ─────────────────────────────────────────────────────────────

    @Test
    fun noMessageIsEverJustANumber() {
        (listOf(100, 204, 301, 400, 401, 404, 408, 418, 429, 451, 500, 502, 503, 504, 599, 999)).forEach { c ->
            val m = msg(c)
            assertFalse("bare number for $c", m.trim().matches(Regex("^\\d{3}$")))
            assertFalse("the old shape resurfaced for $c", m.startsWith("Server error:"))
            assertTrue("must be a sentence, not a token ($c): $m", m.length > 30 && m.contains(" "))
        }
    }

    @Test
    fun everyMessageTellsTheOperatorWhatToDo() {
        // A message that only describes the fault leaves the café owner holding a phone with no
        // next move. Each branch names an action.
        val actionable = listOf("try again", "re-register", "updating", "wait", "attention", "shortly", "check")
        listOf(400, 404, 408, 429, 500, 502, 599, 999).forEach { c ->
            val m = msg(c).lowercase()
            assertTrue(
                "no action offered for $c: ${msg(c)}",
                actionable.any { m.contains(it) },
            )
        }
    }

    @Test
    fun aStatusOutsideEveryRangeStillProducesSomethingUsable() {
        // Defensive: a proxy or a broken gateway can return anything at all.
        assertEquals("UNEXPECTED", code(999))
        assertTrue(msg(999).contains("999"))
        assertTrue(msg(0).isNotBlank())
    }

    @Test
    fun theResponseBodyIsNeverEchoed() {
        // Two sites used to append `$responseBody`, so an HTML error page or a stack trace could
        // reach the screen verbatim. Nothing in these messages is derived from a response.
        listOf(400, 404, 500).forEach {
            val m = msg(it)
            assertFalse("no markup may appear", m.contains("<"))
            assertFalse("no JSON body may appear", m.contains("{"))
        }
    }
}
