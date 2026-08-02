package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.razstudio.pos.data.ModeCapabilities
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Read-only access to the active mode and what it permits, for screens that only need to gate
 * something (tasks 11.1-11.4).
 *
 * Exists so a screen does not have to acquire a feature ViewModel it otherwise has no use for just
 * to ask one question — `CafeManagementScreen` is a hub of four buttons, and giving it the whole
 * settings ViewModel to decide whether to show one of them would be worse than this.
 *
 * Deliberately exposes [ModeRepository]'s flows rather than snapshots: a mode changed in Setup must
 * be reflected when the operator navigates back, and a gate that is only evaluated once at first
 * composition would leave a cloud-only control on screen in a café that just left Cloud Mode.
 */
@HiltViewModel
class ModeViewModel @Inject constructor(
    private val modeRepository: ModeRepository,
    private val lanAddress: com.razstudio.pos.data.lan.LanAddress,
    private val appConfig: com.razstudio.pos.data.AppConfigStore,
) : ViewModel() {
    val mode: StateFlow<OperatingMode> = modeRepository.activeMode
    val capabilities: StateFlow<ModeCapabilities> = modeRepository.capabilities

    /**
     * What the admin home's backup banner needs (task 13.1).
     *
     * [isOnlyCopy] is the whole gate: Cloud has Supabase holding a second copy, so a banner there
     * would be noise — and a banner shown when it does not matter is one operators learn to ignore,
     * which breaks it for the cafés where it does.
     */
    data class BackupNag(val lastBackupAtMs: Long, val isOnlyCopy: Boolean)

    fun backupNag(): BackupNag = BackupNag(
        lastBackupAtMs = appConfig.lastBackupAtMs(),
        isOnlyCopy = modeRepository.currentMode() != OperatingMode.CLOUD,
    )

    /**
     * This device's address on the café network, resolved fresh on each call (task 21.2,
     * Requirements 4.3.2, 4.3.4).
     *
     * Not cached in a StateFlow: the operator reads it precisely when they are turning the hotspot on
     * or moving between networks, so a value captured at construction would be the stale one exactly
     * when it matters. Cheap enough to re-resolve on recomposition.
     */
    fun lanAddress(): com.razstudio.pos.data.lan.LanAddress.Result = lanAddress.resolve()
}
