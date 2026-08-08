package com.razstudio.pos.ui.viewmodels

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.CafeLocationResponse
import com.razstudio.pos.realtime.OrderingForegroundService
import com.razstudio.pos.ui.util.GpsHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the ordering staff state machine.
 *
 * State machine: LOADING → CAFE_CLOSED ↔ CHECK_IN ↔ ORDERING
 * - LOADING: initial state while fetching café status
 * - CAFE_CLOSED: café is closed, waiting for CAFE_OPEN broadcast
 * - CHECK_IN: café is open, staff needs to check in (GPS validation)
 * - ORDERING: staff checked in, can take orders (placeholder for Task 20)
 */
@HiltViewModel
class OrderingViewModel @Inject constructor(
    application: Application,
    private val apiClient: ApiClient
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OrderingViewModel"
    }

    enum class CafeState {
        LOADING,
        CAFE_CLOSED,
        CHECK_IN,
        ORDERING
    }

    private val _state = MutableStateFlow(CafeState.LOADING)
    val state: StateFlow<CafeState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isCheckingIn = MutableStateFlow(false)
    val isCheckingIn: StateFlow<Boolean> = _isCheckingIn.asStateFlow()

    private var cafeLocation: CafeLocationResponse? = null

    private val cafeStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                OrderingForegroundService.ACTION_CAFE_OPEN -> {
                    Log.i(TAG, "Received CAFE_OPEN broadcast")
                    // "The café is open" only lifts the CLOSED state — it must never clobber a
                    // screen the staff member is working on.
                    //
                    // This used to assign CHECK_IN unconditionally, and the service re-announces
                    // CAFE_OPEN periodically rather than only on the closed→open edge. So a waiter
                    // taking an order got thrown off the table grid a couple of minutes later,
                    // mid-service, onto what looks like a login screen — which is why this was
                    // reported as the app signing itself out, the one thing it had not done.
                    //
                    // LOADING is deliberately excluded: fetchInitialState() owns that transition,
                    // and racing it here would fight the launch path.
                    if (_state.value == CafeState.CAFE_CLOSED) {
                        _state.value = CafeState.ORDERING
                    }
                    _errorMessage.value = null
                }
                OrderingForegroundService.ACTION_CAFE_CLOSED -> {
                    Log.i(TAG, "Received CAFE_CLOSED broadcast")
                    _state.value = CafeState.CAFE_CLOSED
                    _errorMessage.value = null
                }
                OrderingForegroundService.ACTION_FORCE_CHECKOUT -> {
                    Log.i(TAG, "Received FORCE_CHECKOUT broadcast")
                    _state.value = CafeState.CHECK_IN
                    _errorMessage.value = "Admin forced check-out"
                }
            }
        }
    }

    init {
        // Register broadcast receiver for cafe status events
        val filter = IntentFilter().apply {
            addAction(OrderingForegroundService.ACTION_CAFE_OPEN)
            addAction(OrderingForegroundService.ACTION_CAFE_CLOSED)
            addAction(OrderingForegroundService.ACTION_FORCE_CHECKOUT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(cafeStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiverPreTiramisu(application, cafeStatusReceiver, filter)
        }

        // Initial state: assume café is open (CHECK_IN) — user can check in
        // The foreground service will broadcast actual state shortly
        fetchInitialState()
    }

    /**
     * Lint false positive on the call site (UnspecifiedRegisterReceiverFlag): the caller's
     * SDK_INT branch already uses the 3-arg RECEIVER_NOT_EXPORTED overload on API 33+, where the
     * flag is actually required — this 2-arg overload only ever runs below that, where the flag
     * parameter doesn't exist. Lint can't trace the branch, so this is isolated into its own
     * annotated function rather than suppressed inline (Kotlin can't @SuppressLint a bare
     * statement).
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiverPreTiramisu(
        application: Application,
        receiver: BroadcastReceiver,
        filter: IntentFilter
    ) {
        application.registerReceiver(receiver, filter)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(cafeStatusReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receiver", e)
        }
    }

    /**
     * Fetch initial café state and land on the ordering screen.
     *
     * ## Why there is no GPS clock-in gate here any more
     *
     * An ordering device used to open on a "Ready to Check In" screen and stay there until a staff
     * member passed a GPS proximity check. That was one tap between a waiter and the table grid on
     * every launch, and every time anything reset the state machine — and because the gate looks
     * like a login screen, landing on it read as "the app signed me out" even though the session was
     * untouched.
     *
     * These devices are already bound to one café: they were paired to it, approved by an admin, and
     * carry its credentials. Asking them to prove they are at the café they are permanently
     * installed in was verifying something already established. So the ordering surface now opens
     * where the work is.
     *
     * **This means attendance rows are no longer written by ordering devices.** Nothing else posts
     * them — the CHECK_IN event only ever came from that gate — so if attendance is wanted back it
     * needs a deliberate replacement, not a revert of this line. [CafeState.CHECK_IN] is kept and is
     * still reachable, but now only via an admin's explicit FORCE_CHECKOUT.
     *
     * The café location is still fetched: it confirms connectivity and [cafeLocation] is used
     * elsewhere. A failure is no longer a reason to hold the screen — an ordering device that cannot
     * reach the backend still needs its table grid, which Room can serve offline.
     */
    private fun fetchInitialState() {
        viewModelScope.launch {
            // Demo Mode: there is no café-open broadcast or GPS geofence to clock into — drop staff
            // straight onto the ordering screen, which reads the shared seeded menu/tables from Room.
            if (com.razstudio.pos.data.demo.DemoSession.active) {
                _state.value = CafeState.ORDERING
                return@launch
            }

            // Try to get café location to confirm connectivity
            val client = apiClient

            when (val result = client.getCafeLocation()) {
                is ApiResult.Success -> cafeLocation = result.data
                is ApiResult.Error ->
                    Log.w(TAG, "Failed to fetch café location: ${result.message}")
                is ApiResult.NetworkError ->
                    Log.w(TAG, "Network error fetching café location: ${result.message}")
            }

            // Open on the table grid either way. The foreground service still broadcasts
            // CAFE_CLOSED if the café is not trading, which moves us off this state.
            _state.value = CafeState.ORDERING
        }
    }

    /**
     * Attempt GPS check-in:
     * 1. Get fresh GPS fix
     * 2. Fetch café location (if not cached)
     * 3. Validate distance within radius
     * 4. Call POST /api/attendance CHECK_IN
     * 5. Transition to ORDERING on success
     */
    fun checkIn() {
        val client = apiClient

        _isCheckingIn.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                // 1. Get GPS fix
                // The fix has to be judged against the radius it will be tested with, so the cafe
                // location is fetched BEFORE locating rather than after. Previously the order was
                // reversed and any fix at all was accepted.

                // 1. Fetch café location if not cached
                if (cafeLocation == null) {
                    when (val locResult = client.getCafeLocation()) {
                        is ApiResult.Success -> cafeLocation = locResult.data
                        is ApiResult.Error -> {
                            _errorMessage.value = "Cannot verify café location. Try again."
                            _isCheckingIn.value = false
                            return@launch
                        }
                        is ApiResult.NetworkError -> {
                            _errorMessage.value = "No network connection. Please check your internet."
                            _isCheckingIn.value = false
                            return@launch
                        }
                    }
                }

                val cafe = cafeLocation!!

                // 2. Get a fix good enough to decide the question, and say so when it is not.
                //
                // Each refusal names the actual obstacle. "8616m away" sent staff hunting for a bug
                // in the app; "your phone's position is 3 hours old" sends them to a window, which
                // is the thing that actually fixes it.
                val location = when (val fix = GpsHelper.locate(getApplication(), cafe.radiusMeters)) {
                    is GpsHelper.Fix.Usable -> fix.location
                    is GpsHelper.Fix.Stale -> {
                        _errorMessage.value =
                            "Your phone's last position is ${fix.ageMs / 60_000} minutes old, so it " +
                                "cannot be checked against the cafe. Step outside or near a window " +
                                "for a moment, then try again."
                        _isCheckingIn.value = false
                        return@launch
                    }
                    is GpsHelper.Fix.TooImprecise -> {
                        _errorMessage.value =
                            "Your phone can only place itself to within ${fix.accuracyMeters.toInt()}m, " +
                                "which is wider than the cafe's ${cafe.radiusMeters}m area. Step " +
                                "outside for a better GPS signal, then try again."
                        _isCheckingIn.value = false
                        return@launch
                    }
                    GpsHelper.Fix.NoPermission -> {
                        _errorMessage.value = "Location permission is required to check in."
                        _isCheckingIn.value = false
                        return@launch
                    }
                    GpsHelper.Fix.Unavailable -> {
                        _errorMessage.value =
                            "Unable to get GPS location. Please ensure location is enabled."
                        _isCheckingIn.value = false
                        return@launch
                    }
                }

                // 3. Validate distance
                val withinRadius = GpsHelper.isWithinRadius(
                    staffLat = location.latitude,
                    staffLng = location.longitude,
                    cafeLat = cafe.latitude,
                    cafeLng = cafe.longitude,
                    radiusMeters = cafe.radiusMeters
                )

                if (!withinRadius) {
                    val distance = GpsHelper.computeDistance(
                        location.latitude, location.longitude,
                        cafe.latitude, cafe.longitude
                    )
                    _errorMessage.value = "You're too far from the café (${distance.toInt()}m away). " +
                            "Please move within ${cafe.radiusMeters}m to check in."
                    _isCheckingIn.value = false
                    return@launch
                }

                // 4. POST attendance
                when (val result = client.postAttendance(
                    event = "CHECK_IN",
                    lat = location.latitude,
                    lng = location.longitude
                )) {
                    is ApiResult.Success -> {
                        Log.i(TAG, "Check-in successful")
                        _state.value = CafeState.ORDERING
                        _errorMessage.value = null
                    }
                    is ApiResult.Error -> {
                        _errorMessage.value = when (result.code) {
                            "OUTSIDE_RADIUS" -> "Server says you're outside the café radius. Move closer and try again."
                            else -> "Check-in failed: ${result.message}"
                        }
                    }
                    is ApiResult.NetworkError -> {
                        _errorMessage.value = "Network error. Please check your connection."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Check-in error", e)
                _errorMessage.value = "Unexpected error during check-in."
            } finally {
                _isCheckingIn.value = false
            }
        }
    }

    /**
     * Check out: call POST /api/attendance CHECK_OUT → transition to CHECK_IN or CAFE_CLOSED.
     */
    fun checkOut() {
        val client = apiClient

        viewModelScope.launch {
            try {
                val location = GpsHelper.getLastLocation(getApplication())
                val lat = location?.latitude ?: 0.0
                val lng = location?.longitude ?: 0.0

                when (val result = client.postAttendance(
                    event = "CHECK_OUT",
                    lat = lat,
                    lng = lng
                )) {
                    is ApiResult.Success -> {
                        Log.i(TAG, "Check-out successful")
                        _state.value = CafeState.CHECK_IN
                        _errorMessage.value = null
                    }
                    is ApiResult.Error -> {
                        _errorMessage.value = "Check-out failed: ${result.message}"
                    }
                    is ApiResult.NetworkError -> {
                        _errorMessage.value = "Network error during check-out."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Check-out error", e)
                _errorMessage.value = "Unexpected error during check-out."
            }
        }
    }

    /** Clear the current error message. */
    fun clearError() {
        _errorMessage.value = null
    }
}
