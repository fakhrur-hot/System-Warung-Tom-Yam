package com.warungtomyam.pos.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.uiStrings
import com.warungtomyam.pos.ui.viewmodels.BackupViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    // SAF: Create document picker for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.writeExportToUri(context, it) }
    }

    // SAF: Open document picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.parseImportFile(context, it) }
    }

    // Show snackbar for error or success
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.backupTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.commonBack)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Export Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = strings.exportDatabaseTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.exportDatabaseDesc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.prepareExport()
                                },
                                enabled = !state.isLoading
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(strings.exportButton)
                            }
                            // Share button (visible after export)
                            if (state.exportUri != null) {
                                OutlinedButton(
                                    onClick = {
                                        state.exportUri?.let { uri ->
                                            val shareIntent = viewModel.createShareIntent(uri)
                                            context.startActivity(
                                                Intent.createChooser(shareIntent, strings.shareBackupTitle)
                                            )
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(strings.shareButton)
                                }
                            }
                        }
                    }
                }

                // Import Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = strings.importDatabaseTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.importDatabaseDesc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/json"))
                            },
                            enabled = !state.isLoading
                        ) {
                            Text(strings.selectBackupFileButton)
                        }
                    }
                }
            }

            // Loading overlay
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    // Launch SAF picker after export is prepared
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && viewModel.hasExportReady()) {
            val timestamp = DateTimeFormatter
                .ofPattern("yyyyMMdd_HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now())
            exportLauncher.launch("warung_backup_$timestamp.json")
        }
    }

    // Import preview/confirm dialog
    if (state.showConfirmDialog && state.importPreview != null) {
        val preview = state.importPreview!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissImport() },
            title = { Text(strings.confirmImportTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = strings.importWarning,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${strings.backupVersionLabel}: ${preview.version}")
                    Text("${strings.exportedAtLabel}: ${preview.exportedAt}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(strings.contentsLabel, fontWeight = FontWeight.SemiBold)
                    Text("• ${strings.tablesLabel}: ${preview.tableCount}")
                    Text("• ${strings.menuItemsLabel}: ${preview.menuItemCount}")
                    Text("• ${strings.ordersLabel}: ${preview.orderCount}")
                    Text("• ${strings.orderItemsLabel}: ${preview.orderItemCount}")
                    Text("• ${strings.printerConfigsLabel}: ${if (preview.hasPrinterConfigs) strings.commonYes else strings.commonNo}")
                    Text("• ${strings.settingsLabel}: ${if (preview.hasSettings) strings.commonYes else strings.commonNo}")
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmImport() }
                ) {
                    Text(strings.restoreButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissImport() }) {
                    Text(strings.commonCancel)
                }
            }
        )
    }
}
