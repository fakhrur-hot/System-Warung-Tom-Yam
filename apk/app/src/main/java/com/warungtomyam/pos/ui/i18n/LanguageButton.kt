package com.warungtomyam.pos.ui.i18n

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
 * Squircle language selector — a rounded-square button showing the current
 * language's short label (defaults to "MY"). Tapping it opens a menu listing all
 * languages (current one marked). Drop this into any screen's top-right corner.
 */
@Composable
fun LanguageButton(
    modifier: Modifier = Modifier,
    viewModel: LanguageViewModel = hiltViewModel(),
) {
    val current by viewModel.language.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(30))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = current.buttonLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        val strings = uiStrings(current)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // Clears this device's manual choice and re-adopts the café-wide default for its role.
            DropdownMenuItem(
                text = { Text(strings.cafeDefaultLanguage) },
                onClick = {
                    expanded = false
                    viewModel.useCafeDefault()
                },
            )
            HorizontalDivider()
            AppLanguage.entries.forEach { lang ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (lang == current) "● ${lang.displayName}" else lang.displayName,
                            fontWeight = if (lang == current) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        viewModel.select(lang)
                    },
                )
            }
        }
    }
}
