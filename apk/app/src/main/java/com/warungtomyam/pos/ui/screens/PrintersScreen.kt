package com.warungtomyam.pos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.warungtomyam.pos.data.local.PaperWidth
import com.warungtomyam.pos.data.local.PrinterConfig
import com.warungtomyam.pos.data.local.PrinterRole
import com.warungtomyam.pos.printing.PrinterConnectionManager
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.UiStrings
import com.warungtomyam.pos.ui.i18n.uiStrings
import com.warungtomyam.pos.ui.util.DiscoveredDevice
import com.warungtomyam.pos.ui.viewmodels.PrintersViewModel

/**
 * Printers management screen.
 * Lists configured printers, allows BT scanning, add/edit/remove/test.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersScreen(
    viewModel: PrintersViewModel = hiltViewModel(),
    onBack: () -> Unit,
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val printers by viewModel.printers.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val context = LocalContext.current

    // Bluetooth runtime permissions must be granted from an Activity context before we
    // can scan for or connect to (test-print / real-print) a printer. On Android 12+
    // that's BLUETOOTH_CONNECT + BLUETOOTH_SCAN; older OS versions auto-grant the legacy
    // BLUETOOTH permission at install time, so nothing to request there.
    val btPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyArray()
        }
    }
    fun hasBtPermissions(): Boolean = btPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    // Deferred action to run once permissions come back granted (the scan or test-print
    // the user just tapped). Null when there's nothing pending.
    var pendingBtAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        val action = pendingBtAction
        pendingBtAction = null
        if (granted) action?.invoke()
    }

    // Run [action] immediately if BT permissions are already granted; otherwise stash it
    // and prompt, running it on grant. Keeps the scan/test-print call sites clean.
    fun withBtPermissions(action: () -> Unit) {
        if (hasBtPermissions()) {
            action()
        } else {
            pendingBtAction = action
            btPermissionLauncher.launch(btPermissions)
        }
    }

    // Show errors/success via snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.printersTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.commonBack)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Connection mode: Fast (persistent + 15s keep-alive) vs Eco (disconnect when idle)
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.printerConnectionSection,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (uiState.keepAliveMode == PrinterConnectionManager.MODE_FAST)
                                strings.printerModeFastDesc
                            else
                                strings.printerModeEcoDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val fast = uiState.keepAliveMode == PrinterConnectionManager.MODE_FAST
                            if (fast) {
                                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(strings.printerModeFast) }
                                OutlinedButton(
                                    onClick = { viewModel.setKeepAliveMode(PrinterConnectionManager.MODE_ECO) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(strings.printerModeEco) }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.setKeepAliveMode(PrinterConnectionManager.MODE_FAST) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(strings.printerModeFast) }
                                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(strings.printerModeEco) }
                            }
                        }
                    }
                }
            }

            // Configured printers section
            item {
                Text(
                    text = strings.configuredPrintersSection,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (printers.isEmpty()) {
                item {
                    Text(
                        text = strings.noPrintersConfigured,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(printers, key = { it.id }) { printer ->
                    PrinterCard(
                        printer = printer,
                        isTesting = uiState.testingPrinterId == printer.id,
                        strings = strings,
                        onToggleActive = { viewModel.toggleActive(printer.id) },
                        onEdit = { viewModel.showEditDialog(printer) },
                        onDelete = { viewModel.showDeleteConfirm(printer.id) },
                        onTestPrint = { withBtPermissions { viewModel.testPrint(printer.id) } }
                    )
                }
            }

            // Kitchen print routing (two buckets) + slip font size
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Kitchen Print Routing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Every order prints up to two slips — Foods and Beverages. Choose which printer prints each (the same printer can do both). Tag each category as Food or Beverage in Menu Management.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                listOf("Foods" to "FOOD", "Beverages" to "BEVERAGE").forEach { (label, bucket) ->
                    val currentId = viewModel.printerIdForBucket(bucket)
                    var open by remember(bucket, printers) { mutableStateOf(false) }
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(printers.firstOrNull { it.id == currentId }?.name ?: "Not assigned")
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(
                                text = { Text("Not assigned") },
                                onClick = { viewModel.setBucketPrinter(bucket, null); open = false }
                            )
                            printers.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    onClick = { viewModel.setBucketPrinter(bucket, p.id); open = false }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Kitchen Slip Font Size",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("S", "M", "L").forEach { size ->
                        val selected = uiState.kitchenFontSize == size
                        val label = com.warungtomyam.pos.data.local.KitchenFontSize.label(size)
                        if (selected) {
                            Button(onClick = { viewModel.updateKitchenFontSize(size) }, modifier = Modifier.weight(1f)) {
                                Text(label, maxLines = 1)
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.updateKitchenFontSize(size) }, modifier = Modifier.weight(1f)) {
                                Text(label, maxLines = 1)
                            }
                        }
                    }
                }
                Text(
                    text = "The special-instruction note prints one size smaller.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Scan section
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = strings.scanForPrintersButton,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (isScanning) viewModel.stopBluetoothScan()
                        else withBtPermissions { viewModel.startBluetoothScan() }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isScanning) strings.stopScanningButton else strings.scanForPrintersButton)
                }

                if (isScanning) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }

            // Discovered devices
            if (discoveredDevices.isNotEmpty()) {
                item {
                    Text(
                        text = strings.discoveredDevicesSection,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(discoveredDevices, key = { it.macAddress }) { device ->
                    val alreadyAdded = printers.any { it.macAddress == device.macAddress }
                    DiscoveredDeviceRow(
                        device = device,
                        alreadyAdded = alreadyAdded,
                        strings = strings,
                        onAdd = { viewModel.showAddDialog(device) }
                    )
                }
            }
        }
    }

    // Add printer dialog
    if (uiState.showAddDialog && uiState.selectedDevice != null) {
        AddEditPrinterDialog(
            title = strings.addPrinterTitle,
            initialName = uiState.selectedDevice!!.name,
            macAddress = uiState.selectedDevice!!.macAddress,
            strings = strings,
            onConfirm = { name, paperWidth, role ->
                viewModel.addPrinter(
                    name = name,
                    macAddress = uiState.selectedDevice!!.macAddress,
                    paperWidth = paperWidth,
                    printerRole = role
                )
            },
            onDismiss = { viewModel.dismissAddDialog() }
        )
    }

    // Edit printer dialog
    if (uiState.showEditDialog && uiState.editingPrinter != null) {
        val printer = uiState.editingPrinter!!
        AddEditPrinterDialog(
            title = strings.editPrinterTitle,
            initialName = printer.name,
            macAddress = printer.macAddress,
            initialPaperWidth = printer.paperWidth,
            initialRole = printer.printerRole,
            strings = strings,
            onConfirm = { name, paperWidth, role ->
                viewModel.updatePrinter(
                    id = printer.id,
                    name = name,
                    paperWidth = paperWidth,
                    printerRole = role
                )
            },
            onDismiss = { viewModel.dismissEditDialog() }
        )
    }

    // Delete confirmation dialog
    if (uiState.showDeleteConfirm && uiState.deletingPrinterId != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirm() },
            title = { Text(strings.removePrinterTitle) },
            text = { Text(strings.removePrinterConfirm) },
            confirmButton = {
                TextButton(onClick = { viewModel.removePrinter(uiState.deletingPrinterId!!) }) {
                    Text(strings.removeButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirm() }) {
                    Text(strings.commonCancel)
                }
            }
        )
    }
}

@Composable
private fun PrinterCard(
    printer: PrinterConfig,
    isTesting: Boolean,
    strings: UiStrings,
    onToggleActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTestPrint: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = printer.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = printer.macAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = printer.isActive,
                    onCheckedChange = { onToggleActive() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (printer.paperWidth == PaperWidth.FIFTY_EIGHT_MM) "58mm" else "80mm",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = when (printer.printerRole) {
                        PrinterRole.RECEIPT_ONLY -> strings.receiptOnlyLabel
                        PrinterRole.KITCHEN_ONLY -> strings.kitchenOnlyLabel
                        PrinterRole.BOTH -> strings.bothLabel
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = strings.commonEdit)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = strings.removeButton)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onTestPrint,
                    enabled = !isTesting && printer.isActive
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .height(16.dp)
                                .width(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(strings.testPrintButton)
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    device: DiscoveredDevice,
    alreadyAdded: Boolean,
    strings: UiStrings,
    onAdd: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.macAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (alreadyAdded) {
                Text(
                    text = strings.alreadyAddedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = strings.addPrinterTitle)
                }
            }
        }
    }
}

/**
 * Dialog for adding or editing a printer configuration.
 */
