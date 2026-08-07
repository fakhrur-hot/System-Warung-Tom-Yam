package com.razstudio.pos.ui.theme

import android.app.Activity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Root theme wrapper for the app. Deliberately light-only and non-dynamic (no system
 * dark-mode, no dynamic colour), exactly like the customer website, so the admin and
 * ordering-staff surfaces share one identity on every device.
 *
 * The active [ThemePreset] is read from [ThemeViewModel] (backed by [ThemeManager]
 * which persists the selection to SharedPreferences). Switching presets takes effect
 * immediately because [ThemePreset.toColorScheme] is pure and [MaterialTheme] recomposes
 * on every state change, so every screen in the app re-renders without any navigation.
 *
 * The build-time café palette from [TomYamColors] / `res/values/colors.xml` is still
 * used as the default ([ThemePreset.TOM_YAM]), so branded builds remain unaffected: they
 * compile with a different `colors.xml`, which produces the correct default ramp, and that
 * ramp continues to be the starting point until an operator switches presets at runtime.
 */
@Composable
fun WarungTomYamTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val context = view.context

    // Initialize the resource-backed colour cache on first composition (used by ThemePreset.TOM_YAM
    // and for backward compatibility with any remaining direct TomYam* references).
    if (!TomYamColors.isInitialized) {
        TomYamColors.initialize(context.applicationContext)
    }

    val themeViewModel: ThemeViewModel = hiltViewModel()
    val activePreset by themeViewModel.theme.collectAsState()
    val colorScheme = activePreset.toColorScheme()

    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as Activity).window
            // Blend the status bar into the app background and use dark icons on the light ground.
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !activePreset.isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = activePreset.toTypography().scaledForScreen(),
        content = content,
    )
}

/**
 * Ground for a **scrollable panel inside a dialog** — the bill in the order sheet, the menu list in
 * the picker. One step lighter than the dialog around it, so a scroll region reads as a bounded
 * inset rather than melting into the sheet.
 *
 * ### Why this is not just `surface`
 *
 * On a light preset `surface` is white and does the job. On the two dark presets it is `shade800` —
 * `#1E1E1E` for Material Dark, `#121212` for Amoled — which is the SAME value those presets give
 * `surfaceContainerLow`, the sheet's own ground. So a panel painted with `surface` was invisible in
 * dark mode: correct code, zero contrast, and a cashier with no way to see where the scroll area
 * ends.
 *
 * Dark presets therefore get a deliberate mid-grey. It is a knowing exception for Amoled, whose
 * point is pure black — but a bill that cannot be read is worse than a panel that costs some
 * battery, and this is the one region a cashier reads line by line with a customer waiting.
 *
 * Dark is detected by the luminance of `surface` rather than by threading [ThemePreset.isDark] down
 * here, so this stays a plain [ColorScheme] extension that any dialog can reach for.
 */
val ColorScheme.scrollPanel: Color
    get() = if (surface.luminance() < 0.5f) DarkScrollPanel else Color.White

/** ~75% dark: light enough to sit clearly above `#1E1E1E`/`#000000`, dark enough to stay a dark theme. */
private val DarkScrollPanel = Color(0xFF404040)

/**
 * Scale the type ramp up on physically large screens that report a small pixel density.
 *
 * ## The problem this solves
 *
 * The D3 MINI till is 1280x800 at density 160 — so 1dp is exactly 1px, and Material's default
 * `bodyMedium` renders at a literal 14 pixels across an 8-inch panel. Every size in the app is
 * correct by the spec and illegible in the room: staff lean in to read a price, and a customer
 * looking at the same screen from across the counter cannot read it at all.
 *
 * Density is the thing that is wrong, but it is a property of the hardware and not ours to set. So
 * the type ramp compensates instead.
 *
 * ## Why smallestScreenWidthDp
 *
 * It does not change when the till is rotated, so a device cannot land in one bucket in landscape
 * and another in portrait — which would resize every label on the screen mid-service. The D3 MINI
 * reports 800dp either way; an ordinary phone like the Infinix reports ~410dp and is left alone at
 * 1.0x, because its density is already doing this job correctly.
 *
 * Headline and display sizes scale more gently than body text: they are already large, and the
 * complaint is about menu lines and prices, not titles.
 */
@Composable
private fun Typography.scaledForScreen(): Typography {
    val sw = LocalConfiguration.current.smallestScreenWidthDp
    val body = when {
        sw >= 720 -> 1.45f
        sw >= 600 -> 1.20f
        else -> return this
    }
    val heading = 1f + (body - 1f) * 0.5f

    fun TextStyle.by(factor: Float) = copy(
        fontSize = fontSize * factor,
        lineHeight = lineHeight * factor,
    )

    return copy(
        displayLarge = displayLarge.by(heading),
        displayMedium = displayMedium.by(heading),
        displaySmall = displaySmall.by(heading),
        headlineLarge = headlineLarge.by(heading),
        headlineMedium = headlineMedium.by(heading),
        headlineSmall = headlineSmall.by(heading),
        titleLarge = titleLarge.by(body),
        titleMedium = titleMedium.by(body),
        titleSmall = titleSmall.by(body),
        bodyLarge = bodyLarge.by(body),
        bodyMedium = bodyMedium.by(body),
        bodySmall = bodySmall.by(body),
        labelLarge = labelLarge.by(body),
        labelMedium = labelMedium.by(body),
        labelSmall = labelSmall.by(body),
    )
}
