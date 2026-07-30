package com.razstudio.pos.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Light color scheme built from the [Tom Yam][TomYam600] brand palette. Deliberately
 * light-only and non-dynamic, exactly like the customer web, so the admin + ordering-staff
 * app carries the same identity on every device regardless of the system dark-mode setting.
 */
private val TomYamLightColors = lightColorScheme(
    primary = TomYam600,
    onPrimary = Color.White,
    primaryContainer = TomYam100,
    onPrimaryContainer = TomYam900,

    secondary = TomYam500,
    onSecondary = Color.White,
    secondaryContainer = TomYam100,
    onSecondaryContainer = TomYam900,

    tertiary = TomYam700,
    onTertiary = Color.White,
    tertiaryContainer = TomYam200,
    onTertiaryContainer = TomYam900,

    background = TomYam50,
    onBackground = TomYam900,
    surface = Color.White,
    onSurface = TomYam900,
    surfaceVariant = TomYam100,
    onSurfaceVariant = TomYamMuted,

    outline = TomYamOutline,
    outlineVariant = TomYam200,

    // Validation / destructive actions keep a standard red, distinct from the brand maroon.
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun WarungTomYamTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Blend the status bar into the app background and use dark icons on the light ground.
            window.statusBarColor = TomYam50.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }
    MaterialTheme(
        colorScheme = TomYamLightColors,
        content = content,
    )
}
