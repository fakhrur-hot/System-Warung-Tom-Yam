package com.razstudio.pos.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.razstudio.pos.R

/**
 * Neutral template palette (indigo) — the generic vendor default shipped with the template build,
 * with no café branding. A café-specific build overrides these values to its own colors. The ramp
 * names (TomYam*) are kept stable so the theme wiring downstream needs no changes.
 *
 * Colors are read from Android resources (res/values/colors.xml) to allow café-specific builds to
 * override the theme palette via source sets.
 */
object TomYamColors {
    private val colorCache = mutableMapOf<String, Color>()

    private fun getColorName(resId: Int): String = when (resId) {
        R.color.tom_yam_50 -> "tom_yam_50"
        R.color.tom_yam_100 -> "tom_yam_100"
        R.color.tom_yam_200 -> "tom_yam_200"
        R.color.tom_yam_300 -> "tom_yam_300"
        R.color.tom_yam_400 -> "tom_yam_400"
        R.color.tom_yam_500 -> "tom_yam_500"
        R.color.tom_yam_600 -> "tom_yam_600"
        R.color.tom_yam_700 -> "tom_yam_700"
        R.color.tom_yam_800 -> "tom_yam_800"
        R.color.tom_yam_900 -> "tom_yam_900"
        R.color.tom_yam_muted -> "tom_yam_muted"
        R.color.tom_yam_outline -> "tom_yam_outline"
        else -> throw IllegalArgumentException("Unknown color resource: $resId")
    }

    private fun getThemeColor(context: Context, resId: Int): Color {
        val name = getColorName(resId)
        return colorCache.getOrPut(name) {
            Color(ContextCompat.getColor(context, resId))
        }
    }

    /**
     * Pre-populates the color cache with all theme colors.
     * Call this once at theme initialization.
     */
    fun initialize(context: Context) {
        listOf(
            R.color.tom_yam_50,
            R.color.tom_yam_100,
            R.color.tom_yam_200,
            R.color.tom_yam_300,
            R.color.tom_yam_400,
            R.color.tom_yam_500,
            R.color.tom_yam_600,
            R.color.tom_yam_700,
            R.color.tom_yam_800,
            R.color.tom_yam_900,
            R.color.tom_yam_muted,
            R.color.tom_yam_outline
        ).forEach { getThemeColor(context, it) }
    }

    // Primary ramp (10 shades from 50-900)
    val TomYam50: Color get() = getCachedOrThrow("tom_yam_50")
    val TomYam100: Color get() = getCachedOrThrow("tom_yam_100")
    val TomYam200: Color get() = getCachedOrThrow("tom_yam_200")
    val TomYam300: Color get() = getCachedOrThrow("tom_yam_300")
    val TomYam400: Color get() = getCachedOrThrow("tom_yam_400")
    val TomYam500: Color get() = getCachedOrThrow("tom_yam_500")
    val TomYam600: Color get() = getCachedOrThrow("tom_yam_600")
    val TomYam700: Color get() = getCachedOrThrow("tom_yam_700")
    val TomYam800: Color get() = getCachedOrThrow("tom_yam_800")
    val TomYam900: Color get() = getCachedOrThrow("tom_yam_900")

    // Neutral muted slate for secondary text.
    val TomYamMuted: Color get() = getCachedOrThrow("tom_yam_muted")
    val TomYamOutline: Color get() = getCachedOrThrow("tom_yam_outline")

    private fun getCachedOrThrow(name: String): Color {
        return colorCache[name] ?: throw IllegalStateException(
            "TomYamColors not initialized. Call TomYamColors.initialize(context) before accessing colors."
        )
    }

    /** Returns true if the color cache has been initialized. */
    val isInitialized: Boolean get() = colorCache.isNotEmpty()
}

// Legacy property accessors for backward compatibility with existing code
val TomYam50: Color get() = TomYamColors.TomYam50
val TomYam100: Color get() = TomYamColors.TomYam100
val TomYam200: Color get() = TomYamColors.TomYam200
val TomYam300: Color get() = TomYamColors.TomYam300
val TomYam400: Color get() = TomYamColors.TomYam400
val TomYam500: Color get() = TomYamColors.TomYam500
val TomYam600: Color get() = TomYamColors.TomYam600
val TomYam700: Color get() = TomYamColors.TomYam700
val TomYam800: Color get() = TomYamColors.TomYam800
val TomYam900: Color get() = TomYamColors.TomYam900
val TomYamMuted: Color get() = TomYamColors.TomYamMuted
val TomYamOutline: Color get() = TomYamColors.TomYamOutline

// NOTE: Order-status colors (green = free/done, orange = pending, purple = cooking,
// blue = ready, grey = unknown) are intentionally NOT part of this brand ramp. They
// encode meaning and stay hardcoded where they are used.