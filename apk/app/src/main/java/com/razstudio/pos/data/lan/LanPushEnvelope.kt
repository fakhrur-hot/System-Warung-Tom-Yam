package com.razstudio.pos.data.lan

import org.json.JSONObject

/**
 * The wire format for LAN push (task 8.5, Requirement 6.7).
 *
 * One envelope type in both directions, because Server and Client are the same codebase and a single
 * parser that both sides share cannot drift the way two mirrored ones do.
 *
 * ### Why each field is here
 *
 * - [messageId] — monotonic within a [sessionId]. This is what lets the Client tell a **re-delivery**
 *   from a **new event**, which Requirement 6.4 needs: an order arriving by both push and poll must
 *   print once. Without an id the Client can only compare payloads, and two genuinely separate
 *   status changes to the same order look identical.
 * - [sessionId] — regenerated every time the Server starts. A Client that reconnects and sees a new
 *   session knows [messageId] restarted from zero, so "id 5 again" is a fresh event rather than a
 *   duplicate. Without it, a Server restart makes every subsequent push look like a replay.
 * - [delta] — only what changed. Not an optimisation at this scale; it is what makes the payload
 *   self-describing enough for the Client to apply it without re-fetching, which is the entire
 *   latency win.
 * - [lastSeenId] — on a [Type.STATUS_REQUEST], the highest id the Client has processed, so the Server
 *   can replay only what it missed after a disconnect.
 */
data class LanPushEnvelope(
    val type: Type,
    val sessionId: String,
    val messageId: Long,
    val timestamp: String,
    val delta: JSONObject? = null,
    val ackFor: Long? = null,
    val lastSeenId: Long? = null,
) {

    enum class Type {
        /** Server → Client: something changed; [delta] says what. */
        STATUS_UPDATE,

        /** Client → Server: [ackFor] was received and applied. */
        ACK,

        /** Client → Server: I have [lastSeenId]; send me anything after it. */
        STATUS_REQUEST,

        /** Anything unrecognised. Carried rather than thrown — see [decode]. */
        UNKNOWN,
        ;

        companion object {
            fun from(raw: String?): Type = entries.firstOrNull { it.name == raw } ?: UNKNOWN
        }
    }

    fun encode(): String = JSONObject().apply {
        put("type", type.name)
        put("sessionId", sessionId)
        put("messageId", messageId)
        put("timestamp", timestamp)
        delta?.let { put("delta", it) }
        ackFor?.let { put("ackFor", it) }
        lastSeenId?.let { put("lastSeenId", it) }
    }.toString()

    companion object {
        /**
         * Parse a frame, or null if it is not one of ours.
         *
         * An unrecognised [Type] decodes to [Type.UNKNOWN] rather than null, deliberately: a Client
         * meeting a message type from a newer Server should ignore that one frame and keep the
         * socket, not treat the whole connection as broken. Only structurally invalid JSON is null.
         */
        fun decode(raw: String): LanPushEnvelope? = runCatching {
            val o = JSONObject(raw)
            LanPushEnvelope(
                type = Type.from(o.optString("type")),
                sessionId = o.optString("sessionId"),
                messageId = o.optLong("messageId", -1L),
                timestamp = o.optString("timestamp"),
                delta = o.optJSONObject("delta"),
                ackFor = if (o.has("ackFor")) o.optLong("ackFor") else null,
                lastSeenId = if (o.has("lastSeenId")) o.optLong("lastSeenId") else null,
            )
        }.getOrNull()

        /**
         * The delta for an order that changed.
         *
         * Carries the order id plus the fields a Client acts on. `orderId` is always present because
         * it is the de-duplication key — the Client's existing `printedKitchenIds` /
         * `notifiedItemIds` sets are keyed by it, which is how a pushed order and the same order
         * arriving later by poll collapse to one print (Requirement 6.4).
         */
        fun orderDelta(
            orderId: String,
            status: String,
            tableId: String? = null,
            total: Double? = null,
        ): JSONObject = JSONObject().apply {
            put("orderId", orderId)
            put("status", status)
            tableId?.let { put("tableId", it) }
            total?.let { put("total", it) }
        }
    }
}
