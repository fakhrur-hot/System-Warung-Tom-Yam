package com.razstudio.pos.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide bus for authentication state changes.
 *
 * When the server returns HTTP 401 (expired or revoked session token), the
 * OkHttp interceptor in [ApiClient] emits [AuthEvent.SessionExpired] here.
 * Any UI layer that holds an authenticated session (AdminHomeScreen, etc.)
 * can observe [events] and navigate to re-authentication immediately.
 */
@Singleton
class AuthEventBus @Inject constructor() {

    sealed class AuthEvent {
        /** Admin session token is expired or revoked — re-handshake required. */
        data object SessionExpired : AuthEvent()
    }

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    /** Emit a [AuthEvent.SessionExpired] event (non-suspending, safe from any thread). */
    fun emitSessionExpired() {
        _events.tryEmit(AuthEvent.SessionExpired)
    }
}
