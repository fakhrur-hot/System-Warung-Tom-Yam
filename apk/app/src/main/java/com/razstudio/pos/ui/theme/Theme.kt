package com.razstudio.pos.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
