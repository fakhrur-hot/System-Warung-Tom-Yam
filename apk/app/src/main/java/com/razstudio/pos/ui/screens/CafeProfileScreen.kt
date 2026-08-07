package com.razstudio.pos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.razstudio.pos.ui.components.PermissionSettingsDialog
import com.razstudio.pos.ui.components.rememberPermissionHelper
import com.razstudio.pos.ui.util.GpsHelper
import kotlinx.coroutines.launch
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.AdminSettingsViewModel

/**
 * Café Profile — everything that describes *this café* rather than *this device*.
 *
 * ## Why these four sections live together, away from Settings
 *
 * Café name, logo, location, menu preset and payment QR are the café's identity: they are set once
 * when the café is opened, they are identical on every device the café owns, and they all restore
 * from the backend (and the owner's Drive bundle) when a replacement phone scans the owner QR.
 *
 * Admin Settings is the opposite — alert tones, ambient timeout, PIN lock, printer quirks: things
 * that describe one physical terminal and deliberately do NOT travel between devices. Mixing the
 * two taught owners the wrong lesson about which of their settings survive a broken phone, which is
 * the question that actually matters when a till dies mid-service.
 *
 * So this screen sits in Café Management, above Menu Management, beside the other things a café
 * configures once and expects back afterwards.
 *
 * ## Save semantics
 *
 * Name, logo and location are *staged* — edited locally, committed together by the bottom bar, which
 * is why this screen shares [AdminSettingsViewModel] rather than owning a second copy of that
 * staging logic. The payment QR and the menu preset are immediate: both are file/menu operations
 * with their own confirmation, and pretending they were part of a Save/Cancel batch would imply a
 * Cancel could undo a menu that has already been replaced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CafeProfileScreen(
    onBack: () -> Unit,
    viewModel: AdminSettingsViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
    presetViewModel: com.razstudio.pos.ui.viewmodels.MenuPresetViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    val presetLoading by presetViewModel.loading.collectAsState()
    var showPresetConfirm by remember { mutableStateOf(false) }

    val locationPermHelper = rememberPermissionHelper(Manifest.permission.ACCESS_FINE_LOCATION)
    val scope = rememberCoroutineScope()
    var capturing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.processLogo(it) }
    }

    // Separate launcher from the logo one so the two uploads cannot be confused — they go through
    // different pipelines with different rules: the logo is center-cropped and JPEG-compressed for
    // thermal printing, which would smear a dense QR until it stops scanning. The MIME type is
    // passed through so PaymentQrPipeline can keep a PNG lossless.
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
                title = { Text(strings.cafeProfileSection) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.commonBack
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            val isAnyLoading =
                uiState.permissionsLoading || uiState.locationLoading || uiState.brandingLoading
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
            // === Café Profile (name + logo) ===
            ProfileSection(title = strings.cafeProfileSection) {
                OutlinedTextField(
                    value = uiState.cafeName,
                    onValueChange = { viewModel.updateCafeName(it) },
                    label = { Text(strings.cafeNameLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // A freshly-picked local image takes priority; otherwise fall back to whatever logo
                // is already saved server-side, so the screen doesn't look like the logo was lost on
                // a fresh install/relogin. Centred — a 120dp square pinned to the left edge of a
                // full-width column reads as a thumbnail that failed to lay out, not as identity.
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        uiState.logoPreview != null -> Image(
                            bitmap = uiState.logoPreview!!.asImageBitmap(),
                            contentDescription = "Logo preview",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        uiState.existingLogoUrl != null -> AsyncImage(
                            model = uiState.existingLogoUrl,
                            contentDescription = "Current logo",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                if (uiState.logoPreview != null || uiState.existingLogoUrl != null) {
                    Spacer(modifier = Modifier.height(8.dp))
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

                // Directly under the logo, because this is the other half of "what this café is":
                // its identity on this device, and its identity in the owner's Google account —
                // which is also where everything on this screen is backed up to.
                Spacer(modifier = Modifier.height(16.dp))
                com.razstudio.pos.ui.components.GoogleAccountSection()
            }

            HorizontalDivider()

            // === Café Location (GPS Lock) ===
            ProfileSection(title = strings.cafeLocationSection) {
                Button(
                    onClick = {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPerm) {
                            // Requests a CURRENT fix and refuses a stale or imprecise one.
                            //
                            // This used to read getLastKnownLocation() directly, so tapping Capture
                            // could pin the cafe to wherever the tablet last had a fix — possibly
                            // hours earlier and kilometres away. Every staff check-in is then
                            // measured against that wrong point, and nothing on screen would ever
                            // reveal it: the coordinates look like coordinates.
                            captureError = null
                            capturing = true
                            scope.launch {
                                when (val fix = GpsHelper.locate(context, uiState.radiusMeters)) {
                                    is GpsHelper.Fix.Usable -> viewModel.onLocationCaptured(
                                        fix.location.latitude,
                                        fix.location.longitude,
                                    )
                                    is GpsHelper.Fix.Stale -> captureError =
                                        "That position is ${fix.ageMs / 60_000} minutes old. Step " +
                                            "outside or near a window and capture again."
                                    is GpsHelper.Fix.TooImprecise -> captureError =
                                        "This fix is only accurate to ${fix.accuracyMeters.toInt()}m, " +
                                            "wider than the ${uiState.radiusMeters}m radius. Capture " +
                                            "again with a clearer view of the sky."
                                    GpsHelper.Fix.NoPermission -> captureError =
                                        "Location permission is required."
                                    GpsHelper.Fix.Unavailable -> captureError =
                                        "No location available. Check that location is switched on."
                                }
                                capturing = false
                            }
                        } else {
                            locationPermHelper.request()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (capturing) "…" else strings.captureLocationButton)
                }

                captureError?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
            }

            HorizontalDivider()

            // ── Payment QR (task 16.1, Requirements 14.1–14.3, 14.5) ────────────────────────────
            // Available in ALL three operating modes (Requirement 14.7), so this section is never
            // gated on ModeCapabilities. Visibility of the *Show QR* button elsewhere depends solely
            // on whether a hash is stored — absence of a hash IS the "not configured" state, so
            // there is no separate enabled flag that could drift from whether the image exists.
            ProfileSection(title = strings.paymentQrSection) {
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

                // Rejection reason shown inline rather than as a snackbar: "this image has no QR
                // code in it" is a correction the admin must act on, and a transient toast is too
                // easy to miss while they are looking at the picker result (Requirement 14.3).
                uiState.paymentQrError?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider()

            // === Menu Preset (advanced — replaces the entire menu) ===
            ProfileSection(title = strings.menuPresetSection) {
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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Preset picker, then a confirmation naming the chosen preset.
    //
    // Two steps rather than one: the load is destructive, and "replace my menu" is a very
    // different decision from "replace my menu with these 152 items". The second dialog is where
    // the café sees what it is actually getting.
    if (showPresetConfirm) {
        val presets by presetViewModel.presets.collectAsState()
        var chosen by remember { mutableStateOf<com.razstudio.pos.data.local.MenuPreset?>(null) }

        val selected = chosen
        if (selected == null) {
            AlertDialog(
                onDismissRequest = { showPresetConfirm = false },
                title = { Text(strings.loadPresetConfirmTitle) },
                text = {
                    if (presets.isEmpty()) {
                        Text(strings.presetNoneAvailable)
                    } else {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = strings.presetPickerHelp,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            presets.forEach { preset ->
                                OutlinedButton(
                                    onClick = { chosen = preset },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = preset.presetName,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = strings.presetItemCount
                                                .format(preset.itemCount, preset.categoryCount),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showPresetConfirm = false }) {
                        Text(strings.commonCancel)
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { chosen = null },
                title = { Text(selected.presetName) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selected.description.isNotBlank()) {
                            Text(selected.description)
                        }
                        Text(
                            text = strings.presetItemCount
                                .format(selected.itemCount, selected.categoryCount),
                            style = MaterialTheme.typography.labelMedium
                        )
                        // The warning belongs here, next to the name, not on the earlier screen —
                        // this is the tap that destroys the current menu.
                        Text(
                            text = strings.loadPresetConfirmBody,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showPresetConfirm = false
                        presetViewModel.loadPreset(selected)
                    }) {
                        Text(strings.commonConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { chosen = null }) { Text(strings.commonBack) }
                }
            )
        }
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

    PermissionSettingsDialog(
        state = locationPermHelper,
        title = strings.locationPermTitle,
        message = strings.locationPermMessage
    )
}

/** Section header + body, matching the Admin Settings rhythm this screen was split out of. */
@Composable
private fun ProfileSection(
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
