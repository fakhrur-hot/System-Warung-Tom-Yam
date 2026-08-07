package com.razstudio.pos.notification

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Extension property to create a single DataStore instance scoped to the application. */
private val Context.listenerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "listener_prefs"
)

/**
 * Persistent device-local preferences for the Payment Notification Listener feature.
 *
 * Uses DataStore Preferences for reactive Flow-based access, suitable for Compose UI observation.
 * Each device stores its own independent settings (a kitchen tablet can stay silent while the
 * till buzzes).
 *
 * Defaults: all supported apps monitored, sound ON, vibration ON, toast ON, auto-start ON.
 */
@Singleton
class ListenerPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore: DataStore<Preferences> = context.listenerDataStore

    // ── Master toggle ────────────────────────────────────────────────────────────────────────

    /** Master toggle — is the listener feature enabled by the admin on this device? */
    val isEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLED] ?: false
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    // ── Monitored packages ───────────────────────────────────────────────────────────────────

    /** Which wallet app packages are actively monitored. Defaults to ALL supported packages. */
    val monitoredPackages: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_MONITORED_PACKAGES] ?: WalletApp.allPackages()
    }

    suspend fun setMonitoredPackages(packages: Set<String>) {
        dataStore.edit { prefs -> prefs[KEY_MONITORED_PACKAGES] = packages }
    }

    /** Add or remove a single package from the monitored set. */
    suspend fun togglePackage(packageName: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_MONITORED_PACKAGES] ?: WalletApp.allPackages()
            prefs[KEY_MONITORED_PACKAGES] = if (enabled) {
                current + packageName
            } else {
                current - packageName
            }
        }
    }

    // ── Auto-start on boot ───────────────────────────────────────────────────────────────────

    /** Auto-start listener on boot (only effective if notification access permission is granted). */
    val autoStartOnBoot: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_START_ON_BOOT] ?: true
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_AUTO_START_ON_BOOT] = enabled }
    }

    // ── Sound ────────────────────────────────────────────────────────────────────────────────

    /** Play alert sound when a payment is captured or a payment alert arrives from another device. */
    val soundEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SOUND_ENABLED] ?: true
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_SOUND_ENABLED] = enabled }
    }

    // ── Vibration ────────────────────────────────────────────────────────────────────────────

    /** Vibrate on payment alert (device-local, independent of sound). */
    val vibrationEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_VIBRATION_ENABLED] ?: true
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_VIBRATION_ENABLED] = enabled }
    }

    // ── Toast notification ───────────────────────────────────────────────────────────────────

    /** Show Android heads-up toast notification when a payment alert arrives from another device. */
    val toastNotificationEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TOAST_NOTIFICATION_ENABLED] ?: true
    }

    suspend fun setToastNotificationEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_TOAST_NOTIFICATION_ENABLED] = enabled }
    }

    // ── Business hours (cached from backend) ─────────────────────────────────────────────────

    /**
     * Cached business day start hour (0-23). Default -1 means "not yet synced — run 24/7".
     * Synced from backend `SettingsResponse.businessDayStartHour`.
     */
    val cachedBusinessDayStartHour: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_BUSINESS_START_HOUR] ?: -1
    }

    /**
     * Cached business day end hour (0-23). Default -1 means "not yet synced — run 24/7".
     * Synced from backend `SettingsResponse.businessDayEndHour`.
     */
    val cachedBusinessDayEndHour: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_BUSINESS_END_HOUR] ?: -1
    }

    suspend fun setCachedBusinessDayStartHour(hour: Int) {
        dataStore.edit { prefs -> prefs[KEY_BUSINESS_START_HOUR] = hour }
    }

    suspend fun setCachedBusinessDayEndHour(hour: Int) {
        dataStore.edit { prefs -> prefs[KEY_BUSINESS_END_HOUR] = hour }
    }

    /**
     * Fast synchronous check whether the current device clock falls within cached business hours.
     *
     * Returns `true` if either start or end is -1 (not yet synced — run 24/7).
     * Handles wrap-around midnight: start=15, end=2 means open 15:00–01:59.
     */
    fun isWithinBusinessHours(): Boolean {
        // Read cached values directly from the DataStore file via blocking first-value isn't
        // practical here; we use runBlocking-free approach by reading from the preference keys
        // at construction time. However, the simplest correct approach for a synchronous helper
        // is to read from the underlying preferences snapshot.
        // Since this is called from onNotificationPosted (main thread), we use a non-suspending
        // approach by leveraging the fact that DataStore caches in memory after first read.
        return isWithinBusinessHours(LocalTime.now().hour)
    }

    /**
     * Testable overload: checks whether [currentHour] is within [startHour]..[endHour].
     */
    internal fun isWithinBusinessHours(
        currentHour: Int,
        startHour: Int = cachedStartHourSnapshot,
        endHour: Int = cachedEndHourSnapshot,
    ): Boolean {
        // Not yet synced — run 24/7
        if (startHour == -1 || endHour == -1) return true

        return if (startHour <= endHour) {
            // Same-day range, e.g. start=8, end=17 → open 08:00–16:59
            currentHour in startHour until endHour
        } else {
            // Wraps midnight, e.g. start=15, end=2 → open 15:00–01:59
            currentHour >= startHour || currentHour < endHour
        }
    }

    /**
     * In-memory snapshot of business hours for synchronous access in [isWithinBusinessHours].
     * Updated whenever the DataStore emits a new value.
     */
    @Volatile
    internal var cachedStartHourSnapshot: Int = -1
        private set

    @Volatile
    internal var cachedEndHourSnapshot: Int = -1
        private set

    /** Call from a coroutine scope to keep snapshots in sync with DataStore. */
    suspend fun refreshBusinessHoursSnapshot() {
        dataStore.data.collect { prefs ->
            cachedStartHourSnapshot = prefs[KEY_BUSINESS_START_HOUR] ?: -1
            cachedEndHourSnapshot = prefs[KEY_BUSINESS_END_HOUR] ?: -1
        }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("listener_enabled")
        private val KEY_MONITORED_PACKAGES = stringSetPreferencesKey("monitored_packages")
        private val KEY_AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        private val KEY_SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        private val KEY_VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        private val KEY_TOAST_NOTIFICATION_ENABLED = booleanPreferencesKey("toast_notification_enabled")
        private val KEY_BUSINESS_START_HOUR = intPreferencesKey("business_start_hour")
        private val KEY_BUSINESS_END_HOUR = intPreferencesKey("business_end_hour")

        /** All supported wallet packages — the superset from which monitored is a subset. */
        val ALL_SUPPORTED_PACKAGES: Map<WalletApp, List<String>> =
            WalletApp.entries.associateWith { it.packages }
    }
}
