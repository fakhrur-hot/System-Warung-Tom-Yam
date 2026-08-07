package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.notification.CapturedPayment
import com.razstudio.pos.notification.MatchStatus
import com.razstudio.pos.notification.WalletApp
import com.razstudio.pos.ui.viewmodels.PaymentMonitorUiState
import com.razstudio.pos.ui.viewmodels.PaymentMonitorViewModel

/**
 * Payment Monitor screen: listener settings, permission status, and recent payment history.
 * Accessible from AdminHomeScreen overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentMonitorScreen(
    onBack: () -> Unit,
    onPaymentTapped: (CapturedPayment) -> Unit = {},
    viewModel: PaymentMonitorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment Monitor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Permission status section
            item { PermissionStatusSection(state, viewModel) }

            // Schedule / business hours section
            item { BusinessHoursSection(state) }

            // Listener settings section
            item { ListenerSettingsSection(state, viewModel) }

            // Alert settings section
            item { AlertSettingsSection(state, viewModel) }

            // Recent payments history
            item { SectionHeader("Recent Payments") }
            if (state.recentPayments.isEmpty()) {
                item { EmptyPaymentsState() }
            } else {
                items(state.recentPayments, key = { it.id }) { payment ->
                    PaymentHistoryItem(payment, onTap = { onPaymentTapped(payment) })
                }
            }
        }
    }
}

// ── Permission Status Section ────────────────────────────────────────────────────────────────────

@Composable
private fun PermissionStatusSection(
    state: PaymentMonitorUiState,
    viewModel: PaymentMonitorViewModel,
) {
    MonitorCard(title = "Permission Status") {
        // Notification access status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.isPermissionGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (state.isPermissionGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.isPermissionGranted) "Notification access granted" else "Notification access required",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (!state.isPermissionGranted) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "The listener cannot function without notification access permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.openNotificationAccessSettings() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Notification Settings")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        // Battery optimization status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (!state.isBatteryOptimized) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (!state.isBatteryOptimized) Color(0xFF4CAF50) else Color(0xFFFFA000),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (!state.isBatteryOptimized) "Battery optimization bypassed" else "Battery optimization active",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (state.isBatteryOptimized) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Battery optimization may kill the listener service. Disable it for reliable operation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.requestBatteryOptimizationBypass() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Disable Battery Optimization")
            }
        }

        // OEM-specific guidance
        if (state.isAggressiveOem) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "⚠️ Aggressive OEM detected",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFA000),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your device manufacturer is known for aggressive battery management. " +
                    "Please also grant autostart permission in your device's Settings → Apps → " +
                    "Autostart to prevent the listener from being killed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Business Hours / Schedule Section ─────────────────────────────────────────────────────────

@Composable
private fun BusinessHoursSection(state: PaymentMonitorUiState) {
    MonitorCard(title = "Schedule") {
        if (state.businessStartHour == -1 || state.businessEndHour == -1) {
            // Not yet synced
            Text(
                text = "Business hours not configured — listener runs 24/7 until synced",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Show hours
            Text(
                text = "Business hours: ${formatHour(state.businessStartHour)} – ${formatHour(state.businessEndHour)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Active/sleeping indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.isWithinBusinessHours) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (state.isWithinBusinessHours) Color(0xFF4CAF50) else Color(0xFFFFA000),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.isWithinBusinessHours) "Listener is active" else "Listener is sleeping",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Listener pauses outside business hours to save battery",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatHour(hour: Int): String {
    val period = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$displayHour:00 $period"
}

// ── Listener Settings Section ────────────────────────────────────────────────────────────────────

@Composable
private fun ListenerSettingsSection(
    state: PaymentMonitorUiState,
    viewModel: PaymentMonitorViewModel,
) {
    MonitorCard(title = "Listener Settings") {
        // Main listener toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enable Payment Listener")
            Switch(
                checked = state.isListenerEnabled,
                onCheckedChange = { viewModel.toggleListener(it) },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        // Monitored apps
        Text(
            text = "Monitored Apps",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        state.monitoredApps.forEach { (app, enabled) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = { viewModel.toggleApp(app, it) },
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = app.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        // Auto-start on boot
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Auto-start on boot")
            Switch(
                checked = state.autoStartOnBoot,
                onCheckedChange = { viewModel.toggleAutoStart(it) },
            )
        }
        Text(
            text = "Automatically start the listener when the device reboots.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Alert Settings Section ───────────────────────────────────────────────────────────────────────

@Composable
private fun AlertSettingsSection(
    state: PaymentMonitorUiState,
    viewModel: PaymentMonitorViewModel,
) {
    MonitorCard(title = "Alert Settings") {
        // Sound toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sound")
            Switch(
                checked = state.soundEnabled,
                onCheckedChange = { viewModel.toggleSound(it) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Vibration toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Vibration")
            Switch(
                checked = state.vibrationEnabled,
                onCheckedChange = { viewModel.toggleVibration(it) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Toast notification toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Toast Notification")
            Switch(
                checked = state.toastNotificationEnabled,
                onCheckedChange = { viewModel.toggleToast(it) },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Controls how this device alerts when a payment is captured.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Payment History Item ─────────────────────────────────────────────────────────────────────────

@Composable
private fun PaymentHistoryItem(
    payment: CapturedPayment,
    onTap: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onTap,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Amount
                Text(
                    text = "RM %.2f".format(payment.amountSen / 100.0),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                // Wallet app display name
                val walletAppName = try {
                    WalletApp.valueOf(payment.walletApp).displayName
                } catch (_: IllegalArgumentException) {
                    payment.walletApp
                }
                Text(
                    text = walletAppName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Sender (if available)
                payment.sender?.let { sender ->
                    Text(
                        text = "From: $sender",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Timestamp
                Text(
                    text = payment.capturedAt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Match status badge
            MatchStatusBadge(payment.matchStatus)
        }
    }
}

// ── Match Status Badge ───────────────────────────────────────────────────────────────────────────

@Composable
private fun MatchStatusBadge(matchStatus: String) {
    val (label, backgroundColor, textColor) = when (matchStatus) {
        MatchStatus.MATCHED.name -> Triple("MATCHED", Color(0xFF4CAF50), Color.White)
        MatchStatus.AMBIGUOUS.name -> Triple("AMBIGUOUS", Color(0xFFFFA000), Color.White)
        MatchStatus.UNMATCHED.name -> Triple("UNMATCHED", Color(0xFF9E9E9E), Color.White)
        MatchStatus.DISMISSED.name -> Triple("DISMISSED", Color(0xFF424242), Color.White)
        else -> Triple(matchStatus, Color(0xFF9E9E9E), Color.White)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
    }
}

// ── Shared Components ────────────────────────────────────────────────────────────────────────────

@Composable
private fun MonitorCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            content()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun EmptyPaymentsState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Text(
            text = "No captured payments yet",
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
