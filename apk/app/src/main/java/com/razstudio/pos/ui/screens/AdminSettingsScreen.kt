package com.razstudio.pos.ui.screens

import android.Manifest
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.razstudio.pos.ui.components.PermissionSettingsDialog
import com.razstudio.pos.ui.components.rememberPermissionHelper
import com.razstudio.pos.ui.i18n.AppLanguage
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import androidx.compose.ui.res.stringResource
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.razstudio.pos.R
import com.razstudio.pos.ui.viewmodels.AdminSettingsViewModel

/**
 * Admin Settings screen with sections:
 * - Staff Permissions (SEND_TO_KITCHEN / TAKE_PAYMENT toggles)
 * - Café Location (GPS lock with capture + radius)
 * - Café Profile (name + logo upload with pipeline)
 * Note: Staff Invitation has been moved to the Devices screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSettingsScreen(
    viewModel: AdminSettingsViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToPrinters: () -> Unit = {},
    onNavigateToHardwareDevices: () -> Unit = {},
    onNavigateToCashDrawerSettings: () -> Unit = {},
    onNavigateToKeepAliveSetup: () -> Unit = {},
    languageViewModel: LanguageViewModel = hiltViewModel(),
    pinLockViewModel: com.razstudio.pos.ui.viewmodels.PinLockViewModel = hiltViewModel(),
    devicePrefsViewModel: com.razstudio.pos.ui.viewmodels.DevicePrefsViewModel = hiltViewModel(),
    sessionViewModel: com.razstudio.pos.ui.viewmodels.AdminSessionViewModel = hiltViewModel(),
    modeViewModel: com.razstudio.pos.ui.viewmodels.ModeViewModel = hiltViewModel()
) {
    // Task 11.4 / Requirements 7.4, 7.5. Collected from a StateFlow, so the gate is re-evaluated on
    // every recomposition — which is what makes rotation and back-stack restore safe: there is no
    // cached "was visible" flag to restore, the field simply is not emitted when the capability is
    // false. There is no deep link to an individual setting, so the screen is the only entry point.
    val capabilities by modeViewModel.capabilities.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    // A secondary-admin device has no local printer — it prints via the Main Admin — so the
    // two hardware entries are greyed rather than hidden: the café can see the capability
    // exists and understand why this particular till does not have it.
    LaunchedEffect(Unit) { sessionViewModel.refreshRole() }
    val currentRole by sessionViewModel.currentRole.collectAsState()
    val localHardwareAllowed = currentRole != com.razstudio.pos.data.SecureStorage.Role.ADMIN_SECONDARY

    // PIN lock state (device-local, immediate — not part of the staged Save/Cancel bar).
    var pinLockEnabled by remember { mutableStateOf(pinLockViewModel.isPinLockEnabled()) }
    var showSetPin by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
    val secureStorage = remember { com.razstudio.pos.data.SecureStorage(context) }
    var showDrawerPin by remember { mutableStateOf(false) }
    var showUnlinkConfirm by remember { mutableStateOf(false) }
    val unlinkViewModel: com.razstudio.pos.ui.viewmodels.UnlinkDeviceViewModel = hiltViewModel()
    var fullscreenMode by remember { mutableStateOf(devicePrefsViewModel.fullscreenMode()) }
    val setFullscreen: (Boolean) -> Unit = { enable ->
        devicePrefsViewModel.setFullscreenMode(enable)
        fullscreenMode = enable
        com.razstudio.pos.ui.util.FullscreenMode.activityOf(context)?.let {
            com.razstudio.pos.ui.util.FullscreenMode.apply(it, enable)
        }
    }
    var hasDrawerPin by remember { mutableStateOf(secureStorage.hasCustomDrawerPin()) }
    // Device-local UI prefs (immediate).
    var showPrintStatus by remember { mutableStateOf(devicePrefsViewModel.showPrintStatus()) }

    // New-order alert sound (device-local, immediate).
    val soundPlayer = devicePrefsViewModel.newOrderSound
    var alertSoundTitle by remember { mutableStateOf(soundPlayer.currentTitle()) }
    var alertVolume by remember { mutableStateOf(soundPlayer.volumePercent()) }
    // System NOTIFICATION-tone picker (TYPE_NOTIFICATION, so it lists notification tones, not ringtones). A null EXTRA_RINGTONE_PICKED_URI means the operator chose "Silent",
    // which is a real choice — not a cancel — so it must be persisted as such.
    val notificationTonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked = result.data?.let {
                IntentCompat.getParcelableExtra(
                    it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java
                )
            }
            soundPlayer.setUri(picked)
            alertSoundTitle = soundPlayer.currentTitle()
        }
    }

    // Ambient / screensaver prefs (device-local, immediate — this terminal's physical situation).
    val ambientStore = remember { com.razstudio.pos.data.local.AmbientSettingsStore(context) }
    var ambientEnabled by remember { mutableStateOf(ambientStore.isEnabled()) }
    var ambientTimeout by remember { mutableStateOf(ambientStore.getTimeoutMinutes()) }
    var ambientCustomerFacing by remember { mutableStateOf(ambientStore.isCustomerFacing()) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.settingsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.commonBack)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // === Staff Permissions ===
            SettingsCard(title = strings.staffPermissionsSection) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.staffCanSendKitchenLabel)
                    Switch(
                        checked = uiState.staffCanSendKitchen,
                        onCheckedChange = { viewModel.updateStaffCanSendKitchen(it) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.staffCanTakePaymentLabel)
                    Switch(
                        checked = uiState.staffCanTakePayment,
                        onCheckedChange = { viewModel.updateStaffCanTakePayment(it) }
                    )
                }
            }


            // === Default Language (café-wide, per surface) ===
            SettingsCard(title = strings.defaultLanguageSection) {
                LanguagePickerRow(
                    label = strings.defaultLangAdminLabel,
                    selectedCode = uiState.defaultLangAdmin,
                    onSelect = { viewModel.updateDefaultLangAdmin(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                LanguagePickerRow(
                    label = strings.defaultLangOrderingLabel,
                    selectedCode = uiState.defaultLangOrdering,
                    onSelect = { viewModel.updateDefaultLangOrdering(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Task 11.4: the customer surface is the website. No website, no default to set.
                if (capabilities.customerQrOrdering) {
                    LanguagePickerRow(
                        label = strings.defaultLangCustomerLabel,
                        selectedCode = uiState.defaultLangCustomer,
                        onSelect = { viewModel.updateDefaultLangCustomer(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                // Printer language — all 5 supported. Latin (BM/EN) prints as fast ESC/POS text;
                // Chinese/Tamil/Thai auto-render as a bitmap so the glyphs print correctly.
                // Always used for slips & receipts regardless of any device's UI language.
                LanguagePickerRow(
                    label = strings.printerLanguageLabel,
                    selectedCode = uiState.printLanguage,
                    onSelect = { viewModel.updatePrintLanguage(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.defaultLanguageHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }


            // === Customer Order ===
            // "Hold before kitchen" delay for customer-placed orders. The order waits this
            // long (customer can cancel) before it's sent to the kitchen. Admin/staff orders
            // use a fixed short hold, not this value.
            //
            // Task 11.4: the whole section is customer-web pacing, so off-cloud it configures a flow
            // that does not exist. Hidden rather than disabled — a café that cannot have web ordering
            // should not be shown a dial for it and left wondering what it does.
            if (capabilities.customerQrOrdering) {
            SettingsCard(title = strings.customerOrderSection) {
                // Auto-print vs buffer-to-pending-modal.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.autoPrintToKitchenLabel)
                    Switch(
                        checked = uiState.autoPrintToKitchen,
                        onCheckedChange = { viewModel.updateAutoPrintToKitchen(it) }
                    )
                }
                Text(
                    text = if (uiState.autoPrintToKitchen)
                        strings.autoPrintOnDesc
                    else
                        strings.autoPrintOffDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = strings.holdBeforeKitchenLabel,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(10, 15, 30, 60).forEach { seconds ->
                        val selected = uiState.holdSeconds == seconds
                        if (selected) {
                            Button(
                                onClick = { viewModel.updateHoldSeconds(seconds) },
                                modifier = Modifier.weight(1f)
                            ) { Text("${seconds}s") }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.updateHoldSeconds(seconds) },
                                modifier = Modifier.weight(1f)
                            ) { Text("${seconds}s") }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.todaysSpecial,
                    onValueChange = { viewModel.updateTodaysSpecial(it) },
                    label = { Text(strings.todaysSpecialLabel) },
                    supportingText = { Text(strings.todaysSpecialHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            }


            // === Reports ===
            SettingsCard(title = strings.reportsTitle) {
                // No email recipient any more.
                //
                // The field wrote a `report_email` setting that the app never used: the Brevo send
                // lives in the reports-closing Edge Function, and nothing in the app had ever
                // called it. Owners were configuring a delivery that could not happen. The report
                // now lands on the device itself, which needs no mail provider and no account.
                Text(
                    text = strings.reportSavedToDownloads,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                // A 24-hour number field asked an owner to translate "we close at 2am" into 2, and
                // "we open at 3pm" into 15 — arithmetic nobody should do to describe their own
                // opening hours, and the commonest way to end up with a report anchored to the
                // wrong half of the day.
                HourPickerRow(
                    label = strings.businessDayStartLabel,
                    hour = uiState.businessDayStartHour,
                    onHourChange = { viewModel.updateBusinessDayStartHour(it) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                HourPickerRow(
                    label = strings.businessDayEndLabel,
                    hour = uiState.businessDayEndHour,
                    onHourChange = { viewModel.updateBusinessDayEndHour(it) },
                )
                Text(
                    text = strings.businessDayHoursHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }


            // === Security (device-local, applied immediately) ===
            SettingsCard(title = strings.securitySection) {
                // The whole row is the control, not just the switch.
                //
                // On a 1280dp-wide till the label and the switch are a hand's width apart, and
                // tapping the label — which is what everyone does — did nothing at all. The lock
                // was wired correctly the whole time and read as broken, because the only live
                // pixels were the ones nobody aimed at.
                val togglePinLock: (Boolean) -> Unit = { enable ->
                    if (enable) {
                        showSetPin = true   // set a PIN before turning the lock on
                    } else {
                        pinLockViewModel.disable()
                        pinLockEnabled = false
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { togglePinLock(!pinLockEnabled) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.pinLockLabel)
                    Switch(checked = pinLockEnabled, onCheckedChange = togglePinLock)
                }
                Text(
                    text = strings.pinLockDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pinLockEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showChangePin = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(strings.changePinButton) }
                }

                // ── Unlink ───────────────────────────────────────────────────────────────
                // Last, and behind a confirmation: this is the most destructive control in the
                // app, and the only one that cannot be undone from inside the app afterwards.
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(strings.unlinkDeviceLabel, fontWeight = FontWeight.Bold)
                Text(
                    text = strings.unlinkDeviceDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showUnlinkConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(strings.unlinkDeviceButton) }
            }


            // === Printing & hardware (this terminal) ===
            //
            // Printers and Devices & Hardware moved here from Café Management. They describe
            // THIS till — which printer it talks to, whether it has a drawer — not the café,
            // so they do not belong beside the menu and the floor plan, and they do not travel
            // to a replacement device the way café settings do.
            //
            // The print toggles below were in "Security", which they never had anything to do
            // with; they are printer behaviour and sit with the printers now.
            SettingsCard(title = strings.settingsHardwareSection) {
                SettingsNavRow(
                    title = strings.printersTitle,
                    description = strings.printersManagementDesc,
                    enabled = localHardwareAllowed,
                    onClick = onNavigateToPrinters,
                )
                SettingsNavRow(
                    title = strings.devicesAndHardwareTitle,
                    description = strings.devicesAndHardwareDesc,
                    enabled = localHardwareAllowed,
                    onClick = onNavigateToHardwareDevices,
                )
                // Drawer + printer-transport + auto-cut settings, moved out of Devices &
                // Hardware (cash-drawer-settings R2.8). Same localHardwareAllowed gate as its
                // siblings: a Secondary Admin has no local printer, so no drawer either (R5.3).
                SettingsNavRow(
                    title = "Cash Drawer",
                    description = "Enable the drawer, choose which printer kicks it, paper auto-cut.",
                    enabled = localHardwareAllowed,
                    onClick = onNavigateToCashDrawerSettings,
                )
                // Battery/OEM plumbing that keeps the till awake — the same "this terminal"
                // concern as its printer, and previously stranded in the home overflow menu
                // beside Reports and Backup, which are café-wide.
                SettingsNavRow(
                    title = strings.backgroundSetupTitle,
                    description = strings.backgroundSetupDesc,
                    enabled = true,
                    onClick = onNavigateToKeepAliveSetup,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.showPrintStatusLabel)
                    Switch(
                        checked = showPrintStatus,
                        onCheckedChange = {
                            devicePrefsViewModel.setShowPrintStatus(it); showPrintStatus = it
                        }
                    )
                }
                Text(
                    text = strings.showPrintStatusDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // === New-order alert sound (device-local, applied immediately) ===
            // Uses the SYSTEM ringtone picker so the operator gets every tone already on the
            // device (plus any they've added) rather than a bundled subset.
            SettingsCard(title = strings.alertSoundSection) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.alertSoundLabel)
                        Text(
                            text = alertSoundTitle ?: strings.alertSoundSilent,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = {
                        notificationTonePicker.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_TYPE,
                                    RingtoneManager.TYPE_NOTIFICATION
                                )
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_TITLE,
                                    strings.alertSoundPickerTitle
                                )
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                // Pre-select whatever is configured now.
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    soundPlayer.currentUri()
                                )
                            }
                        )
                    }) { Text(strings.alertSoundChoose) }
                }
                Text(
                    text = strings.alertSoundDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${strings.alertSoundVolume}  $alertVolume%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = alertVolume.toFloat(),
                    onValueChange = { alertVolume = it.toInt() },
                    // Persist on release rather than on every pixel of the drag.
                    onValueChangeFinished = { soundPlayer.setVolumePercent(alertVolume) },
                    valueRange = 0f..100f,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        // Commit first so Test always reflects the slider's current position.
                        soundPlayer.setVolumePercent(alertVolume)
                        soundPlayer.play(respectThrottle = false)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(strings.alertSoundTest) }
            }


            // === Screen (device-local, applied immediately) ===
            SettingsCard(title = strings.screenSection) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { setFullscreen(!fullscreenMode) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.fullscreenLabel)
                    Switch(checked = fullscreenMode, onCheckedChange = setFullscreen)
                }
                Text(
                    text = strings.fullscreenDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // === Ambient / screensaver mode (device-local, applied immediately) ===
            // Describes THIS terminal's physical situation (powered counter, guest-visible screen),
            // so it is stored per device rather than café-wide.
            SettingsCard(title = strings.ambientSection) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.ambientEnableLabel)
                    Switch(
                        checked = ambientEnabled,
                        onCheckedChange = { ambientStore.setEnabled(it); ambientEnabled = it }
                    )
                }
                Text(
                    text = strings.ambientEnableDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (ambientEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(strings.ambientStartAfter, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.razstudio.pos.data.local.AmbientSettingsStore.TIMEOUT_OPTIONS.forEach { minutes ->
                            FilterChip(
                                selected = ambientTimeout == minutes,
                                onClick = {
                                    ambientStore.setTimeoutMinutes(minutes)
                                    ambientTimeout = minutes
                                },
                                label = { Text(strings.ambientMinutes.format(minutes)) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.ambientGuestVisibleLabel)
                        Switch(
                            checked = ambientCustomerFacing,
                            onCheckedChange = {
                                ambientStore.setCustomerFacing(it); ambientCustomerFacing = it
                            }
                        )
                    }
                    Text(
                        text = strings.ambientGuestVisibleDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))


            // Third-party licence notices. This app ships proprietary (see LICENSE), but it is built
            // on MIT- and Apache-2.0-licensed components, both of which require their copyright
            // notices and licence texts to be reproduced in a distributed binary. This screen is that
            // reproduction, and it is the project's only hard open-source obligation — the generated
            // resources exist whether or not anything opens them, so removing this entry point would
            // put the app in breach while still appearing to build correctly.
            //
            // Labels come from res/values rather than UiStrings because the licence texts themselves
            // are English-only; see THIRD-PARTY-NOTICES.md for the audit and the one component
            // (ZXing) the generator cannot detect.
            SettingsCard(title = strings.aboutSection) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, OssLicensesMenuActivity::class.java)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(strings.openSourceLicenses)
                }

                // The generated screen is the compliance record, but it has two holes worth naming
                // on screen rather than only in THIRD-PARTY-NOTICES.md: a debug build emits a
                // placeholder instead of the real list, and the generator does not detect ZXing at
                // all. Reproducing ZXing's notice here is what keeps the in-app record complete.
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.licenceGeneratorNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.licenceExtraNotices,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Set / change admin PIN dialogs
    if (showSetPin) {
        com.razstudio.pos.ui.components.SetPinDialog(
            strings = strings,
            title = strings.setPinTitle,
            onSet = { pin ->
                pinLockViewModel.enableWithPin(pin)
                pinLockEnabled = true
                showSetPin = false
            },
            onCancel = { showSetPin = false } // toggle stays off if they back out
        )
    }
    if (showUnlinkConfirm) {
        AlertDialog(
            onDismissRequest = { showUnlinkConfirm = false },
            title = { Text(strings.unlinkConfirmTitle) },
            text = { Text(strings.unlinkConfirmBody) },
            confirmButton = {
                TextButton(onClick = {
                    showUnlinkConfirm = false
                    // Restarted rather than navigated: every ViewModel on the back stack is
                    // holding data for a café that no longer exists on this device.
                    unlinkViewModel.unlink { viewModel.restartApp() }
                }) { Text(strings.unlinkDeviceButton) }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkConfirm = false }) { Text(strings.commonCancel) }
            },
        )
    }
    if (showDrawerPin) {
        com.razstudio.pos.ui.components.DrawerPinDialog(
            strings = strings,
            // Always required, even before the café has set its own: the shipped default is
            // still the PIN that opens the drawer right now, and skipping the check would let
            // anyone holding the phone re-key the till without knowing anything at all.
            requiresCurrent = true,
            onVerifyCurrent = { it == secureStorage.getDrawerPin() },
            onSet = { pin ->
                secureStorage.saveDrawerPin(pin)
                hasDrawerPin = true
                showDrawerPin = false
            },
            onDismiss = { showDrawerPin = false },
        )
    }
    if (showChangePin) {
        com.razstudio.pos.ui.components.ChangePinDialog(
            strings = strings,
            onChange = { current, new -> pinLockViewModel.changePin(current, new) },
            onDone = { showChangePin = false },
            onCancel = { showChangePin = false }
        )
    }
}

/**
 * One settings category.
 *
 * Every group on this screen is the same card so the eye can find the boundaries: the screen is
 * long, it mixes café-wide settings with per-device ones, and the previous bold-heading-plus-rule
 * rhythm left it reading as a single unbroken column. Matches `HardwareDevicesScreen`'s card
 * exactly, since that screen is now reached from this one.
 */
