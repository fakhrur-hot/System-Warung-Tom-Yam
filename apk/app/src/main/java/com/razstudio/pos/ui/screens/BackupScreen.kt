package com.razstudio.pos.ui.screens

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
import com.razstudio.pos.ui.components.AdBannerFooter
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.BackupViewModel
import com.razstudio.pos.ui.viewmodels.CafeBundleViewModel
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
        // The two cards are top-anchored and this screen does not scroll, so the banner takes the
        // bottom row of a Column rather than being aligned inside the Box — the Box stays only to
        // centre the progress indicator over the cards. Restore, the one destructive control here,
        // sits in the upper card, a long way from the ad.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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

                    // ── Task 23.7: the café setup, in the owner's Google account ──────────────
                    // Sits beside export/import because it is the same idea — a copy somewhere
                    // that is not this phone — but it is a separate card, not a checkbox on
                    // export, because it saves something different: the setup and the café key,
                    // not the day's orders.
                    CafeBundleCard()
                }

                // Loading overlay
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            AdBannerFooter()
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

                    // Task 13.2 — name what is about to be destroyed, not only what is arriving.
                    // applyImport deletes orders, order items, menu, tables, printers and settings
                    // before it imports anything, and the dialog listed none of that.
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = strings.restoreWillEraseTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = strings.restoreWillEraseBody
                            .format(state.currentOrderCount, state.currentMenuItemCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (state.localDataIsOnlyCopy) {
                        // Off-cloud there is no server holding a second copy, so this is final.
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.restoreOnlyCopyWarning,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

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

/**
 * Task 23.7 / 23.9 — save the café setup to the owner's Google account, deliberately.
 *
 * Hidden entirely off-cloud: LAN and Kiosk store no backend and have no internet, so the card would
 * offer an action that could not complete (task 23.4).
 */
@Composable
private fun CafeBundleCard(
    viewModel: com.razstudio.pos.ui.viewmodels.CafeBundleViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
) {
    if (!viewModel.isOffered()) return

    val state by viewModel.state.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val activity = LocalContext.current as? android.app.Activity

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = strings.cafeBundleTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = strings.cafeBundleDesc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.askToSave() },
                    enabled = !state.busy,
                ) { Text(strings.cafeBundleSave) }

                OutlinedButton(
                    onClick = { activity?.let { viewModel.remove(it) } },
                    enabled = !state.busy && activity != null,
                ) { Text(strings.cafeBundleRemove) }
            }

            state.outcome?.let { outcome ->
                val (text, isError) = when (outcome) {
                    CafeBundleViewModel.Outcome.SAVED -> strings.cafeBundleSaved to false
                    CafeBundleViewModel.Outcome.REMOVED -> strings.cafeBundleRemoved to false
                    CafeBundleViewModel.Outcome.UPLOAD_REJECTED -> strings.cafeBundleUploadRejected to true
                    CafeBundleViewModel.Outcome.NEEDS_CONSENT -> strings.cafeBundleNeedsConsent to true
                    CafeBundleViewModel.Outcome.NO_PERMISSION -> strings.cafeBundleNoPermission to true
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    // Task 23.9 — the trade, stated at the moment it is made. The owner recovery key is what proves
    // ownership of this café; putting it in a Google account means whoever reaches that account
    // reaches the café. That is worth saying plainly and once, here.
    if (state.confirming) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelSave() },
            title = { Text(strings.cafeBundleConsentTitle) },
            text = { Text(strings.cafeBundleConsentBody) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelSave()
                    activity?.let { viewModel.save(it) }
                }) { Text(strings.cafeBundleConsentConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelSave() }) { Text(strings.commonCancel) }
            },
        )
    }
}