@Composable
private fun AddEditPrinterDialog(
    title: String,
    initialName: String,
    macAddress: String,
    initialPaperWidth: PaperWidth = PaperWidth.FIFTY_EIGHT_MM,
    initialRole: PrinterRole = PrinterRole.BOTH,
    strings: UiStrings,
    onConfirm: (name: String, paperWidth: PaperWidth, role: PrinterRole) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedWidthIndex by remember {
        mutableIntStateOf(if (initialPaperWidth == PaperWidth.FIFTY_EIGHT_MM) 0 else 1)
    }
    var selectedRoleIndex by remember {
        mutableIntStateOf(
            when (initialRole) {
                PrinterRole.RECEIPT_ONLY -> 0
                PrinterRole.KITCHEN_ONLY -> 1
                PrinterRole.BOTH -> 2
            }
        )
    }

    val paperWidths = listOf("58mm" to PaperWidth.FIFTY_EIGHT_MM, "80mm" to PaperWidth.EIGHTY_MM)
    val roles = listOf(
        strings.receiptOnlyLabel to PrinterRole.RECEIPT_ONLY,
        strings.kitchenOnlyLabel to PrinterRole.KITCHEN_ONLY,
        strings.bothLabel to PrinterRole.BOTH
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.printerNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "MAC: $macAddress",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Paper width selection
                Text(strings.paperWidthLabel, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    paperWidths.forEachIndexed { index, (label, _) ->
                        OutlinedButton(
                            onClick = { selectedWidthIndex = index },
                            enabled = selectedWidthIndex != index
                        ) {
                            Text(label)
                        }
                    }
                }

                // Role selection
                Text(strings.printerRoleLabel, style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    roles.forEachIndexed { index, (label, _) ->
                        OutlinedButton(
                            onClick = { selectedRoleIndex = index },
                            enabled = selectedRoleIndex != index,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name,
                        paperWidths[selectedWidthIndex].second,
                        roles[selectedRoleIndex].second
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text(strings.commonSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.commonCancel)
            }
        }
    )
}
