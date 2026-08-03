package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.AdminSessionViewModel

/**
 * Café Management hub screen.
 * Entry point for Menu Management, Tables Management, printable Table QR cards, and Printers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CafeManagementScreen(
    onBack: () -> Unit,
    onNavigateToMenu: () -> Unit,
    onNavigateToTables: () -> Unit,
    onNavigateToQrPdf: () -> Unit = {},
    onNavigateToPrinters: () -> Unit = {},
    onNavigateToHardwareDevices: () -> Unit = {},
    onNavigateToPaymentGateway: () -> Unit = {},
    languageViewModel: LanguageViewModel = hiltViewModel(),
    sessionViewModel: AdminSessionViewModel = hiltViewModel(),
    modeViewModel: com.razstudio.pos.ui.viewmodels.ModeViewModel = hiltViewModel()
) {
    val capabilities by modeViewModel.capabilities.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    // A secondary-admin device has no local printer — reconcile the role, then grey the
    // Printers entry (it prints via the Main Admin) rather than hiding it.
    LaunchedEffect(Unit) { sessionViewModel.refreshRole() }
    val currentRole by sessionViewModel.currentRole.collectAsState()
    val printersEnabled = currentRole != SecureStorage.Role.ADMIN_SECONDARY

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.cafeManagementTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.commonBack)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            HubCard(
                icon = Icons.Default.RestaurantMenu,
                title = strings.menuManagementTitle,
                description = strings.menuManagementDesc,
                onClick = onNavigateToMenu
            )

            // Task 11.2 / Requirement 3.2: Kiosk Mode has no tables at all — one counter, orders
            // identified by a running number. A Tables screen there would let an operator create
            // rows that nothing can ever select.
            if (capabilities.tables) {
                HubCard(
                    icon = Icons.Default.TableRestaurant,
                    title = strings.tablesManagementTitle,
                    description = strings.tablesManagementDesc,
                    onClick = onNavigateToTables
                )
            }

            // Generate the printable table QR cards (sits under Tables Management since it's
            // about the tables you just set up).
            // Task 11.1 / Requirement 7.1: printable table QR sheets only mean something when
            // customers can scan them to order, which needs the website. Hidden rather than
            // disabled — a greyed control invites "why can't I press this", whereas a café that
            // never had web ordering has no reason to know the feature exists.
            if (capabilities.printableQrSheets) {
                HubCard(
                    icon = Icons.Default.QrCode2,
                    title = strings.generateTableQrTitle,
                    description = strings.generateTableQrDesc,
                    onClick = onNavigateToQrPdf
                )
            }

            // Printers moved here from the home overflow menu, directly under Generate Table QR.
            HubCard(
                icon = Icons.Default.Print,
                title = strings.printersTitle,
                description = strings.printersManagementDesc,
                onClick = onNavigateToPrinters,
                enabled = printersEnabled
            )

            // One entry for all peripherals rather than three siblings: hardware is configured
            // once when the till is set up, whereas Menu Management is opened daily, and equal
            // visual weight would misrepresent that. Gated exactly like Printers — a secondary
            // admin has no local hardware and prints through the Main Admin. (HW-REQ-6)
            HubCard(
                icon = Icons.Default.Devices,
                title = strings.devicesAndHardwareTitle,
                description = strings.devicesAndHardwareDesc,
                onClick = onNavigateToHardwareDevices,
                enabled = printersEnabled
            )

            // Gateway payments need a live path to the acquirer and are unavailable off-cloud
            // (ModeCapabilities.gatewayPaymentsEnabled, A1, task 6.4) — hidden here entirely
            // rather than greyed, same rule as Generate Table QR above: a café that can never use
            // this has no reason to know it exists. Unlike Printers/Hardware, NOT gated on
            // secondary-admin — gateway credentials are café-wide configuration, not local
            // hardware, so a Secondary Admin (full management, per its own RBAC design) can set
            // them same as the Main Admin.
            if (capabilities.gatewayPaymentsEnabled) {
                HubCard(
                    icon = Icons.Default.Payment,
                    title = strings.paymentGatewaySettingsTitle,
                    description = strings.paymentGatewayHubCardDesc,
                    onClick = onNavigateToPaymentGateway,
                )
            }
        }
    }
}

/** A single Café-Management entry: icon + title + one-line description, left-aligned. */
@Composable
private fun HubCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.padding(end = 12.dp)
        )
        // weight(1f) fills the row so every icon + title pins to the same left edge,
        // regardless of how long each card's text is (otherwise the button centers its
        // content and shorter cards drift right).
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
