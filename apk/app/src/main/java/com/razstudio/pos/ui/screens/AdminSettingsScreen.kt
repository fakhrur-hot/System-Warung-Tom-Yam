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
    languageViewModel: LanguageViewModel = hiltViewModel(),
    pinLockViewModel: com.razstudio.pos.ui.viewmodels.PinLockViewModel = hiltViewModel(),
    devicePrefsViewModel: com.razstudio.pos.ui.viewmodels.DevicePrefsViewModel = hiltViewModel(),
    presetViewModel: com.razstudio.pos.ui.viewmodels.MenuPresetViewModel = hiltViewModel(),
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

    // Menu preset loader (deep in settings): replaces the whole menu, then soft-restarts.
    val presetLoading by presetViewModel.loading.collectAsState()
    var showPresetConfirm by remember { mutableStateOf(false) }

    // PIN lock state (device-local, immediate — not part of the staged Save/Cancel bar).
    var pinLockEnabled by remember { mutableStateOf(pinLockViewModel.isPinLockEnabled()) }
    var showSetPin by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
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

    // Location permission helper
    val locationPermHelper = rememberPermissionHelper(Manifest.permission.ACCESS_FINE_LOCATION)

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.processLogo(it) }
    }

    // Payment QR picker (task 16.1). Separate launcher from the logo one so the two uploads cannot be
    // confused — they go through different pipelines with different rules: the logo is center-cropped
    // and JPEG-compressed for thermal printing, which would smear a dense QR until it stops scanning.
    // The MIME type is passed through so PaymentQrPipeline can keep a PNG lossless.
    val paymentQrPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.processPaymentQr(it, context.contentResolver.getType(it)) }
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
        bottomBar = {
            // Sticky Save / Cancel — always visible so changes are never "lost" off-screen.
            // Disabled until something changes (isDirty); shows a spinner while persisting.
            val isAnyLoading = uiState.permissionsLoading || uiState.locationLoading || uiState.brandingLoading
            androidx.compose.material3.Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.cancelAll() },
                        enabled = uiState.isDirty && !isAnyLoading,
                        modifier = Modifier.weight(1f)
                    ) { Text(strings.commonCancel) }
                    Button(
                        onClick = { viewModel.saveAll() },
                        enabled = uiState.isDirty && !isAnyLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isAnyLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(strings.commonSave)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // === Café Profile (identity — shown first) ===
            SettingsSection(title = strings.cafeProfileSection) {
                OutlinedTextField(
                    value = uiState.cafeName,
                    onValueChange = { viewModel.updateCafeName(it) },
                    label = { Text(strings.cafeNameLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Logo preview — a freshly-picked local image takes priority; otherwise
                // fall back to whatever logo is already saved server-side, so the
                // screen doesn't look like the logo was lost on a fresh install/relogin.
                when {
                    uiState.logoPreview != null -> {
                        Image(
                            bitmap = uiState.logoPreview!!.asImageBitmap(),
                            contentDescription = "Logo preview",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    uiState.existingLogoUrl != null -> {
                        AsyncImage(
                            model = uiState.existingLogoUrl,
                            contentDescription = "Current logo",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val hasLogo = uiState.logoPreview != null || uiState.existingLogoUrl != null
                    Text(if (hasLogo) strings.changeLogoButton else strings.pickLogoButton)
                }

                if (uiState.brandingLoading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }

                // Save Branding button removed — committed via the sticky Save/Cancel bar
            }

            HorizontalDivider()

            // === Staff Permissions ===
            SettingsSection(title = strings.staffPermissionsSection) {
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

            HorizontalDivider()

            // === Default Language (café-wide, per surface) ===
            SettingsSection(title = strings.defaultLanguageSection) {
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

            HorizontalDivider()

            // === Café Location (GPS Lock) ===
            SettingsSection(title = strings.cafeLocationSection) {
                Button(
                    onClick = {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPerm) {
                            // Get GPS fix
                            try {
                                val locationManager = context.getSystemService(
                                    android.content.Context.LOCATION_SERVICE
                                ) as LocationManager
                                val location = locationManager.getLastKnownLocation(
                                    LocationManager.GPS_PROVIDER
                                ) ?: locationManager.getLastKnownLocation(
                                    LocationManager.NETWORK_PROVIDER
                                )
                                if (location != null) {
                                    viewModel.onLocationCaptured(
                                        location.latitude,
                                        location.longitude
                                    )
                                }
                            } catch (e: SecurityException) {
                                // Shouldn't happen if permission granted
                            }
                        } else {
                            locationPermHelper.request()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.captureLocationButton)
                }

                if (uiState.latitude != null && uiState.longitude != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Lat: ${String.format("%.6f", uiState.latitude)}, " +
                                "Lng: ${String.format("%.6f", uiState.longitude)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.radiusMeters.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { viewModel.updateRadius(it) }
                    },
                    label = { Text(strings.radiusLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Auto-detected timezone (set when location is captured) — synced across
                // reports, kitchen slips, receipts and attendance on Save.
                if (uiState.timezone.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${strings.timezoneLabel}: ${uiState.timezone}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Save Location button removed — committed via the bottom Save/Cancel bar
            }

            HorizontalDivider()

            // === Customer Order ===
            // "Hold before kitchen" delay for customer-placed orders. The order waits this
            // long (customer can cancel) before it's sent to the kitchen. Admin/staff orders
            // use a fixed short hold, not this value.
            //
            // Task 11.4: the whole section is customer-web pacing, so off-cloud it configures a flow
            // that does not exist. Hidden rather than disabled — a café that cannot have web ordering
            // should not be shown a dial for it and left wondering what it does.
            if (capabilities.customerQrOrdering) {
            SettingsSection(title = strings.customerOrderSection) {
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

            HorizontalDivider()

            // === Reports ===
            SettingsSection(title = strings.reportsTitle) {
                OutlinedTextField(
                    value = uiState.reportEmail,
                    onValueChange = { viewModel.updateReportEmail(it) },
                    label = { Text(strings.reportEmailLabel) },
                    supportingText = { Text(strings.reportEmailHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.businessDayStartHour.toString(),
                    onValueChange = { v ->
                        v.filter { it.isDigit() }.take(2).toIntOrNull()?.let {
                            viewModel.updateBusinessDayStartHour(it)
                        }
                        if (v.isBlank()) viewModel.updateBusinessDayStartHour(0)
                    },
                    label = { Text(strings.businessDayStartLabel) },
                    supportingText = {
                        Text(strings.businessDayStartHint)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            HorizontalDivider()

            // === Security (device-local, applied immediately) ===
            SettingsSection(title = strings.securitySection) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.pinLockLabel)
                    Switch(
                        checked = pinLockEnabled,
                        onCheckedChange = { enable ->
                            if (enable) {
                                showSetPin = true   // set a PIN before turning the lock on
                            } else {
                                pinLockViewModel.disable()
                                pinLockEnabled = false
                            }
                        }
                    )
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

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.receiptLogoLabel)
                    Switch(
                        checked = uiState.receiptLogo,
                        onCheckedChange = { viewModel.updateReceiptLogo(it) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.escAsteriskLabel)
                    Switch(
                        checked = uiState.escAsteriskMode,
                        onCheckedChange = { viewModel.updateEscAsteriskMode(it) }
                    )
                }
                Text(
                    text = strings.escAsteriskDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))
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

            HorizontalDivider()

            // === New-order alert sound (device-local, applied immediately) ===
            // Uses the SYSTEM ringtone picker so the operator gets every tone already on the
            // device (plus any they've added) rather than a bundled subset.
            SettingsSection(title = strings.alertSoundSection) {
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

            HorizontalDivider()

            // === Ambient / screensaver mode (device-local, applied immediately) ===
            // Describes THIS terminal's physical situation (powered counter, guest-visible screen),
            // so it is stored per device rather than café-wide.
            SettingsSection(title = strings.ambientSection) {
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

            HorizontalDivider()

            // === Menu Preset (advanced — replaces the entire menu) ===
            SettingsSection(title = strings.menuPresetSection) {
                OutlinedButton(
                    onClick = { showPresetConfirm = true },
                    enabled = !presetLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (presetLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(strings.loadPresetTani)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Payment QR (task 16.1, Requirements 14.1–14.3, 14.5) ─────────────────────────────
            // Available in ALL three operating modes (Requirement 14.7), so this section is never
            // gated on ModeCapabilities. Visibility of the *Show QR* button elsewhere depends solely
            // on whether a hash is stored — absence of a hash IS the "not configured" state, so there
            // is no separate enabled flag that could drift from whether the image really exists.
            SettingsSection(title = strings.paymentQrSection) {
                Text(
                    text = strings.paymentQrHelp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                val qrPreview = uiState.paymentQrPreview
                if (qrPreview != null && uiState.paymentQrHash != null) {
                    Image(
                        bitmap = qrPreview.asImageBitmap(),
                        contentDescription = strings.paymentQrSection,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { paymentQrPickerLauncher.launch("image/*") },
                            enabled = !uiState.paymentQrBusy,
                        ) { Text(strings.paymentQrReplace) }
                        OutlinedButton(
                            onClick = { viewModel.removePaymentQr() },
                            enabled = !uiState.paymentQrBusy,
                        ) { Text(strings.removeButton) }
                    }
                } else {
                    Text(
                        text = strings.paymentQrNone,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { paymentQrPickerLauncher.launch("image/*") },
                        enabled = !uiState.paymentQrBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.paymentQrBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(strings.paymentQrUpload)
                    }
                }

                // Rejection reason shown inline rather than as a snackbar: "this image has no QR code
                // in it" is a correction the admin must act on, and a transient toast is too easy to
                // miss while they are looking at the picker result (Requirement 14.3).
                uiState.paymentQrError?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
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
            SettingsSection(title = strings.aboutSection) {
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
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Preset replace confirmation
    if (showPresetConfirm) {
        AlertDialog(
            onDismissRequest = { showPresetConfirm = false },
            title = { Text(strings.loadPresetConfirmTitle) },
            text = { Text(strings.loadPresetConfirmBody) },
            confirmButton = {
                TextButton(onClick = {
                    showPresetConfirm = false
                    presetViewModel.loadTaniPreset()
                }) {
                    Text(strings.commonConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPresetConfirm = false }) {
                    Text(strings.commonCancel)
                }
            }
        )
    }

    // Café renamed — prompt to restart so every screen that reads the name once (RoleSelectScreen,
    // OEM keep-alive instructions) picks up the change. Not dismissable by tapping outside — the
    // admin must explicitly choose Restart Now or Later, since accidentally losing this prompt
    // just means the stale name lingers until the next natural app restart, not a data-loss risk.
    if (uiState.restartRequired) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(strings.restartRequiredTitle) },
            text = { Text(strings.restartRequiredBody) },
            confirmButton = {
                TextButton(onClick = { viewModel.restartApp() }) {
                    Text(strings.restartNowButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRestartPrompt() }) {
                    Text(strings.restartLaterButton)
                }
            }
        )
    }

    // Location permission dialog
    PermissionSettingsDialog(
        state = locationPermHelper,
        title = strings.locationPermTitle,
        message = strings.locationPermMessage
    )

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
    if (showChangePin) {
        com.razstudio.pos.ui.components.ChangePinDialog(
            strings = strings,
            onChange = { current, new -> pinLockViewModel.changePin(current, new) },
            onDone = { showChangePin = false },
            onCancel = { showChangePin = false }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
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
