package com.razstudio.pos.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import com.razstudio.pos.printing.DriverAvailability
import com.razstudio.pos.ui.i18n.LanguageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives an Android second screen through the standard `Presentation` API. (HW-REQ-4, task 4.1)
 *
 * On the target terminal that screen is a `FLAG_PRESENTATION` **virtual** display, 800 × 480
 * landscape at density 160, owned by `com.sunmi.usbscreen` — verified on the unit. Two consequences
 * this class is built around:
 *
 * - **The display is re-queried on every show.** It belongs to another app, so it disappears and
 *   reappears when that app restarts. A cached `Display` handle goes stale and shows on nothing.
 * - **Nothing here throws.** A customer screen is an accessory. If it has vanished mid-service the
 *   sale must still complete, so every failure is logged and swallowed.
 *
 * Deliberately **not** the `lcd*` / `com.sunmi.adapter.lcd` family: that is Sunmi-internal — its
 * only bound client is `com.sunmi.thingservice` — and it targets two-line text strips, which cannot
 * render a payment QR at all. (designs.md H9)
 */
@Singleton
class PresentationDisplayDriver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val languageManager: LanguageManager,
) : CustomerDisplayDriver {

    private companion object {
        const val TAG = "PresentationDisplay"
    }

    override val kind = DisplayDriverKind.PRESENTATION

    /** An 800 × 480 Compose surface renders a scannable QR; this is the whole reason for it. */
    override val canRenderQr = true

    private val _state = MutableStateFlow<CustomerDisplayState>(
        CustomerDisplayState.Idle(cafeName = "")
    )

    /** The live presentation, or null when nothing is on screen. Main-thread confined. */
    private var presentation: CustomerDisplayPresentation? = null

    override suspend fun availability(context: Context): DriverAvailability {
        val displays = presentationDisplays(context)
        return if (displays.isNotEmpty()) {
            DriverAvailability(available = true)
        } else {
            DriverAvailability(available = false, reason = "No presentation display")
        }
    }

    override suspend fun show(state: CustomerDisplayState) = withContext(Dispatchers.Main) {
        _state.value = state
        try {
            val display = presentationDisplays(context).firstOrNull()
            if (display == null) {
                // The screen went away. Drop what we were holding so the next show starts clean
                // rather than pushing into a dead window.
                dismissQuietly()
                return@withContext
            }

            val live = presentation
            // Rebuild when the Display identity changed — after com.sunmi.usbscreen restarts the
            // new display is a different object, and the old presentation is attached to a window
            // that no longer exists.
            if (live != null && live.display?.displayId == display.displayId && live.isShowing) {
                return@withContext   // same screen, already up; the StateFlow does the rest
            }

            dismissQuietly()
            presentation = CustomerDisplayPresentation(
                outerContext = context,
                display = display,
                state = _state.asStateFlow(),
                language = languageManager.language,
            ).also { it.show() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not show customer display", e)
            presentation = null
        }
    }

    override suspend fun clear() = withContext(Dispatchers.Main) {
        dismissQuietly()
    }

    private fun dismissQuietly() {
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "Dismiss failed", e)
        }
        presentation = null
    }

    /**
     * Displays that can host a `Presentation`. `DISPLAY_CATEGORY_PRESENTATION` is the modern query
     * and returns them in priority order; `MediaRouter` — which MultiPOS uses — is the pre-API-17
     * route and gives no way to tell several displays apart. (designs.md D8)
     */
    private fun presentationDisplays(context: Context): List<android.view.Display> =
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.toList().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Display query failed", e)
            emptyList()
        }
}
