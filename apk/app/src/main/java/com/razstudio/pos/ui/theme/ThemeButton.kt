package com.razstudio.pos.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Squircle theme selector — a rounded-square button showing a small colour swatch of the
 * active theme's primary (600) shade. Tapping it opens a menu listing all [ThemePreset]s
 * with their emoji and name; the current one is marked with a bullet.
 *
 * Drop this into any screen's top-bar actions row to the right of [LanguageButton], exactly
 * as [com.razstudio.pos.ui.i18n.LanguageButton] is placed today.
 *
 * Selecting a preset calls [ThemeViewModel.select], which persists the choice and updates
 * the global [ThemeManager] StateFlow → [WarungTomYamTheme] recomposes with the new
 * [ColorScheme] → every screen recolours instantly.
 */
@Composable
fun ThemeButton(
    modifier: Modifier = Modifier,
    viewModel: ThemeViewModel = hiltViewModel(),
) {
    val current by viewModel.theme.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Squircle button — same 40dp size as LanguageButton
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(30))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            // Colour swatch: a filled circle in the preset's primary shade
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(current.shade600)
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f),
                        shape = CircleShape,
                    )
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            HorizontalDivider()
            ThemePreset.entries.forEach { preset ->
                val isCurrent = preset == current
                DropdownMenuItem(
                    leadingIcon = {
                        // Colour swatch in the menu row
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(preset.shade600)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                        )
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = preset.emoji,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isCurrent) "● ${preset.displayName}" else preset.displayName,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        viewModel.select(preset)
                    },
                )
            }
        }
    }
}
