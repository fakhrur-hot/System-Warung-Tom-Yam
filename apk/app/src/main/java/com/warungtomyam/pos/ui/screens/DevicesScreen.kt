package com.warungtomyam.pos.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.warungtomyam.pos.data.DeviceDto
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.UiStrings
import com.warungtomyam.pos.ui.i18n.uiStrings
import com.warungtomyam.pos.ui.util.QrCodeUtil
import com.warungtomyam.pos.ui.viewmodels.AdminSettingsViewModel
import com.warungtomyam.pos.ui.viewmodels.DevicesViewModel

/**
 * Device management screen.
 * Shows the Staff Invitation section at the top (QR + share/regenerate),
 * followed by all devices: pending (highlighted), connected, revoked.
 * Actions: Approve, Reject, Revoke, Force Check-Out, Rename.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel = hiltViewModel(),
    settingsViewModel: AdminSettingsViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var renameDevice by remember { mutableStateOf<DeviceDto?>(null) }
    var renameText by remember { mutableStateOf("") }
    // Reveals the secondary-admin invite QR (loaded on demand, separate from staff invite).
    var showAdminInvite by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(settingsState.error) {
        settingsState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            settingsViewModel.clearMessages()
        }
    }

    LaunchedEffect(settingsState.successMessage) {
        settingsState.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            settingsViewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.devicesTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.commonBack)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // === Staff Invitation ===
            item {
                Text(
                    text = strings.staffInvitationSection,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (settingsState.inviteLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else if (settingsState.invite != null) {
                    val inviteUrl = settingsState.invite!!.url
                    val qrBitmap = remember(inviteUrl) { QrCodeUtil.encode(inviteUrl, 512) }
                    if (qrBitmap != null) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Staff invite QR code",
                                modifier = Modifier.size(220.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = inviteUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, settingsState.invite!!.url)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, strings.shareInviteLink)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.shareButton)
                        }
                        OutlinedButton(onClick = { settingsViewModel.regenerateInvite() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.regenerateButton)
                        }
                    }
                } else {
                    Text(strings.noInvitationAvailable, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // === Add Secondary Admin ===
                // A separate QR that grants an admin-level device with full management but no
                // local printer (its orders print on this Main Admin). Loaded on demand.
                Text(
                    text = "Secondary Admin",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Add another admin device with full management access but no printer. It prints through this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (!showAdminInvite) {
                    OutlinedButton(onClick = {
                        showAdminInvite = true
                        settingsViewModel.loadAdminInvite()
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Secondary Admin")
                    }
                } else if (settingsState.adminInviteLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else if (settingsState.adminInvite != null) {
                    val adminUrl = settingsState.adminInvite!!.url
                    val adminQr = remember(adminUrl) { QrCodeUtil.encode(adminUrl, 512) }
                    if (adminQr != null) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = adminQr.asImageBitmap(),
                                contentDescription = "Secondary admin invite QR code",
                                modifier = Modifier.size(220.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = adminUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, adminUrl)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, strings.shareInviteLink)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.shareButton)
                        }
                        OutlinedButton(onClick = { settingsViewModel.regenerateAdminInvite() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.regenerateButton)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Devices section header
                Text(
                    text = strings.connectedDevicesSection,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // === Device list ===
            if (uiState.isLoading && uiState.devices.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (uiState.devices.isEmpty()) {
                item {
                    Text(
                        text = strings.noDevicesRegistered,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                val grouped = uiState.devices.sortedBy { device ->
                    when (device.status) {
                        "PENDING" -> 0
                        "APPROVED" -> 1
                        else -> 2
                    }
                }
                items(grouped) { device ->
                    DeviceCard(
                        device = device,
                        isCurrent = device.deviceIdentifier.isNotBlank() &&
                            device.deviceIdentifier == uiState.currentDeviceId,
                        strings = strings,
                        onApprove = { viewModel.approveDevice(device.id) },
                        onReject = { viewModel.rejectDevice(device.id) },
                        onRevoke = { viewModel.revokeDevice(device.id) },
                        onForceCheckOut = { viewModel.forceCheckOut(device.id) },
                        onRename = {
                            renameDevice = device
                            renameText = device.label
                        }
                    )
                }
            }
        }
    }

    // Rename dialog
    if (renameDevice != null) {
        AlertDialog(
            onDismissRequest = { renameDevice = null },
            title = { Text(strings.renameDeviceTitle) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(strings.deviceLabelField) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameDevice?.let { device ->
                            viewModel.renameDevice(device.id, renameText)
                        }
                        renameDevice = null
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text(strings.commonSave)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDevice = null }) {
                    Text(strings.commonCancel)
                }
            }
        )
    }
}

@Composable
private fun DeviceCard(
    device: DeviceDto,
    isCurrent: Boolean,
    strings: UiStrings,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRevoke: () -> Unit,
    onForceCheckOut: () -> Unit,
    onRename: () -> Unit
) {
    val containerColor = when (device.status) {
        "PENDING" -> MaterialTheme.colorScheme.tertiaryContainer
        "APPROVED" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.label.ifBlank { strings.unnamedDevice },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (isCurrent) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.currentDeviceLabel,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row {
                        StatusBadge(device.status, strings)
                        if (device.role.isNotBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = device.role,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (device.isCheckedIn) {
                        Text(
                            text = strings.checkedInLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                    // Last-online time for every device except this one (whose status is
                    // simply "now"). Only meaningful for approved/revoked devices that have
                    // actually connected before.
                    if (!isCurrent) {
                        formatLastSeen(device.lastSeenAt)?.let { seen ->
                            Text(
                                text = "${strings.lastOnlineLabel}: $seen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Actions row
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (device.status) {
                    "PENDING" -> {
                        IconButton(onClick = onApprove) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = strings.commonConfirm,
                                tint = Color(0xFF4CAF50)
                            )
                        }
                        IconButton(onClick = onReject) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = strings.commonDelete,
                                tint = Color(0xFFF44336)
                            )
                        }
                    }
                    "APPROVED" -> {
                        IconButton(onClick = onRename) {
                            Icon(Icons.Default.Edit, contentDescription = strings.commonEdit)
                        }
                        IconButton(onClick = onRevoke) {
                            Icon(
                                Icons.Default.RemoveCircle,
                                contentDescription = strings.commonDelete,
                                tint = Color(0xFFF44336)
                            )
                        }
                        if (device.isCheckedIn) {
                            IconButton(onClick = onForceCheckOut) {
                                Icon(
                                    Icons.Default.ExitToApp,
                                    contentDescription = strings.commonDelete,
                                    tint = Color(0xFFFF9800)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Format a UTC ISO-8601 timestamp (as returned by the backend `last_seen_at`) into a
 * short local "dd/MM HH:mm" string for display. Returns null for blank/unparseable input
 * so the caller can simply omit the line. Locale-neutral numeric format on purpose — only
 * the surrounding label is translated.
 */
private fun formatLastSeen(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val instant = try {
            java.time.OffsetDateTime.parse(iso).toInstant()
        } catch (_: Exception) {
            java.time.Instant.parse(iso)
        }
        java.time.format.DateTimeFormatter
            .ofPattern("dd/MM HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun StatusBadge(status: String, strings: UiStrings) {
    val (text, color) = when (status) {
        "PENDING" -> strings.pendingStatus to Color(0xFFFF9800)
        "APPROVED" -> strings.connectedStatus to Color(0xFF4CAF50)
        "REVOKED" -> strings.revokedStatus to Color(0xFFF44336)
        else -> status to MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = FontWeight.Medium
    )
}
