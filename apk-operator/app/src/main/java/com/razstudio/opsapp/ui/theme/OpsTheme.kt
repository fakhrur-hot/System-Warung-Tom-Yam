package com.razstudio.opsapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Deliberately generic/neutral theme — not café-branded (Requirement 1.3).
 * Blue-grey palette that could belong to any internal-tools app.
 */

private val OpsBlueGrey = Color(0xFF455A64)
private val OpsBlueGreyLight = Color(0xFF78909C)
private val OpsBlueGreyDark = Color(0xFF263238)
private val OpsOnPrimary = Color(0xFFFFFFFF)
private val OpsBackground = Color(0xFFFAFAFA)
private val OpsSurface = Color(0xFFFFFFFF)
private val OpsError = Color(0xFFB00020)

private val LightColorScheme = lightColorScheme(
    primary = OpsBlueGrey,
    onPrimary = OpsOnPrimary,
    primaryContainer = OpsBlueGreyLight,
    onPrimaryContainer = Color.White,
    secondary = OpsBlueGreyLight,
    onSecondary = Color.White,
    background = OpsBackground,
    onBackground = Color(0xFF1C1B1F),
    surface = OpsSurface,
    onSurface = Color(0xFF1C1B1F),
    error = OpsError,
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = OpsBlueGreyLight,
    onPrimary = OpsBlueGreyDark,
    primaryContainer = OpsBlueGrey,
    onPrimaryContainer = Color.White,
    secondary = OpsBlueGreyLight,
    onSecondary = OpsBlueGreyDark,
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    error = Color(0xFFCF6679),
    onError = Color.Black,
)

@Composable
fun OpsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
