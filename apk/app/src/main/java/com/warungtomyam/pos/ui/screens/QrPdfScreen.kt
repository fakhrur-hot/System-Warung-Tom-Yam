package com.warungtomyam.pos.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.uiStrings
import com.warungtomyam.pos.ui.viewmodels.QrHeaderMode
import com.warungtomyam.pos.ui.viewmodels.QrPdfViewModel

/**
 * QR PDF generation screen.
 * Allows admin to select tables, enter café name, and generate a print-ready PDF
 * with QR code cards in A6 format, 4-up on A4 portrait pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrPdfScreen(
    viewModel: QrPdfViewModel = hiltViewModel(),
    onBack: () -> Unit,
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val tables by viewModel.tables.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    // Logo image picker (jpg/png); the ViewModel downscales to ≤1024px.
    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.pickLogo(it) } }

    // Show error in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Show success in snackbar
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.generateQrTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.commonBack
                        )
                    }
                },
                actions = {
                    // Share button (only visible after generation)
                    if (uiState.generatedUri != null) {
                        IconButton(onClick = {
                            viewModel.getShareIntent()?.let { intent ->
                                context.startActivity(intent)
                            }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = strings.sharePdfButton)
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Card header — choose the café name (text, from Settings → Branding) or a logo image.
            Text(
                text = strings.cafeNameLabel,
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = uiState.headerMode == QrHeaderMode.TEXT,
                        onClick = { viewModel.setHeaderMode(QrHeaderMode.TEXT) }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.headerMode == QrHeaderMode.TEXT,
                    onClick = { viewModel.setHeaderMode(QrHeaderMode.TEXT) }
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = "${strings.qrHeaderTextOption}: ${uiState.cafeName.ifBlank { "—" }}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = uiState.headerMode == QrHeaderMode.LOGO,
                        onClick = { viewModel.setHeaderMode(QrHeaderMode.LOGO) }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.headerMode == QrHeaderMode.LOGO,
                    onClick = { viewModel.setHeaderMode(QrHeaderMode.LOGO) }
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(strings.qrHeaderLogoOption, style = MaterialTheme.typography.bodyMedium)
            }
            if (uiState.headerMode == QrHeaderMode.LOGO) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    uiState.logoPreview?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = strings.qrHeaderLogoOption,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    OutlinedButton(onClick = { logoPickerLauncher.launch("image/*") }) {
                        Text(strings.qrUploadLogo)
                    }
                    TextButton(onClick = { viewModel.resetLogo() }) {
                        Text(strings.qrResetLogo)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Selection controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${strings.tablesSelectedLabel} (${uiState.selectedTableIds.size}/${tables.size})",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.selectAll() }) {
                        Text(strings.selectAllButton)
                    }
                    TextButton(onClick = { viewModel.deselectAll() }) {
                        Text(strings.deselectAllButton)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Table list with checkboxes
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(tables, key = { it.id }) { table ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = table.id in uiState.selectedTableIds,
                            onCheckedChange = { viewModel.toggleTableSelection(table.id) }
                        )
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
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Generate / Share buttons
            if (uiState.generatedUri != null) {
                OutlinedButton(
                    onClick = {
                        viewModel.getShareIntent()?.let { intent ->
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text("  ${strings.sharePdfButton}", modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.clearSuccess()
                        viewModel.generatePdf()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(strings.generateNewPdfButton)
                }
            } else {
                Button(
                    onClick = { viewModel.generatePdf() },
                    enabled = !uiState.isGenerating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(if (uiState.isGenerating) strings.generatingLabel else strings.generatePdfButton)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
