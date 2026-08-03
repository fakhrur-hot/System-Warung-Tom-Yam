package com.razstudio.pos.display

import android.util.Log
import com.razstudio.pos.data.local.LocalPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one entry point the rest of the app uses to drive the customer display.
 *
 * Call sites never look up a driver, never null-check, and never branch on whether a display is
 * configured — they call [show] and it goes wherever the café pointed it, including nowhere. That
 * is what [NoDisplayDriver] buys as a null object. (designs.md D8)
 *
 * The selection is read from [LocalPrefs] on **every** call rather than cached, because it is
 * **device-local** configuration a café can change from Devices & Hardware while the till is
 * running (HW-REQ-8). A cached choice would need an invalidation path, and reading a SharedPreference
 * is cheaper than the bug that path would eventually hide.
 */
@Singleton
class CustomerDisplayManager @Inject constructor(
    private val localPrefs: LocalPrefs,
    private val drivers: Set<@JvmSuppressWildcards CustomerDisplayDriver>,
) {
    private companion object {
        const val TAG = "CustomerDisplay"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Serialises driver calls. Two order updates arriving together could otherwise interleave
     * inside `show()` and race to build the presentation, leaving one orphaned window on screen.
     */
    private val mutex = Mutex()

    /** The driver this device is configured for, falling back to "no display". */
    internal fun current(): CustomerDisplayDriver {
        val selected = localPrefs.selectedDisplayDriver
            ?.let { runCatching { DisplayDriverKind.valueOf(it) }.getOrNull() }
            ?: DisplayDriverKind.NONE
        return drivers.firstOrNull { it.kind == selected }
        // A café could hold a selection whose driver is not in this build — an older preference,
        // or a downgrade. Falling back to the null object is right: silently showing nothing beats
        // crashing the till over an accessory.
            ?: drivers.first { it.kind == DisplayDriverKind.NONE }
    }

    /** True when the configured display can show a payment QR. */
    fun canRenderQr(): Boolean = current().canRenderQr

    /** Push [state] to the configured display. Never throws; never blocks the caller. */
    fun show(state: CustomerDisplayState) {
        scope.launch {
            mutex.withLock {
                try {
                    current().show(state)
                } catch (e: Exception) {
                    // Belt and braces — drivers are required not to throw, but a display fault
                    // must never be able to take down an order flow.
                    Log.w(TAG, "Customer display update failed", e)
                }
            }
        }
    }

    /** Blank the display — on sign-out, or when the till goes idle. */
    fun clear() {
        scope.launch {
            mutex.withLock {
                try {
                    current().clear()
                } catch (e: Exception) {
                    Log.w(TAG, "Customer display clear failed", e)
                }
            }
        }
    }
}
