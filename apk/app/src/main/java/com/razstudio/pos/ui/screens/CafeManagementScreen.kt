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
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material.icons.filled.RestaurantMenu
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
    languageViewModel: LanguageViewModel = hiltViewModel(),
    sessionViewModel: AdminSessionViewModel = hiltViewModel()
) {
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

            HubCard(
                icon = Icons.Default.TableRestaurant,
                title = strings.tablesManagementTitle,
                description = strings.tablesManagementDesc,
                onClick = onNavigateToTables
            )

            // Generate the printable table QR cards (sits under Tables Management since it's
            // about the tables you just set up).
            HubCard(
                icon = Icons.Default.QrCode2,
                title = strings.generateTableQrTitle,
                description = strings.generateTableQrDesc,
                onClick = onNavigateToQrPdf
            )

            // Printers moved here from the home overflow menu, directly under Generate Table QR.
            HubCard(
                icon = Icons.Default.Print,
                title = strings.printersTitle,
                description = strings.printersManagementDesc,
                onClick = onNavigateToPrinters,
                enabled = printersEnabled
            )
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
