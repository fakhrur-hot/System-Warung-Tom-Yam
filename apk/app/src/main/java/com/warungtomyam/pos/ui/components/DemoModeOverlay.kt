package com.warungtomyam.pos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Global Demo Mode affordance, hoisted at the NavHost scope so it overlays every real screen while
 * [active]. It doubles as a role switcher: the same shared local dataset backs the admin and the
 * ordering-staff surfaces, so placing an order as staff and switching to admin shows it live in the
 * table view. Tapping Exit opens a confirm dialog; confirming calls [onConfirmExit], which destroys
 * the shared demo dataset and returns to the main page.
 *
 * Strings are intentionally short and English-only: this is a meta-control sitting on top of the
 * real (fully localized) app, not part of any café-facing surface.
 */
@Composable
fun DemoModeOverlay(
    active: Boolean,
    onRole: DemoRole?,
    onGoAdmin: () -> Unit,
    onGoStaff: () -> Unit,
    onConfirmExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!active) return

    var showConfirm by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Filled.Science, contentDescription = null)
            Text(
                text = "DEMO",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            RoleChip("Admin", selected = onRole == DemoRole.ADMIN, onClick = onGoAdmin)
            RoleChip("Staff", selected = onRole == DemoRole.STAFF, onClick = onGoStaff)
            RoleChip("Exit", selected = false, onClick = { showConfirm = true })
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            icon = { Icon(Icons.Filled.Science, contentDescription = null) },
            title = { Text("Leave the demo?") },
            text = {
                Text(
                    "This ends the demo and erases all demo data (orders, menu edits and tables). " +
                        "You'll return to the main page."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onConfirmExit()
                }) { Text("Exit demo") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Keep exploring") }
            },
        )
    }
}

@Composable
private fun RoleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) Color.White else Color.Transparent,
            labelColor = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        ),
    )
}

/** Which demo surface is currently on screen (drives the switcher's selected chip). */
enum class DemoRole { ADMIN, STAFF }
