package com.razstudio.pos.ui.screens

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.filled.QrCode2
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
import com.razstudio.pos.data.DeviceDto
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.util.ImageSaver
import com.razstudio.pos.ui.util.QrCodeUtil
import com.razstudio.pos.ui.viewmodels.AdminSettingsViewModel
import com.razstudio.pos.ui.viewmodels.DevicesViewModel
import kotlinx.coroutines.delay

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
    modeViewModel: com.razstudio.pos.ui.viewmodels.ModeViewModel = hiltViewModel(),
    onBack: () -> Unit,
    /**
     * Show the LAN pairing QR. Lives here because pairing a staff device *is* device management —
     * and because "Host this café" on the home screen now opens the till instead, which is where an
     * owner expects that button to go. Without this the QR would be unreachable.
     */
    onPairStaffDevice: () -> Unit = {},
) {
    val capabilities by modeViewModel.capabilities.collectAsState()
    val mode by modeViewModel.mode.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val isSecondaryAdmin = viewModel.isSecondaryAdmin
    var renameDevice by remember { mutableStateOf<DeviceDto?>(null) }
    var renameText by remember { mutableStateOf("") }
    // Reveals the secondary-admin invite QR (loaded on demand, separate from staff invite).
    var showAdminInvite by remember { mutableStateOf(false) }
    // Reveals the permanent owner-recovery QR (loaded on demand).
    var showRecovery by remember { mutableStateOf(false) }
    // Requests already approved, rejected, or dismissed here — so one popup does not reappear.
    var handledRequestIds by remember { mutableStateOf(setOf<String>()) }
    val pendingRequests by viewModel.pendingRequests.collectAsState()

    // Keep this screen live while it is open.
    //
    // `loadDevices()` previously ran only once, from the ViewModel's `init`. An admin who opened
    // Devices & Staff and then asked a staff member to scan the invite — which is exactly when this
    // screen is open — watched a list that could never show the device arriving, and got no popup
    // either: the approve/reject prompt existed solely on the admin home screen, whose own comment
    // claimed it was there "not only when the Devices page is open". It was.
    //
    // Same 8s cadence as the home screen, so approving from either place feels identical.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshPendingRequests()
            viewModel.loadDevices()
            delay(8_000)
        }
    }

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
                },
                actions = {
                    // LAN only. A Cloud café's staff join through the website invite link, and a
                    // Kiosk has no peers at all, so the pairing QR would be an action with nothing
                    // on the other end of it.
                    if (mode == com.razstudio.pos.data.OperatingMode.LAN) {
                        IconButton(onClick = onPairStaffDevice) {
                            Icon(
                                Icons.Default.QrCode2,
                                contentDescription = strings.pairStaffDeviceButton,
                            )
                        }
                    }
                },
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

                // Task 21.2 / Requirement 4.3.2: show where this device can actually be reached,
                // so the operator can confirm the network is up BEFORE holding a QR at a staff phone.
                // Task 21.3: when there is no usable interface, say so instead of rendering a QR that
                // carries an address nothing can reach — the failure would otherwise only appear
                // minutes later as a phone that "won't connect", with nothing pointing at the network.
                if (!capabilities.websiteInvites) {
                    when (val addr = modeViewModel.lanAddress()) {
                        is com.razstudio.pos.data.lan.LanAddress.Result.Found -> Text(
                            text = "This device: ${addr.ip}  (${addr.interfaceName})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is com.razstudio.pos.data.lan.LanAddress.Result.Unavailable -> Text(
                            text = addr.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (settingsState.inviteLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else if (settingsState.invite != null) {
                    val inviteUrl = settingsState.invite!!.url

                    // What the QR actually encodes differs by topology, and getting this wrong is
                    // invisible until a staff phone fails to join.
                    //
                    // Cloud: a `https://…/join?invite=<token>` link. The token is IN the URL, so
                    // encoding the URL is enough, and it doubles as something shareable over
                    // WhatsApp.
                    //
                    // Off-cloud: `LocalBackend.getInvite` returns `http://<ip>:8765` as a *human*
                    // caption and the pairing token separately. Encoding that URL alone produced a
                    // QR with an address and no token — unusable, because registration consumes the
                    // token, and `OrderingConnectScreen` decodes `PairingQrPayload` rather than a
                    // bare URL. The two halves have to be put back together here, in the one format
                    // the scanner understands.
                    val qrContent = remember(inviteUrl, settingsState.invite!!.token, capabilities.websiteInvites) {
                        if (capabilities.websiteInvites) {
                            inviteUrl
                        } else {
                            val host = (modeViewModel.lanAddress() as? com.razstudio.pos.data.lan.LanAddress.Result.Found)?.ip
                            if (host == null) null else com.razstudio.pos.data.lan.PairingQrPayload(
                                host = host,
                                port = com.razstudio.pos.data.lan.PairingQrPayload.PORT,
                                pairingToken = settingsState.invite!!.token,
                            ).encode()
                        }
                    }
                    val qrBitmap = remember(qrContent) { qrContent?.let { QrCodeUtil.encode(it, 512) } }
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
                        // Task 11.1 / Requirement 7.2: sharing the invite as a link only works when
                        // it IS a link. Off-cloud the invite address is this device's LAN IP, so a
                        // shared "http://192.168.x.x:8765" is unopenable from WhatsApp and looks
                        // like a broken invite — the QR beside it is the working path, and it stays.
                        if (capabilities.websiteInvites) {
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

                // `secondaryAdmin` is false off-cloud by design (see ModeCapabilities): LAN Mode is
                // one ADMIN server plus N ORDERING clients, and Kiosk has no peers. The section used
                // to render regardless, offering a Wireless AP owner a role their café cannot hold —
                // and the invite behind it would have been minted against endpoints LocalBackend
                // does not implement.
                if (!isSecondaryAdmin) {
                    // `secondaryAdmin` is false off-cloud by design (see ModeCapabilities): LAN is
                    // one ADMIN server plus N ORDERING clients, and Kiosk has no peers. This used to
                    // render regardless, offering a Wireless AP owner a role their café cannot hold.
                    //
                    // The divider lives INSIDE the gate: a separator with nothing after it reads as
                    // a section that failed to load.
                    if (capabilities.secondaryAdmin) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    // === Add Secondary Admin ===
                    // A separate QR that grants an admin-level device with full management but no
                    // local printer (its orders print on this Main Admin). Loaded on demand.
                    Text(
                        text = strings.secondaryAdminSection,
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
                            Text(strings.addSecondaryAdmin)
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

                    }

                    // Owner recovery is a cloud concept: the token is minted by the `admin-recovery`
                    // endpoint and the QR carries a link into the café's website, neither of which
                    // exists off-cloud — `LocalBackend.getRecoveryToken` reports it unsupported.
                    // The equivalent safety net for a LAN or Kiosk café is the backup file and the
                    // Google bundle, both on the Backup screen.
                    if (mode == com.razstudio.pos.data.OperatingMode.CLOUD) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // === Owner Recovery Key (permanent) ===
                    Text(
                        text = strings.ownerRecoveryKeySection,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "A permanent key to restore Main Admin on a NEW phone if this one is lost or broken. Keep it secret — anyone who scans it gains full control.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Generating the key just reveals a QR in a timed modal — it does NOT touch
                    // this device's session (no logout). The modal auto-closes after 30s and the
                    // QR is saved to local storage as a PNG so the owner can keep/print it.
                    OutlinedButton(onClick = {
                        settingsViewModel.loadRecoveryToken()
                        showRecovery = true
                    }) { Text(strings.showOwnerRecoveryQr) }
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
                        isSecondaryAdmin = isSecondaryAdmin,
                        onApprove = { viewModel.approveDevice(device.id) },
                        onReject = { viewModel.rejectDevice(device.id) },
                        onRevoke = { viewModel.revokeDevice(device.id) },
                        onPromoteToMain = { viewModel.promoteToMain(device.id) },
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

    // Owner Recovery QR — timed modal. Reveals the QR (fetched without touching this device's
    // session, so no logout), auto-saves it to local storage as a PNG, and auto-closes after 30s.
    if (showRecovery) {
        OwnerRecoveryQrDialog(
            url = settingsState.recoveryInvite?.url,
            loading = settingsState.recoveryLoading,
            strings = strings,
            onDismiss = { showRecovery = false }
        )
    }

    // New device requesting to connect → the same approve/reject popup the admin home screen shows.
    // Duplicated deliberately rather than hoisted: the two screens own their own dialog state, and a
    // shared holder would have to outlive both. The selection rule below must stay identical to
    // AdminHomeScreen's — a Secondary Admin may approve staff, never another admin.
    val deviceRequest = pendingRequests.firstOrNull {
        it.id !in handledRequestIds &&
            !(isSecondaryAdmin && (it.role == "ADMIN" || it.role == "ADMIN_SECONDARY"))
    }
    if (deviceRequest != null) {
        LaunchedEffect(deviceRequest.id) {
            delay(30_000)
            handledRequestIds = handledRequestIds + deviceRequest.id
        }
        AlertDialog(
            onDismissRequest = { handledRequestIds = handledRequestIds + deviceRequest.id },
            title = { Text(strings.newDeviceRequestTitle) },
            text = { Text(strings.newDeviceRequestBody.format(deviceRequest.label)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.approveDevice(deviceRequest.id)
                    handledRequestIds = handledRequestIds + deviceRequest.id
                }) { Text(strings.approveButton) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.rejectDevice(deviceRequest.id)
                    handledRequestIds = handledRequestIds + deviceRequest.id
                }) { Text(strings.rejectButton, color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}

/**
 * Timed modal that shows the permanent Owner Recovery QR, auto-saves it as a PNG to local
 * storage the moment it loads, and auto-dismisses after 30 seconds (a "keep it on screen only
 * briefly" secret). Fetching/showing the key never mutates this device's admin session.
 */
@Composable
private fun OwnerRecoveryQrDialog(
    url: String?,
    loading: Boolean,
    strings: UiStrings,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var secondsLeft by remember { mutableStateOf(30) }
    var savedLocation by remember { mutableStateOf<String?>(null) }
    val qr = remember(url) { url?.let { QrCodeUtil.encode(it, 512) } }

    // Auto-save once as soon as the QR is ready.
    LaunchedEffect(qr) {
        if (qr != null && savedLocation == null) {
            savedLocation = ImageSaver.savePng(context, qr, "owner-recovery-qr")
            Toast.makeText(
                context,
                savedLocation?.let { "Saved recovery QR to $it" } ?: "Couldn't save the QR image",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 30-second countdown → auto-dismiss (only starts ticking once the QR is visible).
    LaunchedEffect(qr) {
        if (qr != null) {
            secondsLeft = 30
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft--
            }
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (qr != null) "Owner Recovery QR — closes in ${secondsLeft}s" else "Owner Recovery QR")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    loading || (url == null) -> {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                    qr != null -> {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "Owner recovery QR code",
                            modifier = Modifier.size(240.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Keep this secret. Scan it from a new phone's Admin login to regain Main Admin. A PNG copy was saved to your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        Text(
                            text = strings.recoveryKeyLoadFailed,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.commonClose) }
        },
        dismissButton = if (qr != null) {
            {
                TextButton(onClick = {
                    val loc = ImageSaver.savePng(context, qr, "owner-recovery-qr")
                    Toast.makeText(
                        context,
                        loc?.let { "Saved recovery QR to $it" } ?: "Couldn't save the QR image",
                        Toast.LENGTH_LONG
                    ).show()
                }) { Text(strings.savePngAgain) }
            }
        } else null
    )
}

@Composable
private fun DeviceCard(
    device: DeviceDto,
    isCurrent: Boolean,
    strings: UiStrings,
    isSecondaryAdmin: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRevoke: () -> Unit,
    onForceCheckOut: () -> Unit,
    onRename: () -> Unit,
    onPromoteToMain: () -> Unit = {}
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
                val isTargetAdmin = device.role == "ADMIN" || device.role == "ADMIN_SECONDARY"
                val isOtherAdmin = isTargetAdmin && !isCurrent
                val hideAdminActions = isSecondaryAdmin && isOtherAdmin

                when (device.status) {
                    "PENDING" -> {
                        if (!hideAdminActions) {
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
                    }
                    "APPROVED" -> {
                        // Promote a Secondary Admin to Main (printer host); demotes current Main.
                        if (device.role == "ADMIN_SECONDARY" && !isSecondaryAdmin) {
                            androidx.compose.material3.TextButton(onClick = onPromoteToMain) {
                                Text(strings.makeMainAdmin)
                            }
                        }
                        IconButton(onClick = onRename) {
                            Icon(Icons.Default.Edit, contentDescription = strings.commonEdit)
                        }
                        if (!hideAdminActions) {
                            IconButton(onClick = onRevoke) {
                                Icon(
                                    Icons.Default.RemoveCircle,
                                    contentDescription = strings.commonDelete,
                                    tint = Color(0xFFF44336)
                                )
                            }
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
