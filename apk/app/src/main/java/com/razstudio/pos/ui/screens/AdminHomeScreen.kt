package com.razstudio.pos.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.OperatingMode
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.ui.components.BlockingLoadingOverlay
import com.razstudio.pos.ui.components.HoldCountdownOverlay
import com.razstudio.pos.ui.components.PinEntryDialog
import com.razstudio.pos.ui.viewmodels.PinLockViewModel
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.printing.PrintAlert
import com.razstudio.pos.realtime.RealtimeService
import com.razstudio.pos.ui.viewmodels.PrintAlertsViewModel
import com.razstudio.pos.ui.i18n.LanguageButton
import com.razstudio.pos.ui.theme.ThemeButton
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.tableview.CartLine
import com.razstudio.pos.ui.tableview.OrderDetailSheet
import com.razstudio.pos.ui.tableview.OrderEntrySheet
import com.razstudio.pos.ui.tableview.StaffPermissions
import com.razstudio.pos.ui.tableview.TableGrid
import com.razstudio.pos.ui.tableview.TableUiStatus
import com.razstudio.pos.ui.viewmodels.AdminSessionViewModel
import com.razstudio.pos.ui.viewmodels.TableViewViewModel

/**
 * Admin home screen — the Table View POS.
 * Shows a grid of tables colored by order status.
 * Tapping a table opens an order detail bottom sheet.
 * Maintains session lifecycle from Task 15 (openSession, signOut, signOutWithClosing).
 *
 * Uses the shared [TableGrid] and [OrderDetailSheet] components from [com.razstudio.pos.ui.tableview]
 * with [StaffPermissions.ADMIN] (all actions enabled). The local TableCell composable has been
 * removed in favor of the shared implementation (Requirements 5.2, 5.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminHomeScreen(
    sessionViewModel: AdminSessionViewModel = hiltViewModel(),
    tableViewModel: TableViewViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
    printAlertsViewModel: PrintAlertsViewModel = hiltViewModel(),
    pinLockViewModel: PinLockViewModel = hiltViewModel(),
    devicePrefsViewModel: com.razstudio.pos.ui.viewmodels.DevicePrefsViewModel = hiltViewModel(),
    devicesViewModel: com.razstudio.pos.ui.viewmodels.DevicesViewModel = hiltViewModel(),
    modeViewModel: com.razstudio.pos.ui.viewmodels.ModeViewModel = hiltViewModel(),
    /**
     * Avatar -> Mode Logout finished. The caller clears the stack back to the home screen; the
     * Google account is untouched, so the owner's other cafés are still listed when they land.
     */
    onAccountSignedOut: () -> Unit = {},
    onNavigateToLock: () -> Unit,
    onNavigateToReconnect: () -> Unit = {},
    onNavigateToDineIn: () -> Unit = {},
    onNavigateToDevices: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToPrinters: () -> Unit = {},
    onNavigateToQrPdf: () -> Unit = {},
    onNavigateToReports: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToKeepAliveSetup: () -> Unit = {},
    onNavigateToCafeManagement: () -> Unit = {}
) {
    val sessionState by sessionViewModel.uiState.collectAsState()
    val tableStates by tableViewModel.tableStates.collectAsState()
    val orderDetail by tableViewModel.orderDetail.collectAsState()
    val tableManagement by tableViewModel.tableManagement.collectAsState()
    val orderEntry by tableViewModel.orderEntry.collectAsState()
    val availableMenu by tableViewModel.availableMenu.collectAsState()
    val cafeName by tableViewModel.cafeName.collectAsState()
    val activeMode by tableViewModel.activeMode.collectAsState()
    val pendingPrints by tableViewModel.pendingKitchenPrints.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showClosingDialog by remember { mutableStateOf(false) }
    var showPendingPrints by remember { mutableStateOf(false) }
    var showPinGate by remember { mutableStateOf(false) }
    var showRecentPrints by remember { mutableStateOf(false) }
    val recentPrints by devicePrefsViewModel.recentPrints.collectAsState()
    var selectedTableLabel by remember { mutableStateOf("") }
    var showOrderSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Poll for pending device requests while on the home screen, so a new ordering-staff device
    // that scans in triggers an approve/reject popup here (not only when the Devices page is open).
    val pendingRequests by devicesViewModel.pendingRequests.collectAsState()
    var handledRequestIds by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        while (true) {
            devicesViewModel.refreshPendingRequests()
            kotlinx.coroutines.delay(8_000)
        }
    }

    // Ensure the realtime listener (new orders, kitchen auto-print) is running whenever
    // this screen is reached — on fresh admin login AND on every app relaunch that lands
    // here directly (already-authenticated). Previously this was only started from
    // BootReceiver on device reboot, so a login without a reboot left the admin device
    // silently deaf to new orders until the next reboot.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // In Demo Mode there is no backend to subscribe to; keep the app fully offline.
        if (!com.razstudio.pos.data.demo.DemoSession.active) RealtimeService.start(context)
    }

    // Open session on first composition
    LaunchedEffect(Unit) {
        sessionViewModel.openSession()
    }

    // Rehydrate the table registry from the backend if local Room has none — covers
    // a fresh install/relogin where a prior device already pushed tables to the
    // server (same class of gap as branding/menu: local-only state looked "lost"
    // after reinstall even though the server-side data was untouched).
    LaunchedEffect(Unit) {
        tableViewModel.rehydrateTablesIfEmpty()
        // Reconcile this device's admin role (it may have been promoted/demoted elsewhere).
        sessionViewModel.refreshRole()
    }

    // Handle navigation to lock screen
    LaunchedEffect(sessionState.navigateToLock) {
        if (sessionState.navigateToLock) {
            sessionViewModel.onNavigatedToLock()
            onNavigateToLock()
        }
    }

    // Handle expired/revoked token → re-handshake required
    LaunchedEffect(sessionState.navigateToReconnect) {
        if (sessionState.navigateToReconnect) {
            sessionViewModel.onNavigatedToReconnect()
            onNavigateToReconnect()
        }
    }

    // Show errors/success from session
    LaunchedEffect(sessionState.error) {
        sessionState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            sessionViewModel.clearError()
        }
    }

    // Show errors/success from table view
    LaunchedEffect(orderDetail.error) {
        orderDetail.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            tableViewModel.clearMessages()
        }
    }

    LaunchedEffect(orderDetail.successMessage) {
        orderDetail.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            tableViewModel.clearMessages()
            // Close sheet on payment/cancel success (order detail is now empty)
            if (orderDetail.order == null) {
                showOrderSheet = false
            }
        }
    }

    // Collect printer alerts (print failures, success confirmations) once at
    // app-wide scope so they surface regardless of which screen triggered the print.
    LaunchedEffect(Unit) {
        printAlertsViewModel.alerts.collect { alert ->
            snackbarHostState.showSnackbar(printAlertsViewModel.message(alert))
        }
    }

    val backupNag = remember { modeViewModel.backupNag() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // Café name sits above the screen title; hidden until branding loads.
                        if (cafeName.isNotBlank()) {
                            Text(
                                text = cafeName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.tableView)
                            Spacer(modifier = Modifier.width(8.dp))
                            // Task 9.4 / Requirement 1.4 — the operator can see the topology without
                            // opening Setup. It lives beside the screen title rather than in the
                            // overflow menu because the overflow is behind the PIN gate, and the one
                            // moment this matters most is when someone is trying to work out why the
                            // café is behaving unexpectedly.
                            ModeBadge(mode = activeMode)
                        }
                    }
                },
                actions = {
                    LanguageButton()
                    Spacer(modifier = Modifier.width(4.dp))
                    ThemeButton()
                    // Mid-service: Mode Logout only. Signing out of Google here would hide every
                    // café on the account, on a counter phone, during a shift.
                    com.razstudio.pos.ui.components.AccountAvatar(
                        isHomeScreen = false,
                        onSignedOut = onAccountSignedOut,
                    )
                    Box {
                        // The PIN gate now guards the whole overflow menu (not just Settings),
                        // so no management screen inside it is reachable without the PIN.
                        IconButton(onClick = {
                            if (pinLockViewModel.isGateActive()) {
                                showPinGate = true
                            } else {
                                showOverflowMenu = true
                            }
                        }) {
                            Icon(Icons.Default.MoreVert, contentDescription = strings.moreOptions)
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            // Pending kitchen prints (only when auto-print is off / items waiting)
                            if (pendingPrints.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("${strings.pendingKitchenPrints} (${pendingPrints.size})") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showPendingPrints = true
                                    }
                                )
                                HorizontalDivider()
                            }

                            // Recent kitchen print statuses (when the admin enabled the status view)
                            if (devicePrefsViewModel.showPrintStatus()) {
                                DropdownMenuItem(
                                    text = { Text(strings.recentPrints) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showRecentPrints = true
                                    }
                                )
                                HorizontalDivider()
                            }

                            // --- Cafe Management ---
                            DropdownMenuItem(
                                text = { Text(strings.cafeManagementTitle) },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToCafeManagement()
                                }
                            )

                            HorizontalDivider()

                            // --- Devices ---
                            DropdownMenuItem(
                                text = { Text(strings.devicesAndStaff) },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToDevices()
                                }
                            )
                            // Printers moved into Café Management (under Generate Table QR).

                            HorizontalDivider()

                            // --- Setup ---
                            // "Generate Table QR" moved into Café Management (under Tables).
                            DropdownMenuItem(
                                text = { Text(strings.reportsTitle) },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToReports()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.backupMenuItem) },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToBackup()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.backgroundSetupTitle) },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToKeepAliveSetup()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.settingsTitle) },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToSettings()
                                }
                            )

                            HorizontalDivider()

                            // --- Session (destructive; separated from navigation) ---
                            MenuSectionLabel(strings.sessionSectionLabel)
                            DropdownMenuItem(
                                text = { Text(strings.signOut) },
                                onClick = {
                                    showOverflowMenu = false
                                    sessionViewModel.signOut()
                                },
                                modifier = androidx.compose.ui.Modifier.background(
                                    Color(0xFFFFE0B2) // light orange
                                )
                            )
                            DropdownMenuItem(
                                text = { Text(strings.signOutClosingTitle, color = Color.Red) },
                                onClick = {
                                    showOverflowMenu = false
                                    showClosingDialog = true
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // Primary action: start a new dine-in order (Requirement 6.1)
            ExtendedFloatingActionButton(
                onClick = onNavigateToDineIn,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(strings.newDineInOrder) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Task 13.1 — off-cloud this device holds the café's only copy, so the need to back up
            // has to be evident rather than discoverable three screens down in Café Management.
            // Cloud cafés never see it: Supabase holds the second copy for them.
            BackupNagBanner(
                lastBackupAtMs = backupNag.lastBackupAtMs,
                visible = backupNag.isOnlyCopy,
                strings = strings,
                onBackUp = onNavigateToBackup,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (sessionState.isLoading && !sessionState.isSessionOpen) {
                // Loading session
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = strings.openingSessionLabel,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (tableStates.isEmpty()) {
                // No tables configured
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = strings.noTablesConfigured,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = strings.noTablesAdminHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Shared table grid — same component as staff screen (Requirement 5.2)
                TableGrid(
                    tableStates = tableStates,
                    strings = strings,
                    onTableClick = { tableState ->
                        if (tableState.status == TableUiStatus.FREE) {
                            // Free table → open the tabbed new-order menu modal
                            tableViewModel.startOrderEntry(tableState.table.id, tableState.table.label)
                        } else {
                            // Occupied table → open the receipt-style order detail
                            selectedTableLabel = tableState.table.label
                            tableViewModel.loadOrderForTable(tableState.table.id)
                            showOrderSheet = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // Shared order detail bottom sheet with full admin permissions (Requirement 5.2, 5.3)
    if (showOrderSheet) {
        OrderDetailSheet(
            state = orderDetail,
            tableLabel = selectedTableLabel,
            permissions = StaffPermissions.ADMIN,
            strings = strings,
            menuItems = availableMenu,
            language = language,
            onAddItems = { orderId, items -> tableViewModel.addItems(orderId, items) },
            onReprintSession = { orderId, sessionNumber -> tableViewModel.reprintSession(orderId, sessionNumber) },
            onConfirmSession = { orderId, sessionNumber -> tableViewModel.confirmSession(orderId, sessionNumber) },
            onPayment = { orderId, method, printReceipt -> tableViewModel.processPayment(orderId, method, printReceipt) },
            onVoidItems = { orderId, itemIds, reason -> tableViewModel.voidItems(orderId, itemIds, reason) },
            onCancel = { orderId, reason -> tableViewModel.cancelOrder(orderId, reason) },
            onDismiss = {
                showOrderSheet = false
                tableViewModel.clearOrderDetail()
            }
        )
    }

    // New-order entry modal (free-table tap) — shared tabbed-category menu sheet
    if (orderEntry.isVisible) {
        OrderEntrySheet(
            tableLabel = orderEntry.tableLabel,
            menuItems = orderEntry.menuItems,
            cart = orderEntry.cart.map {
                CartLine(
                    menuItemId = it.menuItem.id,
                    name = language.menuName(it.menuItem) + (it.size?.let { s -> " ($s)" } ?: ""),
                    unitPrice = it.unitPrice ?: it.menuItem.price,
                    quantity = it.quantity,
                    note = it.note,
                )
            },
            language = language,
            strings = strings,
            isSubmitting = orderEntry.isSubmitting,
            onAdd = { item, note, size, price -> tableViewModel.addToCart(item, note, size, price) },
            onRemove = { index -> tableViewModel.removeFromCart(index) },
            onSubmit = { tableViewModel.submitOrder() },
            onDismiss = { tableViewModel.dismissOrderEntry() },
            categoryOrder = orderEntry.categoryOrder,
        )
    }

    // Order-entry success/error → snackbar
    LaunchedEffect(orderEntry.successMessage, orderEntry.error) {
        (orderEntry.successMessage ?: orderEntry.error)?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            tableViewModel.clearOrderEntryMessages()
        }
    }

    // Table management dialog
    if (tableManagement.isVisible) {
        TableManagementDialog(
            state = tableManagement,
            strings = strings,
            onUpdateNewTableLabel = { tableViewModel.updateNewTableLabel(it) },
            onAddTable = { tableViewModel.addTable() },
            onStartEdit = { tableViewModel.startEditTable(it) },
            onUpdateEditLabel = { tableViewModel.updateEditLabel(it) },
            onSaveEdit = { tableViewModel.saveEditTable() },
            onCancelEdit = { tableViewModel.cancelEdit() },
            onDeleteTable = { tableViewModel.deleteTable(it) },
            onDismiss = { tableViewModel.hideTableManagement() }
        )
    }

    // Daily Availability popup
    if (sessionState.showDailyPopup && sessionState.dailyItems.isNotEmpty()) {
        DailyAvailabilityDialog(
            items = sessionState.dailyItems,
            strings = strings,
            language = language,
            onConfirm = { updates ->
                updates.forEach { update ->
                    sessionViewModel.updateItemAvailability(
                        itemId = update.itemId,
                        available = update.available,
                        price = update.price
                    )
                }
                sessionViewModel.confirmDailyAvailability()
            },
            onDismiss = { sessionViewModel.dismissDailyPopup() }
        )
    }

    // Sign Out with Closing dialog
    if (showClosingDialog) {
        SignOutWithClosingDialog(
            closingState = sessionState.closingState,
            strings = strings,
            onConfirm = { reason ->
                sessionViewModel.signOutWithClosing(reason)
            },
            onDismiss = { showClosingDialog = false }
        )
    }

    // PIN gate before the overflow menu opens (when enabled). Success unlocks the whole menu;
    // Forgot-PIN resets the lock since this is the logged-in admin device.
    if (showPinGate) {
        PinEntryDialog(
            strings = strings,
            title = strings.pinRequiredTitle,
            onVerify = { pinLockViewModel.verifyPin(it) },
            onSuccess = { showPinGate = false; showOverflowMenu = true },
            onCancel = { showPinGate = false },
            onForgot = {
                pinLockViewModel.resetForgotten()
                showPinGate = false
                showOverflowMenu = true
            }
        )
    }

    // New ordering-staff device requesting to connect → approve/reject popup, auto-closing
    // after 30s (the request stays pending in Devices & Staff if ignored).
    val deviceRequest = pendingRequests.firstOrNull {
        it.id !in handledRequestIds &&
            !(sessionViewModel.isSecondaryAdmin && (it.role == "ADMIN" || it.role == "ADMIN_SECONDARY"))
    }
    if (deviceRequest != null) {
        LaunchedEffect(deviceRequest.id) {
            kotlinx.coroutines.delay(30_000)
            handledRequestIds = handledRequestIds + deviceRequest.id
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { handledRequestIds = handledRequestIds + deviceRequest.id },
            title = { Text(strings.newDeviceRequestTitle) },
            text = { Text(strings.newDeviceRequestBody.format(deviceRequest.label)) },
            confirmButton = {
                TextButton(onClick = {
                    devicesViewModel.approveDevice(deviceRequest.id)
                    handledRequestIds = handledRequestIds + deviceRequest.id
                }) { Text(strings.approveButton) }
            },
            dismissButton = {
                TextButton(onClick = {
                    devicesViewModel.rejectDevice(deviceRequest.id)
                    handledRequestIds = handledRequestIds + deviceRequest.id
                }) { Text(strings.rejectButton, color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    // Global "Pending Kitchen Prints" modal — lists every buffered order/session (auto-print
    // off) with its own Print-to-Kitchen button.
    if (showPendingPrints) {
        PendingKitchenPrintsDialog(
            pending = pendingPrints,
            strings = strings,
            onPrint = { orderId, sessionNumber -> tableViewModel.confirmSession(orderId, sessionNumber) },
            onClear = { orderId, sessionNumber -> tableViewModel.dismissPendingSession(orderId, sessionNumber) },
            onDismiss = { showPendingPrints = false }
        )
    }

    // Persistent kitchen print status list.
    if (showRecentPrints) {
        RecentPrintsDialog(prints = recentPrints, strings = strings, onDismiss = { showRecentPrints = false })
    }

    // Pre-send hold countdown (admin/staff 3s) — cancellable, blocks the screen.
    HoldCountdownOverlay(
        secondsRemaining = orderEntry.holdRemaining,
        onCancel = { tableViewModel.cancelSubmitHold() }
    )

    // Blocking overlay while an order is being submitted or order detail is loading
    // (Requirement B2.1, B2.2, B2.3)
    val isSubmitting = orderEntry.isSubmitting
    val isDetailLoading = orderDetail.isLoading
    BlockingLoadingOverlay(visible = isSubmitting || isDetailLoading)
    BackHandler(enabled = isSubmitting || isDetailLoading || orderEntry.holdRemaining != null) {
        /* block back navigation mid-submit / mid-hold */
    }
}

/** Recent kitchen print jobs with their status — answers "did it actually print?" */

/**
 * The café's active operating mode, as a compact chip (task 9.4, Requirement 1.4).
 *
 * All three modes are shown, including CLOUD. Hiding the default would make the badge's absence
 * ambiguous — it could equally mean "Cloud" or "this build is too old to have modes" — and the point
 * of the requirement is that the operator can always answer the question without entering Setup.
 *
 * LAN and Kiosk are tinted to stand out because those are the states where the answer changes what
 * the café can do; CLOUD is deliberately quiet so an existing install's top bar stays as it was.
 */
@Composable
private fun ModeBadge(mode: OperatingMode) {
    val (label, container, content) = when (mode) {
        OperatingMode.CLOUD -> Triple(
            "CLOUD",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OperatingMode.LAN -> Triple(
            "LAN",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        OperatingMode.KIOSK -> Triple(
            "KIOSK",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
        )
    }
}

@Composable
private fun RecentPrintsDialog(
    prints: List<com.razstudio.pos.data.local.PrintJob>,
    strings: com.razstudio.pos.ui.i18n.UiStrings,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.recentPrints) },
        text = {
            if (prints.isEmpty()) {
                Text(strings.noPrintsYet)
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(prints, key = { it.id }) { job ->
                        val statusColor = when (job.status) {
                            "COMPLETED" -> MaterialTheme.colorScheme.primary
                            "FAILED" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${job.documentType} — ${job.status}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = statusColor
                            )
                            job.lastError?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            Text(
                                text = job.createdAt.take(19).replace("T", " "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(strings.commonClose) }
        }
    )
}

/** Small non-clickable section header for grouping the overflow menu (Setup / Session). */
@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
    )
}

/**
 * Modal listing every buffered kitchen print (auto-print off), one row per table+session,
 * each with its own "Print to Kitchen" button that releases just that session.
 */
@Composable
private fun PendingKitchenPrintsDialog(
    pending: List<TableViewViewModel.PendingPrintGroup>,
    strings: com.razstudio.pos.ui.i18n.UiStrings,
    onPrint: (orderId: String, sessionNumber: Int) -> Unit,
    onClear: (orderId: String, sessionNumber: Int) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.pendingKitchenPrints) },
        text = {
            if (pending.isEmpty()) {
                Text(strings.allCaughtUpNoPrints)
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pending, key = { "${it.orderId}_${it.sessionNumber}" }) { group ->
                        androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Table ${group.tableLabel} — Session ${group.sessionNumber}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                group.items.forEach { item ->
                                    Text(
                                        text = "${item.quantity}× ${com.razstudio.pos.util.MenuName.display(item.nameSnapshot)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    text = "RM %.2f".format(group.total),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                androidx.compose.foundation.layout.Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { onClear(group.orderId, group.sessionNumber) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Clear")
                                    }
                                    androidx.compose.material3.Button(
                                        onClick = { onPrint(group.orderId, group.sessionNumber) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(strings.printToKitchenButton)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(strings.commonClose) }
        }
    )
}

/**
 * Persistent reminder that this device is the café's only copy (task 13.1, Requirement 8.2).
 *
 * Shown only in LAN and Kiosk Mode. A Cloud café has Supabase holding a second copy, so nagging it
 * would be noise — and a banner that appears when it does not matter is one an operator learns to
 * stop reading, which is exactly what breaks it for the cafés that do need it.
 *
 * States what is true rather than scolding: how long it has been, or that it has never happened.
 * "Never" is its own message because "0 days ago" would read as reassurance.
 */
@Composable
private fun BackupNagBanner(
    lastBackupAtMs: Long,
    visible: Boolean,
    strings: UiStrings,
    onBackUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val days = if (lastBackupAtMs <= 0L) -1
    else ((System.currentTimeMillis() - lastBackupAtMs) / 86_400_000L).toInt()

    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (days < 0) strings.backupBannerNever
                       else strings.backupBannerStale.format(days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBackUp) { Text(strings.backupBannerAction) }
        }
    }
}
