package com.warungtomyam.pos.ui.viewmodels

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.data.ApiClient
import com.warungtomyam.pos.data.ApiResult
import com.warungtomyam.pos.data.CafeLocationResponse
import com.warungtomyam.pos.realtime.OrderingForegroundService
import com.warungtomyam.pos.ui.util.GpsHelper
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
                    _state.value = CafeState.CHECK_IN
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
            application.registerReceiver(cafeStatusReceiver, filter)
        }

        // Initial state: assume café is open (CHECK_IN) — user can check in
        // The foreground service will broadcast actual state shortly
        fetchInitialState()
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
     * Fetch initial café state. If we can reach the backend, determine open/closed.
     * For now, default to CHECK_IN and let the foreground service correct if closed.
     */
    private fun fetchInitialState() {
        viewModelScope.launch {
            // Demo Mode: there is no café-open broadcast or GPS geofence to clock into — drop staff
            // straight onto the ordering screen, which reads the shared seeded menu/tables from Room.
            if (com.warungtomyam.pos.data.demo.DemoSession.active) {
                _state.value = CafeState.ORDERING
                return@launch
            }

            // Try to get café location to confirm connectivity
            val client = apiClient

            when (val result = client.getCafeLocation()) {
                is ApiResult.Success -> {
                    cafeLocation = result.data
                    // If we can reach the backend, default to CHECK_IN
                    // The foreground service will broadcast CAFE_CLOSED if applicable
                    _state.value = CafeState.CHECK_IN
                }
                is ApiResult.Error -> {
                    Log.w(TAG, "Failed to fetch café location: ${result.message}")
                    _state.value = CafeState.CHECK_IN
                }
                is ApiResult.NetworkError -> {
                    Log.w(TAG, "Network error fetching café location: ${result.message}")
                    _state.value = CafeState.CHECK_IN
                }
            }
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
                val location = GpsHelper.getLastLocation(getApplication())
                if (location == null) {
                    _errorMessage.value = "Unable to get GPS location. Please ensure location is enabled."
                    _isCheckingIn.value = false
                    return@launch
                }

                // 2. Fetch café location if not cached
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
