package com.razstudio.pos.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hiding and restoring the Android system bars.
 *
 * ## Why this is a helper and not two lines at the call site
 *
 * Immersive mode is not a flag you set once. The system shows the bars again on its own — after a
 * dialog, a permission prompt, a keyboard, a rotation, or the user swiping from an edge — so the
 * app has to re-assert it whenever it regains focus. Every place that needs to do that should be
 * calling the same function, or the bars come back on one screen and not another and nobody can
 * work out why.
 *
 * ## BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, deliberately
 *
 * The bars are hidden, not disabled: an edge swipe brings them back temporarily and they hide
 * themselves again. The alternative traps whoever is holding the device — no Back, no Home, no way
 * to reach Android settings — and on a POS terminal that is one bad afternoon away from a factory
 * reset. A café should be able to get out of this without knowing a secret.
 */
object FullscreenMode {

    /** Apply or lift immersive mode on [activity]'s window. Safe to call repeatedly. */
    fun apply(activity: Activity, enabled: Boolean) {
        val window = activity.window ?: return
        val controller = WindowInsetsControllerCompat(window, window.decorView)

        // Drawing edge-to-edge only while hidden: with the bars visible the app keeps the ordinary
        // inset behaviour every screen was laid out against, so turning this off restores exactly
        // what was there before rather than something close to it.
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)

        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Find the Activity behind a Compose `LocalContext`.
     *
     * Compose hands out a ContextWrapper rather than the Activity, so a naive cast is a crash
     * waiting for the one OEM that wraps it twice.
     */
    fun activityOf(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
