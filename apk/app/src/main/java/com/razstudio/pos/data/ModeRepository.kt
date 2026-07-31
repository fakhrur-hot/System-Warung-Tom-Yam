package com.razstudio.pos.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observable access to the active [OperatingMode] and its [ModeCapabilities] (Requirement 1.3).
 *
 * [AppConfigStore] is the persistent home of the mode, but it exposes plain getters over
 * EncryptedSharedPreferences. Compose screens and ViewModels need something observable, and — more
 * importantly — they need one *shared* instance, so a mode change applied during Setup is reflected
 * everywhere at once instead of leaving each screen holding whatever it happened to read at
 * construction.
 *
 * A `@Singleton` with an `@Inject` constructor needs no Hilt module, matching [AppConfigStore].
 */
@Singleton
class ModeRepository @Inject constructor(
    private val appConfigStore: AppConfigStore,
) {
    // Seeded from persistence. Not defaulted to CLOUD here: AppConfigStore already applies the
    // "absent key means CLOUD" rule (Requirement 1.2), and repeating it would mask a read failure
    // behind a plausible-looking value.
    private val _activeMode = MutableStateFlow(appConfigStore.operatingMode())
    private val _capabilities = MutableStateFlow(_activeMode.value.toCapabilities())

    /** The persisted operating mode. Changes only via [setMode] — that is, only from Setup. */
    val activeMode: StateFlow<OperatingMode> = _activeMode.asStateFlow()

    /**
     * What the active mode permits, always derived from [activeMode] rather than stored
     * independently — there is no code path that can move one without the other.
     */
    val capabilities: StateFlow<ModeCapabilities> = _capabilities.asStateFlow()

    /** For non-reactive call sites: services, one-shot checks. */
    fun currentMode(): OperatingMode = _activeMode.value

    /** For non-reactive call sites. */
    fun currentCapabilities(): ModeCapabilities = _capabilities.value

    /**
     * Persists [mode] and publishes it together with its derived capabilities.
     *
     * Setup Wizard only. Requirement 10.1 makes a mode change a deliberate, confirmed operation
     * rather than a side effect of editing a setting, so this is intentionally not something ordinary
     * settings code reaches for.
     */
    fun setMode(mode: OperatingMode) {
        appConfigStore.setOperatingMode(mode)
        _activeMode.value = mode
        _capabilities.value = mode.toCapabilities()
    }

    /** Re-reads from persistence, for paths that wrote through [AppConfigStore] directly. */
    fun refresh() {
        val persisted = appConfigStore.operatingMode()
        _activeMode.value = persisted
        _capabilities.value = persisted.toCapabilities()
    }
}
