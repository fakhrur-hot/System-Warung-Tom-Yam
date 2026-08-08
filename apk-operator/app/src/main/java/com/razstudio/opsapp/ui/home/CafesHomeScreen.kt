package com.razstudio.opsapp.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.opsapp.data.local.ConnectedCafeEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Cafés Management home screen (Requirement 2).
 *
 * Lists every café this operator device is connected to, provides the two primary entry points
 * (Provision New Cafe / Connect to Existing Cafe), and exposes the app-wide overflow menu
 * (Affiliate Ads, About).
 *
 * Tapping a card opens that café's profile shell. Long-pressing (or using the trailing overflow)
 * offers Disconnect with a confirmation dialog — local delete only (Requirement 2.5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CafesHomeScreen(
    onProvisionNewCafe: () -> Unit,
    onConnectExistingCafe: () -> Unit,
    onOpenCafe: (cafeId: String) -> Unit,
    onOpenAffiliateAds: () -> Unit,
    viewModel: CafesHomeViewModel = hiltViewModel(),
) {
    val cafes by viewModel.connectedCafes.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringAppName()) },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Affiliate Ads") },
                            onClick = {
                                expanded = false
                                onOpenAffiliateAds()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                expanded = false
                                val version = try {
                                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                                        ?: "0.1.0"
                                } catch (_: Exception) {
                                    "0.1.0"
                                }
                                Toast.makeText(context, "RAZ Ops v$version", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (cafes.isEmpty()) {
                EmptyCafesView(
                    onProvisionNewCafe = onProvisionNewCafe,
                    onConnectExistingCafe = onConnectExistingCafe,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Cafés Management",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    EntryPointButtons(
                        onProvisionNewCafe = onProvisionNewCafe,
                        onConnectExistingCafe = onConnectExistingCafe,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = cafes,
                            key = { it.id },
                        ) { cafe ->
                            CafeCard(
                                cafe = cafe,
                                onClick = { onOpenCafe(cafe.id) },
                                onDisconnect = { viewModel.disconnect(cafe.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryPointButtons(
    onProvisionNewCafe: () -> Unit,
    onConnectExistingCafe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onProvisionNewCafe,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Provision New Cafe")
        }
        OutlinedButton(
            onClick = onConnectExistingCafe,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Connect to Existing Cafe")
        }
    }
}

@Composable
private fun EmptyCafesView(
    onProvisionNewCafe: () -> Unit,
    onConnectExistingCafe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InitialAvatar(
            name = stringAppName(),
            size = 96,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            text = "No cafés connected yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Provision a brand-new café, or connect to one that already exists.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        EntryPointButtons(
            onProvisionNewCafe = onProvisionNewCafe,
            onConnectExistingCafe = onConnectExistingCafe,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun CafeCard(
    cafe: ConnectedCafeEntity,
    onClick: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialAvatar(name = cafe.cafeName, size = 56)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cafe.cafeName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatRelativeTime(cafe.lastConnectedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Café options",
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Disconnect") },
                    onClick = {
                        showMenu = false
                        showDisconnectDialog = true
                    },
                )
            }
        }
    }

    if (showDisconnectDialog) {
        DisconnectConfirmDialog(
            cafeName = cafe.cafeName,
            onConfirm = {
                showDisconnectDialog = false
                onDisconnect()
            },
            onDismiss = { showDisconnectDialog = false },
        )
    }
}

@Composable
private fun DisconnectConfirmDialog(
    cafeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect café?") },
        text = {
            Text(
                "This will remove \"$cafeName\" from this device only. " +
                    "The café's backend and staff devices will not be affected."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun InitialAvatar(
    name: String,
    size: Int,
    modifier: Modifier = Modifier,
) {
    val initial = name.trim().take(1).uppercase().ifBlank { "?" }
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = if (size >= 72) {
                MaterialTheme.typography.headlineMedium
            } else {
                MaterialTheme.typography.titleLarge
            },
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun stringAppName(): String {
    val context = LocalContext.current
    return remember {
        try {
            context.getString(context.applicationInfo.labelRes)
        } catch (_: Exception) {
            "RAZ Ops"
        }
    }
}

/**
 * Formats an ISO-8601 timestamp as a short relative string, e.g. "last connected 3 days ago".
 * Falls back to a full date if parsing fails.
 */
private fun formatRelativeTime(isoTimestamp: String): String {
    return try {
        val instant = Instant.parse(isoTimestamp)
        val now = Instant.now()
        val seconds = ChronoUnit.SECONDS.between(instant, now)
        val prefix = "last connected"
        when {
            seconds < 0 -> "$prefix just now"
            seconds < 60 -> "$prefix just now"
            seconds < 3600 -> {
                val m = seconds / 60
                "$prefix ${m}m ago"
            }
            seconds < 86400 -> {
                val h = seconds / 3600
                "$prefix ${h}h ago"
            }
            seconds < 604800 -> {
                val d = seconds / 86400
                "$prefix ${d}d ago"
            }
            else -> {
                val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
                    .withZone(ZoneId.systemDefault())
                "$prefix ${formatter.format(instant)}"
            }
        }
    } catch (_: Exception) {
        "last connected $isoTimestamp"
    }
}
