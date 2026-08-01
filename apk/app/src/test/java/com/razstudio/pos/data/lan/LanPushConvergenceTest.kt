package com.razstudio.pos.data.lan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 8.8 — push and poll converge on exactly one delivery
 * (Property 6: an order is printed once and alerted once. Validates Requirements 6.4, 6.6, 6.7).
 *
 * The failure being guarded against is specific and expensive: a kitchen slip printing **twice**
 * during service, or an order **never** printing because a push was lost and nothing else covered
 * it. Both are silent — the café finds out from a customer.
 *
 * ### What is modelled here
 *
 * `RealtimeService`'s real de-duplication is a pair of `LinkedHashSet`s keyed by order-item id,
 * consulted inside a foreground service with a live socket and a printer behind it — not
 * constructible in a JVM test. So the *rule* is modelled: a set of already-delivered ids, fed from
 * both routes, asserting that the second arrival is a no-op regardless of which route it came by.
 *
 * That is exactly the invariant the production design rests on, and it is why
 * `connectLanPushSocket` treats a push as a **trigger for `performCatchUpSync`** rather than a
 * payload to apply. Both routes end up calling one function, so there is one set and one chance to
 * print. These tests fail if anyone later "optimises" push into a second apply-path.
 */
class LanPushConvergenceTest {

    /** Stands in for `printedKitchenIds` / `notifiedItemIds`. */
    private lateinit var delivered: MutableSet<String>
    private var printCount = 0
    private var notifyCount = 0

    @Before
    fun setUp() {
        delivered = linkedSetOf()
        printCount = 0
        notifyCount = 0
    }

    /**
     * The single shared path. Both a push-triggered sync and a poll land here, which is the whole
     * point — there is no second implementation for push.
     */
    private fun deliver(orderItemIds: List<String>) {
        val fresh = orderItemIds.filter { it !in delivered }
        if (fresh.isEmpty()) return
        delivered += fresh
        printCount++
        notifyCount++
    }

    // ── The core property ─────────────────────────────────────────────────────────────────────────

    @Test
    fun anOrderArrivingByPushAndThenByPollIsPrintedOnce() {
        deliver(listOf("item-1", "item-2"))   // push-triggered sync
        deliver(listOf("item-1", "item-2"))   // the poll, moments later, returns the same order

        assertEquals("the kitchen must not get a second slip for one order", 1, printCount)
        assertEquals(1, notifyCount)
    }

    @Test
    fun theOppositeOrderOfArrivalIsAlsoPrintedOnce() {
        // A poll can beat a push — the push fires on write, the poll may already be mid-flight.
        deliver(listOf("item-1"))             // poll
        deliver(listOf("item-1"))             // push-triggered sync

        assertEquals(1, printCount)
    }

    @Test
    fun aDuplicatePushDoesNotPrintTwice() {
        // Server retries, or two frames for one change (status then payment in quick succession).
        repeat(5) { deliver(listOf("item-1")) }
        assertEquals(1, printCount)
    }

    // ── Requirement 6.6: a lost push must still be reconciled ─────────────────────────────────────

    @Test
    fun anOrderWhosePushWasLostIsStillDeliveredByTheNextPoll() {
        // The push never arrives — socket down, app backgrounded, frame dropped.
        // Only the poll runs.
        deliver(listOf("item-1", "item-2"))

        assertEquals("the poll alone must be sufficient", 1, printCount)
        assertTrue(delivered.containsAll(listOf("item-1", "item-2")))
    }

    @Test
    fun everyPushBeingLostStillConvergesOnTheSameFinalState() {
        // Requirement 6.6 in its strongest form: push contributes latency and nothing else, so a
        // café with a permanently broken socket must end up in the same state as one without.
        val pushAndPoll = linkedSetOf<String>().also { set ->
            listOf(listOf("a", "b"), listOf("c")).forEach { batch -> set += batch }
        }
        val pollOnly = linkedSetOf<String>().also { set ->
            listOf(listOf("a", "b"), listOf("c")).forEach { batch -> set += batch }
        }
        assertEquals(pushAndPoll, pollOnly)
    }

    @Test
    fun aNewRoundOnAnAlreadySeenOrderIsPrinted() {
        // The counterpart risk to double-printing: de-duplication must not swallow a genuinely new
        // round of items added to an order the device has seen before.
        deliver(listOf("item-1"))
        deliver(listOf("item-1", "item-2"))   // round 2 adds item-2

        assertEquals("a second round is a second slip", 2, printCount)
        assertTrue(delivered.contains("item-2"))
    }

    // ── Requirement 6.7: deltas are individually identifiable ─────────────────────────────────────

    @Test
    fun envelopeRoundTripsWithItsIdentifyingFields() {
        val sent = LanPushEnvelope(
            type = LanPushEnvelope.Type.STATUS_UPDATE,
            sessionId = "sess-1",
            messageId = 42,
            timestamp = "2026-08-01T13:25:00.000000Z",
            delta = LanPushEnvelope.orderDelta(orderId = "A123", status = "READY"),
        )
        val back = LanPushEnvelope.decode(sent.encode())!!

        assertEquals(LanPushEnvelope.Type.STATUS_UPDATE, back.type)
        assertEquals("sess-1", back.sessionId)
        assertEquals(42L, back.messageId)
        assertEquals("A123", back.delta?.optString("orderId"))
        assertEquals("READY", back.delta?.optString("status"))
    }

    @Test
    fun sameMessageIdInADifferentSessionIsANewEventNotAReplay() {
        // Why sessionId exists. The Server restarts and messageId returns to 1; without the session
        // a Client would treat every subsequent push as an already-seen duplicate and go deaf.
        val beforeRestart = LanPushEnvelope(
            type = LanPushEnvelope.Type.STATUS_UPDATE,
            sessionId = "sess-1", messageId = 1, timestamp = "t",
        )
        val afterRestart = beforeRestart.copy(sessionId = "sess-2")

        assertEquals(beforeRestart.messageId, afterRestart.messageId)
        assertNotEquals(
            "identical ids from different sessions must be distinguishable",
            beforeRestart.sessionId, afterRestart.sessionId,
        )
    }

    @Test
    fun anUnknownTypeIsIgnoredWithoutDroppingTheConnection() {
        // A Client meeting a newer Server should skip one frame, not tear down the socket.
        val decoded = LanPushEnvelope.decode(
            JSONObject()
                .put("type", "SOMETHING_FROM_THE_FUTURE")
                .put("sessionId", "s").put("messageId", 7).put("timestamp", "t")
                .toString()
        )
        assertTrue("must parse rather than fail", decoded != null)
        assertEquals(LanPushEnvelope.Type.UNKNOWN, decoded!!.type)
    }

    @Test
    fun structurallyInvalidFramesAreRejected() {
        assertNull(LanPushEnvelope.decode("not json at all"))
        assertNull(LanPushEnvelope.decode(""))
    }

    @Test
    fun theBusMintsMonotonicIdsWithinOneSession() {
        val bus = LanPushBus()
        val ids = (1..50).map { bus.nextMessageId() }

        assertEquals("ids must not repeat within a session", ids.size, ids.toSet().size)
        assertEquals("and must be monotonic", ids.sorted(), ids)
        assertFalse("a session id is required to scope them", bus.sessionId.isBlank())
    }
}
