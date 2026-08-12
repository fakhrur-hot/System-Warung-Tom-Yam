package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.local.Table
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.viewmodels.TableViewViewModel

/**
 * Dialog for managing table registry (add/rename/delete tables).
 */
@Composable
fun TableManagementDialog(
    state: TableViewViewModel.TableManagementState,
    strings: UiStrings,
    onUpdateNewTableLabel: (String) -> Unit,
    onAddTable: () -> Unit,
    onStartEdit: (Table) -> Unit,
    onUpdateEditLabel: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDeleteTable: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // A deleted table's row (and its qr_token) is genuinely removed server-side — any
    // QR card already printed for it stops resolving (tables-session 404s). That's
    // silent and irreversible from the admin's point of view without this warning, so
    // deletion goes through a confirm step here rather than firing on the icon tap.
    var pendingDeleteTable by remember { mutableStateOf<Table?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.manageTablesTitle) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Add new table section — ID is auto-generated (T0001, T0002, ...),
                // never entered manually. Label is an optional display-name override.
                Text(
                    text = strings.addTableSection,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.newTableLabel,
                        onValueChange = onUpdateNewTableLabel,
                        label = { Text(strings.tableLabelField) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = onAddTable,
                        enabled = state.tables.size < TableViewViewModel.MAX_TABLES
                    ) {
                        Text(strings.addButton)
                    }
                }

                if (state.error != null) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // Existing tables list
                Text(
                    text = "${strings.currentTablesLabel} (${state.tables.size}/${TableViewViewModel.MAX_TABLES})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (state.tables.isEmpty()) {
                    Text(
                        text = strings.noTablesConfiguredHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(200.dp)
                    ) {
                        items(state.tables) { table ->
                            if (state.editingTable?.id == table.id) {
                                // Editing mode
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = state.editLabel,
                                        onValueChange = onUpdateEditLabel,
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = onSaveEdit) {
                                        Text(strings.commonSave)
                                    }
                                    TextButton(onClick = onCancelEdit) {
                                        Text(strings.commonCancel)
                                    }
                                }
                            } else {
                                // Display mode
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = table.label,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "ID: ${table.id}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { onStartEdit(table) }) {
                                        Icon(Icons.Default.Edit, contentDescription = strings.commonEdit)
                                    }
                                    IconButton(onClick = { pendingDeleteTable = table }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = strings.commonDelete,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.commonDone)
            }
        }
    )

    pendingDeleteTable?.let { table ->
        AlertDialog(
            onDismissRequest = { pendingDeleteTable = null },
            title = { Text(strings.deleteTableConfirmTitle) },
            text = { Text(strings.deleteTableConfirmMessage) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteTable(table.id)
                    pendingDeleteTable = null
                }) {
                    Text(strings.commonDelete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTable = null }) {
                    Text(strings.commonCancel)
                }
            }
        )
    }
}
