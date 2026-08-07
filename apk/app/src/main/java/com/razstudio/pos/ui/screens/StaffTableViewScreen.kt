package com.razstudio.pos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.razstudio.pos.data.customChargeMenuItem
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.data.local.PaymentMethod
import com.razstudio.pos.data.local.Table
import com.razstudio.pos.ui.components.BlockingLoadingOverlay
import com.razstudio.pos.ui.components.HoldCountdownOverlay
import com.razstudio.pos.ui.i18n.LanguageButton
import com.razstudio.pos.ui.theme.ThemeButton
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.tableview.CartLine
import com.razstudio.pos.ui.tableview.GatewayCheckoutOverlay
import com.razstudio.pos.ui.tableview.MerchantScanRequest
import com.razstudio.pos.ui.tableview.OrderDetailSheet
import com.razstudio.pos.ui.tableview.OrderEntrySheet
import com.razstudio.pos.ui.tableview.TableGrid
import com.razstudio.pos.ui.tableview.TableState
import com.razstudio.pos.ui.tableview.TableUiStatus
import com.razstudio.pos.ui.viewmodels.StaffOrderViewModel

/**
 * Staff Table View — the primary ordering screen after check-in.
 * Shows color-coded table grid (shared [TableGrid] component), order detail bottom sheet
 * with RBAC-controlled actions (shared [OrderDetailSheet]), and a FAB for new order entry.
 *
 * The local StaffTableCell and StaffOrderDetailSheet composables have been removed in favour
 * of the shared implementations from [com.razstudio.pos.ui.tableview] (Requirements 5.2, 5.3).
 * The offline-pending banner and check-out flow are unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffTableViewScreen(
    viewModel: StaffOrderViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
    cashDrawerViewModel: com.razstudio.pos.ui.viewmodels.CashDrawerViewModel = hiltViewModel(),
    /**
     * Avatar -> Mode Logout finished. The caller clears the stack back to the home screen; the
     * Google account is untouched, so the owner's other cafés are still listed when they land.
     */
    onAccountSignedOut: () -> Unit = {},
    onCheckOut: () -> Unit,
) {
    // Pull the floor plan if this device does not already hold this café's. Staff previously had
    // no path to this at all, so a freshly-joined phone showed an empty grid indefinitely.
    LaunchedEffect(Unit) { viewModel.syncTablesIfNeeded() }

    val tableStates by viewModel.tableStates.collectAsState()
    val orderDetail by viewModel.orderDetail.collectAsState()
    val orderEntry by viewModel.orderEntry.collectAsState()
    val permissions by viewModel.permissions.collectAsState()
    val pendingCount by viewModel.pendingOrderCount.collectAsState()
    val availableMenu by viewModel.availableMenu.collectAsState()
    val gatewayMethods by viewModel.gatewayMethods.collectAsState()
    val gatewayCheckout by viewModel.gatewayCheckout.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    var showOrderSheet by remember { mutableStateOf(false) }
    var showCalculator by remember { mutableStateOf(false) }
    var selectedTableLabel by remember { mutableStateOf("") }
    var showTableSelectForOrder by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Merchant-scan (task 8.3) — see AdminHomeScreen's identical wiring.
    val context = LocalContext.current
    var merchantScanRequest by remember { mutableStateOf<MerchantScanRequest?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // Handle order detail messages
    LaunchedEffect(orderDetail.error) {
        orderDetail.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(orderDetail.successMessage) {
        orderDetail.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
            if (orderDetail.order == null) {
                showOrderSheet = false
            }
        }
    }

    // Handle order entry messages
    LaunchedEffect(orderEntry.successMessage) {
        orderEntry.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearOrderEntryMessages()
        }
    }

    LaunchedEffect(orderEntry.error) {
        orderEntry.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearOrderEntryMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(strings.tableView)
                        if (pendingCount > 0) {
                            Text(
                                text = "⚠️ $pendingCount order(s) pending — offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
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
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onCheckOut,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.checkOutButton, style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showTableSelectForOrder = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = strings.newOrder)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (tableStates.isEmpty()) {
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
                        text = strings.askAdminAddTables,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Shared table grid — same component as admin screen (Requirement 5.2)
                TableGrid(
                    tableStates = tableStates,
                    strings = strings,
                    onTableClick = { tableState ->
                        if (tableState.status == TableUiStatus.FREE) {
                            viewModel.startOrderEntry(tableState.table.id, tableState.table.label)
                        } else {
                            selectedTableLabel = tableState.table.label
                            viewModel.loadOrderForTable(tableState.table.id)
                            showOrderSheet = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Calculator, opposite the new-order button. Same reasoning as the admin home: the
            // busy FAB keeps the right, this takes the left. Staff need the till open to give
            // change as often as an admin does.
            // Icon-only, matching the new-order FAB opposite; the title lives on for TalkBack.
            FloatingActionButton(
                onClick = { showCalculator = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Icon(Icons.Default.Calculate, contentDescription = strings.calculatorTitle)
            }

            // Offline banner at bottom (unchanged)
            if (pendingCount > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "⚠️ $pendingCount order(s) queued offline — will send when connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }

    if (showCalculator) {
        com.razstudio.pos.ui.calculator.CalculatorDialog(
            strings = strings,
            onDismiss = { showCalculator = false },
        )
    }

    // Shared order detail bottom sheet with RBAC-gated permissions (Requirement 5.2, 5.3)
    if (showOrderSheet) {
        OrderDetailSheet(
            // Governed by the café's "Staff can Take Payment" setting: the whole payment block,
            // split included, only renders when permissions.canTakePayment is true.
            allowSplitPayment = true,
            onSplitShare = { orderId, tableId, plan, method, printReceipt ->
                viewModel.paySplitShare(orderId, tableId, plan, method, printReceipt)
            },
            state = orderDetail,
            tableLabel = selectedTableLabel,
            permissions = permissions,
            strings = strings,
            menuItems = availableMenu,
            language = language,
            onAddItems = { orderId, items -> viewModel.addItemsToOrder(orderId, items) },
            onReprintSession = { orderId, sessionNumber -> viewModel.reprintSession(orderId, sessionNumber) },
            onConfirmSession = { orderId, sessionNumber -> viewModel.confirmSession(orderId, sessionNumber) },
            onPayment = { orderId, method, printReceipt -> viewModel.processPayment(orderId, method, printReceipt) },
            onVoidItems = { orderId, itemIds, reason -> viewModel.voidItems(orderId, itemIds, reason) },
            gatewayMethods = gatewayMethods,
            onGatewayCheckout = { orderId, method, amount, printReceipt ->
                viewModel.startGatewayCheckout(orderId, method, amount, printReceipt)
            },
            onGatewaySplitCheckout = { orderId, tableId, plan, method, printReceipt ->
                viewModel.startGatewaySplitCheckout(orderId, tableId, plan, method, printReceipt)
            },
            onRequestMerchantScan = { orderId, tableId, method, amount, printReceipt ->
                merchantScanRequest = MerchantScanRequest(orderId, tableId, method, amount, printReceipt)
            },
            onRequestMerchantScanSplit = { orderId, tableId, plan, method, printReceipt ->
                merchantScanRequest = MerchantScanRequest(
                    orderId = orderId, tableId = tableId, method = method,
                    amount = plan.amount, printReceipt = printReceipt, splitPlan = plan,
                )
            },
            onResumeGatewayCheckout = { pending -> viewModel.resumeGatewayCheckout(pending) },
            onCashTendered = { orderId, totalSen, tenderedSen ->
                cashDrawerViewModel.recordCashSale(orderId, totalSen, tenderedSen)
            },
            onCancel = { orderId, reason -> viewModel.cancelOrder(orderId, reason) },
            onDismiss = {
                showOrderSheet = false
                viewModel.clearOrderDetail()
            }
        )
    }

    // Full-screen gateway checkout (task 8.1/8.2) — see GatewayCheckoutOverlay's own doc for why
    // this is a true Dialog rather than another bottom sheet.
    gatewayCheckout?.let { checkoutState ->
        GatewayCheckoutOverlay(
            state = checkoutState,
            strings = strings,
            onCancel = { viewModel.cancelGatewayCheckout() },
            onDismiss = { viewModel.dismissGatewayCheckout() },
            onNudgePoll = { viewModel.nudgeGatewayPoll() },
        )
    }

    // Merchant-scan camera (task 8.3) — see AdminHomeScreen's identical wiring.
    merchantScanRequest?.let { req ->
        QrScannerScreen(
            hasCameraPermission = hasCameraPermission,
            onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            onQrDecoded = { code ->
                val plan = req.splitPlan
                if (plan != null) {
                    viewModel.startGatewaySplitCheckout(
                        req.orderId, req.tableId, plan, req.method,
                        printReceipt = req.printReceipt, customerAuthCode = code,
                    )
                } else {
                    viewModel.startGatewayCheckout(req.orderId, req.method, req.amount, req.printReceipt, code)
                }
                merchantScanRequest = null
            },
            onCancel = { merchantScanRequest = null },
            promptText = strings.merchantScanPrompt,
            cancelText = strings.commonCancel,
            grantText = strings.cameraPermissionRequired,
        )
    }

    // Table select for new order (from the FAB)
    if (showTableSelectForOrder) {
        TableSelectDialog(
            tableStates = tableStates,
            strings = strings,
            onTableSelected = { table ->
                showTableSelectForOrder = false
                viewModel.startOrderEntry(table.id, table.label)
            },
            onDismiss = { showTableSelectForOrder = false }
        )
    }

    // Shared tabbed-category order entry modal (menu + cart)
    if (orderEntry.isVisible) {
        OrderEntrySheet(
            tableLabel = orderEntry.selectedTableLabel ?: "",
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
            onAdd = { item, note, size, price -> viewModel.addToCart(item, 1, note, size, price) },
            // A hand-typed charge enters the cart as a synthetic menu item, so every existing cart
            // path (dedupe, receipt preview, submit) handles it unchanged.
            onAddCustom = { name, price ->
                viewModel.addToCart(customChargeMenuItem(name, price), 1, unitPrice = price)
            },
            onRemove = { index -> viewModel.removeFromCartAt(index) },
            onSubmit = { viewModel.submitOrder() },
            onDismiss = { viewModel.dismissOrderEntry() },
            categoryOrder = orderEntry.categoryOrder
        )
    }

    // Pre-send hold countdown (staff 3s) — cancellable.
    HoldCountdownOverlay(
        secondsRemaining = orderEntry.holdRemaining,
        onCancel = { viewModel.cancelSubmitHold() }
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

// --- Table Select Dialog ---

@Composable
private fun TableSelectDialog(
    tableStates: List<TableState>,
    strings: com.razstudio.pos.ui.i18n.UiStrings,
    onTableSelected: (Table) -> Unit,
    onDismiss: () -> Unit
) {
    val freeTables = tableStates.filter { it.status == TableUiStatus.FREE }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.selectTableForOrder) },
        text = {
            if (freeTables.isEmpty()) {
                Text(strings.noFreeTables)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(freeTables) { tableState ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTableSelected(tableState.table) }
                        ) {
                            Text(
                                text = tableState.table.label,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.commonCancel)
            }
        }
    )
}

