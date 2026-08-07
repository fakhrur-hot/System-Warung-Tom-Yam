package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.HardwareAvailabilityReason
import com.razstudio.pos.ui.viewmodels.HardwareDevicesViewModel

/**
 * Cash Drawer Settings (cash-drawer-settings spec, Requirement 2).
 *
 * One page for everything about the till drawer and the printer it hangs off: the master
 * enable/disable for the physical kick, which printer owns the drawer, receipt auto-cut, and the
 * printer transport. The last three moved here from Devices & Hardware (Requirement 3), which now
 * focuses on the customer display; they render through the SAME composables and the SAME
 * [HardwareDevicesViewModel] logic, so nothing about how a selection persists changed
 * (Requirements 8, 10).
 *
 * The enable toggle gates ONLY the solenoid pulse — every cash-ledger write (opening float, cash
 * sale, cash out) happens identically whether the drawer is enabled or not (Requirement 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashDrawerSettingsScreen(
    onBack: () -> Unit,
    viewModel: HardwareDevicesViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val s = uiStrings(language)
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val secureStorage = remember { SecureStorage(context) }
    var hasDrawerPin by remember { mutableStateOf(secureStorage.hasCustomDrawerPin()) }
    var showDrawerPin by remember { mutableStateOf(false) }

    // Same resume-time re-probe as Devices & Hardware: Bluetooth toggles and cables change while
    // the screen is open, and stale availability reasons are worse than none.
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
                title = { Text("Cash Drawer Settings") },
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
            // ── Cash Drawer — master enable + kick source list (Requirements 1, 2) ────────
            SectionCard(title = "Cash drawer") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable cash drawer")
                        Text(
                            text = "When off, nothing ever kicks the physical drawer — cash " +
                                "sales, cash out and the Drawer page still track every ringgit; " +
                                "only the hardware stays still.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.cashDrawerEnabled,
                        onCheckedChange = { viewModel.setCashDrawerEnabled(it) },
                    )
                }

                // When enabled, show the kick-through source list inline (no radio, no "None")
                if (state.cashDrawerEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Kick through",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    state.drawerOptions
                        .filter { it.printerId != null }  // exclude "None"
                        .forEach { option ->
                            val label = when {
                                option.printerId == HardwareDevicesViewModel.SUNMI_DRAWER_SYNTHETIC_ID ->
                                    s.hardwareDrawerSunmi
                                else -> s.hardwareDrawerViaPrinter.format(option.printerName.orEmpty())
                            }
                            DriverRow(
                                label = label,
                                selected = state.selectedDrawerPrinterId == option.printerId,
                                enabled = option.available,
                                detail = null,
                                onSelect = { viewModel.selectDrawer(option.printerId) }
                            )
                        }
                    if (state.drawerOptions.none { it.printerId != null }) {
                        Text(
                            text = "No printers configured — add a printer in Café Management first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Cash drawer PIN (moved from Settings → Security) ────────────────────────────
            SectionCard(title = s.drawerPinLabel) {
                Text(
                    text = s.drawerPinDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDrawerPin = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (hasDrawerPin) s.drawerPinChangeButton
                        else s.drawerPinSetButton
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = s.drawerPinHowTo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ── Drawer PIN dialog ────────────────────────────────────────────────────────────────
    if (showDrawerPin) {
        com.razstudio.pos.ui.components.DrawerPinDialog(
            strings = s,
            requiresCurrent = hasDrawerPin,
            onVerifyCurrent = { pin -> secureStorage.getDrawerPin() == pin },
            onSet = { newPin ->
                secureStorage.saveDrawerPin(newPin)
                hasDrawerPin = true
                showDrawerPin = false
            },
            onDismiss = { showDrawerPin = false },
        )
    }
}
