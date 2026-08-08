package com.razstudio.opsapp.ui.viewmodels

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.opsapp.data.api.AccessRevocationManager
import com.razstudio.opsapp.data.api.OperatorApiClient
import com.razstudio.opsapp.data.local.ConnectedCafeDao
import com.razstudio.opsapp.data.local.ConnectedCafeEntity
import com.razstudio.opsapp.ui.util.OwnerQrShare
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * ViewModel for the Cafe_Profile_Shell.
 *
 * Responsibilities:
 * - Load the selected café from the local store and touch its `lastConnectedAt`.
 * - Expose an [OperatorApiClient] scoped to that café's credential.
 * - Observe [AccessRevocationManager.revocationEvents] for this café and surface revocation.
 * - Handle disconnect and owner-key share actions.
 */
@HiltViewModel
class CafeProfileViewModel @Inject constructor(
    private val dao: ConnectedCafeDao,
    private val revocationManager: AccessRevocationManager,
) : ViewModel() {

    private val _cafe = MutableStateFlow<ConnectedCafeEntity?>(null)
    val cafe: StateFlow<ConnectedCafeEntity?> = _cafe.asStateFlow()

    private val _apiClient = MutableStateFlow<OperatorApiClient?>(null)
    val apiClient: StateFlow<OperatorApiClient?> = _apiClient.asStateFlow()

    private val _selectedTab = MutableStateFlow(ShellTab.PROFILE)
    val selectedTab: StateFlow<ShellTab> = _selectedTab.asStateFlow()

    private val _revoked = MutableStateFlow(false)
    val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

    private var loadedCafeId: String? = null

    /**
     * Load the café with [cafeId] from the local store. Safe to call again on config change;
     * it no-ops if already loaded for the same id.
     */
    fun loadCafe(cafeId: String) {
        if (loadedCafeId == cafeId && _cafe.value != null) return
        loadedCafeId = cafeId

        viewModelScope.launch {
            val entity = dao.listAll()
                .first()
                .firstOrNull { it.id == cafeId }
            _cafe.value = entity

            if (entity != null) {
                val now = DateTimeFormatter.ISO_INSTANT.format(
                    Instant.now().atOffset(ZoneOffset.UTC)
                )
                dao.touchLastConnected(entity.id, now)
                _apiClient.value = OperatorApiClient(entity, revocationManager)
            } else {
                _apiClient.value = null
            }
        }
    }

    /** Switch the visible bottom-nav tab. */
    fun selectTab(tab: ShellTab) {
        _selectedTab.value = tab
    }

    /** Build and start a share chooser for this café's owner key URL, if available. */
    fun shareOwnerKey(context: Context): Boolean {
        val url = _cafe.value?.ownerKeyUrl ?: return false
        return try {
            val intent = OwnerQrShare.buildShareIntent(context, url)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Remove this café from the local store. */
    fun disconnect() {
        val id = _cafe.value?.id ?: return
        viewModelScope.launch {
            dao.deleteById(id)
        }
    }

    init {
        viewModelScope.launch {
            revocationManager.revocationEvents.collect { event ->
                if (event.cafeId == loadedCafeId || event.cafeId == _cafe.value?.id) {
                    _revoked.value = true
                }
            }
        }
    }
}

enum class ShellTab(val label: String, val icon: ImageVector) {
    PROFILE("Profile", Icons.Default.Info),
    MENU("Menu", Icons.Default.Menu),
    TABLES("Tables", Icons.Default.List),
    TABLE_QR("Table QR", Icons.Default.QrCode),
}
