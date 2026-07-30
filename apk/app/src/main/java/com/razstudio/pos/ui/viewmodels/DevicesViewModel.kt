package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.DeviceDto
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Devices management screen.
 * Loads devices from the API and exposes actions (approve, reject, revoke, etc.).
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val secureStorage: SecureStorage,
    private val languageManager: LanguageManager
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    /** This device's own persistent id — used to mark the "(Current)" row in the list. */
    private val currentDeviceId: String = secureStorage.getDeviceId()

    val isSecondaryAdmin: Boolean
        get() = secureStorage.getRole() == SecureStorage.Role.ADMIN_SECONDARY

    private fun isTargetDeviceAdmin(deviceId: String): Boolean {
        val target = _uiState.value.devices.find { it.id == deviceId }
            ?: _pendingRequests.value.find { it.id == deviceId }
        return target?.role == "ADMIN" || target?.role == "ADMIN_SECONDARY"
    }

    data class UiState(
        val devices: List<DeviceDto> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val successMessage: String? = null,
        val pendingCount: Int = 0,
        val currentDeviceId: String = ""
    )

    private val _uiState = MutableStateFlow(UiState(currentDeviceId = currentDeviceId))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** PENDING devices awaiting approval — drives the admin home approval popup. */
    private val _pendingRequests = MutableStateFlow<List<DeviceDto>>(emptyList())
    val pendingRequests: StateFlow<List<DeviceDto>> = _pendingRequests.asStateFlow()

    init {
        loadDevices()
    }

    /**
     * Lightweight poll (no UI-state churn) for PENDING device requests, so the admin home can
     * pop up an approve/reject prompt as soon as an ordering-staff device scans in.
     */
    fun refreshPendingRequests() {
        viewModelScope.launch {
            val result = apiClient.getDevices()
            if (result is ApiResult.Success) {
                _pendingRequests.value = result.data.filter { it.status == "PENDING" }
            }
        }
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = apiClient.getDevices()) {
                is ApiResult.Success -> {
                    val devices = result.data
                    _uiState.value = _uiState.value.copy(
                        devices = devices,
                        isLoading = false,
                        pendingCount = devices.count { it.status == "PENDING" }
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                is ApiResult.NetworkError -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    fun approveDevice(deviceId: String) {
        if (isSecondaryAdmin && isTargetDeviceAdmin(deviceId)) {
            _uiState.value = _uiState.value.copy(error = "Secondary Admin cannot approve another Admin device")
            return
        }
        performAction(deviceId, "APPROVE", str().deviceApproved)
    }

    fun rejectDevice(deviceId: String) {
        if (isSecondaryAdmin && isTargetDeviceAdmin(deviceId)) {
            _uiState.value = _uiState.value.copy(error = "Secondary Admin cannot reject another Admin device")
            return
        }
        performAction(deviceId, "REJECT", str().deviceRejected)
    }

    fun revokeDevice(deviceId: String) {
        if (isSecondaryAdmin && isTargetDeviceAdmin(deviceId)) {
            _uiState.value = _uiState.value.copy(error = "Secondary Admin cannot revoke another Admin device")
            return
        }
        performAction(deviceId, "REVOKE", str().deviceRevoked)
    }

    fun forceCheckOut(deviceId: String) {
        performAction(deviceId, "FORCE_CHECKOUT", str().deviceCheckedOut)
    }

    /** Promote a secondary admin to Main Admin (printer host); demotes the current Main. */
    fun promoteToMain(deviceId: String) {
        if (isSecondaryAdmin) {
            _uiState.value = _uiState.value.copy(error = "Secondary Admin cannot change Admin roles")
            return
        }
        performAction(deviceId, "PROMOTE_MAIN", "Made Main Admin")
    }

    fun renameDevice(deviceId: String, newLabel: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = apiClient.patchDevice(deviceId, "RENAME", newLabel)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        successMessage = str().deviceRenamed
                    )
                    loadDevices()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                is ApiResult.NetworkError -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    private fun performAction(deviceId: String, action: String, successMsg: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = apiClient.patchDevice(deviceId, action)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        successMessage = successMsg
                    )
                    loadDevices()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                is ApiResult.NetworkError -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(error = null, successMessage = null)
    }
}
