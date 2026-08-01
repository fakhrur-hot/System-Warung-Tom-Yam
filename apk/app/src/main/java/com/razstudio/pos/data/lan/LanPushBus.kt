package com.razstudio.pos.data.lan

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries state changes from [com.razstudio.pos.data.local.LocalBackend] to connected Clients
 * (task 8.6, Requirement 6.5).
 *
 * ### Why a bus and not a direct call
 *
 * `LanServer` already depends on `LocalBackend` — it is the HTTP face of it. Having `LocalBackend`
 * call `LanServer` back would make that a cycle, and would also mean the backend could not be
 * constructed in a test without a running HTTP server. The bus inverts it: the backend emits and
 * does not care whether anything is listening, which is also true in Kiosk Mode, where nothing is.
 *
 * ### Session and message ids
 *
 * [sessionId] is minted once per process. [nextMessageId] is monotonic within it. Together they are
 * what let a Client distinguish a re-delivery from a new event after a Server restart — see
 * [LanPushEnvelope].
 *
 * ### Why `extraBufferCapacity` rather than a rendezvous
 *
 * Emission happens inside order-mutation code paths, which must never block or be suspended by a
 * slow socket. A buffered flow with [kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST] means a
 * stalled Client costs stale pushes, never a stalled payment. Dropping is safe **because the poll
 * fallback exists** (Requirement 6.6) — a dropped push is reconciled within one poll interval, which
 * is exactly the guarantee that justifies not blocking here.
 */
@Singleton
class LanPushBus @Inject constructor() {

    /** Identifies this Server run; regenerated on process start. */
    val sessionId: String = UUID.randomUUID().toString()

    private val counter = AtomicLong(0)

    private val _events = MutableSharedFlow<LanPushEnvelope>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Frames to fan out to connected Clients. No replay: a reconnecting Client asks via lastSeenId. */
    val events: SharedFlow<LanPushEnvelope> = _events.asSharedFlow()

    fun nextMessageId(): Long = counter.incrementAndGet()

    /**
     * Publish a change. Non-suspending and never throws, so a mutation path cannot fail because of
     * the push channel.
     */
    fun publish(delta: JSONObject, nowIso: String) {
        val envelope = LanPushEnvelope(
            type = LanPushEnvelope.Type.STATUS_UPDATE,
            sessionId = sessionId,
            messageId = nextMessageId(),
            timestamp = nowIso,
            delta = delta,
        )
        _events.tryEmit(envelope)
    }
}
