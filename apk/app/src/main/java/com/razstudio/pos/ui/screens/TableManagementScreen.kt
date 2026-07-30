package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.local.isTakeout
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.TableViewViewModel

/**
 * Dedicated full-screen Tables Management page (reached from Café Management).
 * Replaces the former modal dialog overlaid on the table view: add/rename/delete tables
 * with an auto-generated ID (T0001, T0002, …). Reuses [TableViewViewModel]'s management
 * state — [TableViewViewModel.showTableManagement] loads the tables and pushes a catch-up
 * resync on entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableManagementScreen(
    onBack: () -> Unit,
    viewModel: TableViewViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val state by viewModel.tableManagement.collectAsState()

    // Populate the management state (tables + backend catch-up) when the page opens.
    LaunchedEffect(Unit) { viewModel.showTableManagement() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.manageTablesTitle) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.hideTableManagement()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.commonBack)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
        ) {
            // Add new table — ID is auto-generated (T0001, …); label is an optional override.
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
                    onValueChange = { viewModel.updateNewTableLabel(it) },
                    label = { Text(strings.tableLabelField) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = { viewModel.addTable() },
                    enabled = state.tables.count { !it.isTakeout } < TableViewViewModel.MAX_TABLES
                ) {
                    Text(strings.addButton)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Take-out (Tapaw) slots — additional to the dine-in cap, no printed QR card.
            Text(
                text = strings.takeoutSection,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${state.tables.count { it.isTakeout }}/${TableViewViewModel.MAX_TAKEOUT}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { viewModel.addTakeoutTable() },
                    enabled = state.tables.count { it.isTakeout } < TableViewViewModel.MAX_TAKEOUT
                ) {
                    Text(strings.addTakeoutButton)
                }
            }

            if (state.error != null) {
                Text(
                    text = state.error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${strings.currentTablesLabel} (${state.tables.count { !it.isTakeout }}/${TableViewViewModel.MAX_TABLES})",
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.tables) { table ->
                        if (state.editingTable?.id == table.id) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = state.editLabel,
                                    onValueChange = { viewModel.updateEditLabel(it) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(onClick = { viewModel.saveEditTable() }) {
                                    Text(strings.commonSave)
                                }
                                TextButton(onClick = { viewModel.cancelEdit() }) {
                                    Text(strings.commonCancel)
                                }
                            }
                        } else {
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
                                IconButton(onClick = { viewModel.startEditTable(table) }) {
                                    Icon(Icons.Default.Edit, contentDescription = strings.commonEdit)
                                }
                                IconButton(onClick = { viewModel.deleteTable(table.id) }) {
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
    }
}
