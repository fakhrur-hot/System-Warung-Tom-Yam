package com.razstudio.opsapp.data.api

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide event bus for OPERATOR token revocations.
 *
 * When any [OperatorApiClient] call receives a 401, it emits an [AccessRevocationEvent] here.
 * The Cafe_Profile_Shell's ViewModel collects [revocationEvents] to show "Access revoked for
 * this café" and offer to remove the stale Cafe_Card.
 *
 * Uses [MutableSharedFlow] with replay=0 and extraBufferCapacity=1:
 * - replay=0: observers don't see stale revocations from before they started collecting
 * - extraBufferCapacity=1: the emit from the API client's IO dispatcher won't suspend even if
 *   the UI collector is momentarily busy
 *
 * Requirement 6.2 / Design.md Error Handling table.
 */
@Singleton
class AccessRevocationManager @Inject constructor() {

    private val _revocationEvents = MutableSharedFlow<AccessRevocationEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )

    /** Observe this from the UI layer (ViewModel/Shell) to react to revocations. */
    val revocationEvents: SharedFlow<AccessRevocationEvent> = _revocationEvents.asSharedFlow()

    /**
     * Called by [OperatorApiClient] when a 401 is received.
     * This is a non-suspending tryEmit — safe to call from any coroutine context.
     */
    fun notifyRevoked(cafeId: String, cafeName: String) {
        _revocationEvents.tryEmit(AccessRevocationEvent(cafeId = cafeId, cafeName = cafeName))
    }
}
