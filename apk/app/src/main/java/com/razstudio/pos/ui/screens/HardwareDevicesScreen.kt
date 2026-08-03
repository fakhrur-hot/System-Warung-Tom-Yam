package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.display.DisplayDriverKind
import com.razstudio.pos.ui.viewmodels.HardwareAvailabilityReason
import com.razstudio.pos.ui.viewmodels.HardwareDevicesViewModel
import com.razstudio.pos.ui.viewmodels.PrinterDriverKind

/**
 * Devices & Hardware — pick the printer transport, cash drawer and customer display this device
 * uses. (HW-REQ-6)
 *
 * Two rules shape the whole screen:
 *
 * - **Unavailable drivers are shown greyed with their reason, never hidden.** An empty list reads
 *   as a bug; "No USB device" tells the owner what to plug in, and a café that might buy the
 *   hardware tomorrow learns it exists.
 * - **Selections are device-local.** One café runs a Sunmi till plus several staff phones off one
 *   backend; a café-wide setting would push "Sunmi built-in printer" onto phones that have no such
 *   hardware. The ViewModel writes to `LocalPrefs`, never to the shared database. (HW-REQ-8)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareDevicesScreen(
    onBack: () -> Unit,
    viewModel: HardwareDevicesViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val s = uiStrings(language)
    val snackbar = remember { SnackbarHostState() }

    // Hardware appears and disappears while this screen is open — Bluetooth gets switched on, the
    // customer display's owning app restarts, a cable is plugged in. Re-probe on resume so the
    // reasons shown are current rather than whatever was true when the screen opened.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshAvailability()
        }
    }

    LaunchedEffect(state.selectionSaved) {
        if (state.selectionSaved) {
            snackbar.showSnackbar(s.hardwareSelectionSaved)
            viewModel.clearSavedMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.devicesAndHardwareTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.commonBack)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard(title = s.hardwarePrintersSection) {
                if (state.printerDrivers.isEmpty()) {
                    Text(
                        text = s.hardwareNoDriversAvailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.printerDrivers.forEach { row ->
                    val available = row.availability == HardwareAvailabilityReason.AVAILABLE
                    DriverRow(
                        label = printerDriverLabel(row.kind, s),
                        selected = state.selectedPrinterTransport == row.kind,
                        enabled = available,
                        // A paired-device count is the useful thing to say about an available
                        // Bluetooth adapter; for everything else the reason is what matters.
                        detail = if (available) {
                            row.pairedCount?.let { s.hardwarePairedCount.format(it) }
                        } else {
                            reasonLabel(row.availability, s)
                        },
                        onSelect = { viewModel.selectPrinterTransport(row.kind) }
                    )
                }
            }

            SectionCard(title = s.hardwareCashDrawerSection) {
                state.drawerOptions.forEach { option ->
                    DriverRow(
                        label = when {
                            option.printerId == null -> s.hardwareDrawerNone
                            option.printerId == HardwareDevicesViewModel.SUNMI_DRAWER_SYNTHETIC_ID ->
                                s.hardwareDrawerSunmi
                            else -> s.hardwareDrawerViaPrinter.format(option.printerName.orEmpty())
                        },
                        selected = state.selectedDrawerPrinterId == option.printerId,
                        enabled = option.available,
                        detail = null,
                        onSelect = { viewModel.selectDrawer(option.printerId) }
                    )
                }
            }

            SectionCard(title = s.hardwareCustomerDisplaySection) {
                state.displayDrivers.forEach { row ->
                    val available = row.availability == HardwareAvailabilityReason.AVAILABLE
                    DriverRow(
                        label = displayDriverLabel(row.kind, s),
                        selected = state.selectedDisplayDriver == row.kind,
                        enabled = available,
                        detail = when {
                            !available -> reasonLabel(row.availability, s)
                            // A text-strip pole cannot render a payment QR. Saying so here is the
                            // difference between an informed choice and finding out at the counter.
                            row.kind == DisplayDriverKind.VFD_SERIAL -> s.hardwareDisplayNoQrNote
                            else -> null
                        },
                        onSelect = { viewModel.selectDisplayDriver(row.kind) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
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

@Composable
private fun DriverRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    detail: String?,
    onSelect: () -> Unit
) {
    // The whole row is the target, not just the radio — a 20dp circle is a poor tap target on a
    // counter, and Role.RadioButton keeps the group correct for accessibility.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        val contentColour = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
        CompositionLocalProvider(LocalContentColor provides contentColour) {
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge, color = contentColour)
                detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Enum → localized text. Kept at the UI edge so no ViewModel holds English. ────────────────

private fun printerDriverLabel(kind: PrinterDriverKind, s: UiStrings): String = when (kind) {
    PrinterDriverKind.BLUETOOTH -> s.hardwareDriverBluetooth
    PrinterDriverKind.SUNMI_AIDL -> s.hardwareDriverSunmi
    PrinterDriverKind.USB -> s.hardwareDriverUsb
    PrinterDriverKind.NETWORK -> s.hardwareDriverNetwork
}

private fun displayDriverLabel(kind: DisplayDriverKind, s: UiStrings): String = when (kind) {
    DisplayDriverKind.NONE -> s.hardwareDisplayNone
    DisplayDriverKind.PRESENTATION -> s.hardwareDisplayPresentation
    DisplayDriverKind.VFD_SERIAL -> s.hardwareDisplayVfd
}

private fun reasonLabel(reason: HardwareAvailabilityReason, s: UiStrings): String? = when (reason) {
    HardwareAvailabilityReason.AVAILABLE -> null
    HardwareAvailabilityReason.BLUETOOTH_OFF -> s.hardwareReasonBluetoothOff
    HardwareAvailabilityReason.BLUETOOTH_UNAVAILABLE -> s.hardwareDriverUnavailable
    HardwareAvailabilityReason.SUNMI_NOT_DETECTED -> s.hardwareDriverNotDetected
    HardwareAvailabilityReason.NOT_IMPLEMENTED -> s.hardwareReasonNotImplemented
    HardwareAvailabilityReason.NO_USB_DEVICE -> s.hardwareReasonNoUsb
    HardwareAvailabilityReason.NO_PRESENTATION_DISPLAY -> s.hardwareReasonNoDisplay
}