@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            content()
        }
    }
}

/** A row inside a card that opens another screen, rather than toggling something in place. */
@Composable
private fun SettingsNavRow(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A labelled language selector: the label on the left, a dropdown showing the currently
 * selected language on the right. [selectedCode] / [onSelect] use the café server codes
 * (BM/EN/ZH/TA/TH); option labels come from [AppLanguage.displayName].
 */
@Composable
private fun LanguagePickerRow(
    label: String,
    selectedCode: String,
    onSelect: (String) -> Unit,
    options: List<AppLanguage> = AppLanguage.entries
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = AppLanguage.fromServerCode(selectedCode)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selected.displayName)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.displayName) },
                        onClick = {
                            expanded = false
                            onSelect(lang.serverCode)
                        }
                    )
                }
            }
        }
    }
}

/**
 * An hour of the day, chosen in 12-hour time.
 *
 * Stored as 0–23 throughout — the backend, the reports and the trading-day maths all speak 24-hour,
 * and converting at the edge keeps one representation in the data and the familiar one on screen.
 * The dropdown lists all 24 hours already formatted, so there is no separate AM/PM control to get
 * out of step with the hour beside it.
 */
@Composable
private fun HourPickerRow(
    label: String,
    hour: Int,
    onHourChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(formatHour12(hour))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (0..23).forEach { h ->
                    DropdownMenuItem(
                        text = { Text(formatHour12(h)) },
                        onClick = {
                            expanded = false
                            onHourChange(h)
                        },
                    )
                }
            }
        }
    }
}

/** 0 -> "12:00 AM", 13 -> "1:00 PM". Midnight and noon are the two everyone gets wrong. */
private fun formatHour12(hour: Int): String {
    val h = ((hour % 12) + 12) % 12
    val display = if (h == 0) 12 else h
    val suffix = if (hour % 24 < 12) "AM" else "PM"
    return "$display:00 $suffix"
}
