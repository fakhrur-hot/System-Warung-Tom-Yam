@file:OptIn(ExperimentalMaterial3Api::class)

package com.razstudio.pos.ui.tableview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.razstudio.pos.ui.components.PaymentQrDialog
import com.razstudio.pos.ui.util.PaymentQrPipeline
import com.razstudio.pos.data.AppConfigStore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.data.local.OrderActions
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.ui.i18n.AppLanguage
import com.razstudio.pos.ui.i18n.UiStrings
import kotlinx.coroutines.delay

/**
 * Shared order detail bottom sheet parameterized by [StaffPermissions].
 *
 * Action buttons are shown only when both:
 *  - the relevant permission flag in [permissions] is `true`, and
 *  - [OrderActions] confirms the action is valid for the current [OrderDetailState.order] status.
 *
 * This is the single implementation used by both the admin role
 * (`permissions = StaffPermissions.ADMIN`) and the staff role (runtime permissions).
 * It must NOT import or reference [TableViewViewModel] or [StaffOrderViewModel].
 *
 * Items are grouped by [OrderItem.sessionNumber] (one group per order-placement round —
 * the table's first order, then each subsequent round of items added to it while still
 * occupied), and within each session, by category. Each session has its OWN reprint
 * button ([onReprintSession]) so the kitchen can reprint one specific round — e.g. the
 * newly-placed round — rather than the whole ticket, which would make it impossible to
 * tell a fresh round from earlier rounds already cooked and served.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailSheet(
    state: OrderDetailState,
    tableLabel: String,
    permissions: StaffPermissions,
    strings: UiStrings,
    menuItems: List<MenuItem> = emptyList(),
    language: AppLanguage = AppLanguage.DEFAULT,
    onAddItems: (orderId: String, items: List<NewOrderItem>) -> Unit = { _, _ -> },
    onConfirmSession: (orderId: String, sessionNumber: Int) -> Unit = { _, _ -> },
    onReprintSession: (orderId: String, sessionNumber: Int) -> Unit = { _, _ -> },
    onPayment: (orderId: String, method: String, printReceipt: Boolean) -> Unit,
    onCancel: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCancelDialog by remember { mutableStateOf(false) }
    var showAddItemPicker by remember(state.order?.id) { mutableStateOf(false) }
    var stagedCart by remember(state.order?.id) { mutableStateOf(listOf<StagedCartLine>()) }
    var pendingPaymentMethod by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // ── No active order — nothing to arrange in two panes ────────────────────────
        val order = state.order
        if (order == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = "${strings.tableWord}: $tableLabel",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.noActiveOrder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            return@ModalBottomSheet
        }

        // ── The two panes, as local lambdas ──────────────────────────────────────────
        // Local lambdas rather than extracted composables, deliberately: every block below
        // closes over a dozen locals (state, order, permissions, strings, the callbacks, and the
        // pendingPaymentMethod / showCancelDialog setters). Threading those through parameter
        // lists is precisely where a behaviour change sneaks into a layout refactor, so the
        // bodies below are left byte-for-byte alone — only their arrangement changes.
        val receiptPane: @Composable ColumnScope.() -> Unit = {
            Text(
                text = "${strings.tableWord}: $tableLabel",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // ── Order info ────────────────────────────────────────────────────────
            // The internal order UUID is intentionally not shown — it's a database id with
            // no meaning to staff and only clutters the header. Status alone is enough.
            Text(
                text = "${strings.status}: ${order.status.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            val canAddItems = !order.status.isTerminal

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.items,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (canAddItems) {
                    AddItemCircleButton(
                        contentDescription = strings.addItemCd,
                        onClick = {
                            showAddItemPicker = !showAddItemPicker
                            if (!showAddItemPicker) stagedCart = emptyList()
                        },
                    )
                }
            }

            // Inline, expandable tabbed menu picker. Picks are staged locally (not sent
            // immediately) so a whole round of newly-added items becomes ONE order
            // round (one sessionNumber, one kitchen print) instead of a separate print
            // per tap.
            AnimatedVisibility(visible = showAddItemPicker && canAddItems) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    CategoryMenuPicker(
                        menuItems = menuItems,
                        language = language,
                        strings = strings,
                        onAdd = { menuItem, note, size, unitPrice ->
                            val n = note?.trim()?.ifBlank { null }
                            val existing = stagedCart.indexOfFirst {
                                it.menuItem.id == menuItem.id && it.note == n && it.size == size
                            }
                            stagedCart = if (existing >= 0) {
                                stagedCart.toMutableList().also {
                                    it[existing] = it[existing].copy(quantity = it[existing].quantity + 1)
                                }
                            } else {
                                stagedCart + StagedCartLine(menuItem, 1, n, size, unitPrice)
                            }
                        },
                    )

                    if (stagedCart.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        stagedCart.forEachIndexed { index, line ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${line.quantity}× ${language.menuName(line.menuItem)}${line.size?.let { " ($it)" } ?: ""}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            stagedCart = stagedCart.toMutableList().also { it.removeAt(index) }
                                        },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = strings.commonDelete,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                if (!line.note.isNullOrBlank()) {
                                    Text(
                                        text = "   + ${line.note}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                onAddItems(
                                    order.id,
                                    stagedCart.map { NewOrderItem(menuItemId = it.menuItem.id, quantity = it.quantity, note = it.note, unitPrice = it.unitPrice, size = it.size) },
                                )
                                stagedCart = emptyList()
                                showAddItemPicker = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${strings.addItemsToOrderButton} (${stagedCart.sumOf { it.quantity }})")
                        }
                    }
                }
            }

            // ── Items grouped by session (order-placement round), then by category ──
            val sessionGroups = state.items.groupBy { it.sessionNumber }.toSortedMap()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
            ) {
                sessionGroups.forEach { (sessionNumber, sessionItems) ->
                    // Partition into confirmed (sentToKitchen=true) and pending (sentToKitchen=false)
                    val (confirmedItems, pendingItems) = sessionItems.partition { it.sentToKitchen }

                    // ── Confirmed items — render as before ──────────────────────────────
                    if (confirmedItems.isNotEmpty()) {
                        item {
                            Text(
                                text = "${strings.orderSessionLabel} $sessionNumber",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        val byCategory = confirmedItems.groupBy { it.categorySnapshot }
                        byCategory.forEach { (category, categoryItems) ->
                            item {
                                Text(
                                    text = categoryLabel(category, strings),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 1.dp),
                                )
                            }
                            items(categoryItems, key = { it.id }) { item ->
                                OrderItemRow(
                                    item = item,
                                    displayName = language.localizedSnapshotName(
                                        item.nameSnapshot,
                                        menuItems.find { it.id == item.menuItemId }
                                    )
                                )
                            }
                        }

                        // Per-session reprint — reprints ONLY this round's slip (marked
                        // "Session #N") so the kitchen can tell it apart from other rounds.
                        if (permissions.canSendToKitchen) {
                            item {
                                OutlinedButton(
                                    onClick = { onReprintSession(order.id, sessionNumber) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 4.dp),
                                    enabled = !state.isLoading,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("${strings.reprintToKitchenButton} (${strings.orderSessionLabel} $sessionNumber)")
                                }
                            }
                        }
                    }

                    // ── Pending items — own labeled block with scoped Print button ──────
                    if (pendingItems.isNotEmpty()) {
                        item {
                            Text(
                                text = "${strings.orderSessionLabel} $sessionNumber — ${strings.sessionPendingConfirmation}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(pendingItems, key = { "pending_${it.id}" }) { item ->
                            OrderItemRow(
                                item = item,
                                displayName = language.localizedSnapshotName(
                                    item.nameSnapshot,
                                    menuItems.find { it.id == item.menuItemId }
                                )
                            )
                        }
                        item {
                            OutlinedButton(
                                onClick = { state.order?.id?.let { onConfirmSession(it, sessionNumber) } },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                enabled = !state.isLoading,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${strings.printToKitchenButton} (${strings.orderSessionLabel} $sessionNumber)")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))

            // ── Subtotal + Grand Total (receipt-style) ─────────────────────────────
            val subtotal = state.items.sumOf { it.unitPriceSnapshot * it.quantity }
            ReceiptTotal(label = strings.subtotal, amount = subtotal, bold = false)
            ReceiptTotal(label = strings.grandTotal, amount = order.total, bold = true)

            // ── Loading indicator ─────────────────────────────────────────────────
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Actions pane ─────────────────────────────────────────────────────────────
        // (Kitchen reprint is per-session, rendered under each session block in the
        // receipt pane, so the kitchen can reprint one round at a time.)
        val actionsPane: @Composable ColumnScope.() -> Unit = {

            // ── Action buttons ────────────────────────────────────────────────────
            // (Kitchen reprint is now per-session, rendered under each session block above,
            // so the kitchen can reprint one round at a time rather than the whole ticket.)

            // Payment buttons — Cash and QR, side by side. Tapping either opens the
            // print-receipt confirm dialog below; the actual payment call only fires
            // once that dialog resolves (Yes/Skip/timeout).
            if (permissions.canTakePayment && OrderActions.canTakePayment(order.status)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { pendingPaymentMethod = "CASH" },
                        modifier = Modifier.weight(1f),
                        enabled = !state.isLoading,
                    ) {
                        Text(strings.payCash)
                    }
                    Button(
                        onClick = { pendingPaymentMethod = "QR" },
                        modifier = Modifier.weight(1f),
                        enabled = !state.isLoading,
                    ) {
                        Text(strings.payQR)
                    }
                }

                // ── Show QR (task 17.3, Requirements 13.1-13.3) ──────────────────────────────
                // Small outlined button directly under the two payment buttons, deliberately
                // subordinate to them. Shown under exactly the same condition as those buttons, so
                // any device permitted to take payment can present the code — admin or staff alike.
                //
                // Visibility is `hash != null`, never mode-dependent: the Payment QR exists in all
                // three operating modes (Requirement 14.7). When nothing is configured the button is
                // ABSENT rather than shown-and-disabled or shown-then-failing (Requirement 13.3) —
                // a control that dies in front of a waiting customer is worse than no control.
                val qrContext = LocalContext.current
                val paymentQrHash = remember { AppConfigStore(qrContext).paymentQrHash() }
                val paymentQrBitmap = remember(paymentQrHash) {
                    if (paymentQrHash == null) null else PaymentQrPipeline.loadFromInternal(qrContext)
                }
                var showPaymentQr by remember { mutableStateOf(false) }

                if (paymentQrHash != null && paymentQrBitmap != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showPaymentQr = true },
                        enabled = !state.isLoading,
                    ) {
                        Text(strings.showQrButton)
                    }
                    if (showPaymentQr) {
                        PaymentQrDialog(
                            qr = paymentQrBitmap,
                            onDismiss = { showPaymentQr = false },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Cancel Order — opens inline AlertDialog to collect reason
            if (permissions.canCancel && OrderActions.canCancel(order.status)) {
                OutlinedButton(
                    onClick = { showCancelDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(strings.cancelOrder)
                }
            }
        }

        // ── Orientation decides the arrangement ───────────────────────────────────────
        // Landscape on this hardware is 2720x1224 — only ~1224px tall. A single stacked column
        // pushed Pay Cash / Pay QR / Show QR / Cancel below the fold, and because that column had
        // no verticalScroll they were not merely hidden but UNREACHABLE: an order simply could not
        // be paid in landscape. Landscape now splits into receipt on the left and actions on the
        // right, each scrolling independently, so the payment buttons stay put however many items
        // or sessions the order grows to.
        //
        // Both branches scroll now. Portrait was unscrollable too and clipped long multi-session
        // orders the same way — just rarely enough that it went unnoticed.
        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(end = 16.dp),
                ) { receiptPane() }

                VerticalDivider(modifier = Modifier.fillMaxHeight())

                // Actions sit at the BOTTOM of their column, under the cashier's thumb.
                //
                // A Box wrapper rather than `verticalArrangement = Arrangement.Bottom` on the Column:
                // once a Column has verticalScroll it is measured with unbounded height, so fillMaxHeight
                // does not apply inside it and Bottom silently does nothing. Aligning in a
                // fixed-height Box works in both cases — content shorter than the pane is pushed down,
                // and content taller than it fills the pane and scrolls as before.
                Box(
                    modifier = Modifier
                        .width(ACTIONS_PANE_WIDTH)
                        .fillMaxHeight()
                        .padding(start = 16.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) { actionsPane() }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 32.dp),
            ) {
                receiptPane()
                actionsPane()
            }
        }
    }

    // ── Cancel confirmation dialog ────────────────────────────────────────────────
    if (showCancelDialog && state.order != null) {
        CancelReasonDialog(
            strings = strings,
            onConfirm = { reason ->
                onCancel(state.order.id, reason)
                showCancelDialog = false
            },
            onDismiss = { showCancelDialog = false },
        )
    }

    // ── Payment receipt-print confirm dialog ──────────────────────────────────────
    val paymentMethod = pendingPaymentMethod
    if (paymentMethod != null && state.order != null) {
        ReceiptPrintConfirmDialog(
            strings = strings,
            onResolve = { shouldPrint ->
                onPayment(state.order.id, paymentMethod, shouldPrint)
                pendingPaymentMethod = null
            },
        )
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────────

private data class StagedCartLine(
    val menuItem: MenuItem,
    val quantity: Int,
    val note: String? = null,
    val size: String? = null,
    val unitPrice: Double? = null,
)

private fun categoryLabel(category: String, strings: UiStrings): String = when (category.uppercase()) {
    "FOOD" -> strings.catFood
    "BEVERAGES" -> strings.catBeverages
    "SIDE_DISHES", "SIDE DISHES" -> strings.catSideDishes
    else -> strings.catOthers
}

/**
 * Width of the landscape actions column. Wide enough that Pay Cash and Pay QR sit side by
 * side without their labels wrapping in any of the five supported languages, and narrow
 * enough to leave the receipt the majority of a 2720px-wide screen.
 */
private val ACTIONS_PANE_WIDTH = 300.dp

@Composable
private fun OrderItemRow(item: OrderItem, displayName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.quantity}× $displayName",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.note.isNullOrBlank()) {
                Text(
                    text = "  + ${item.note}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "RM %.2f".format(item.unitPriceSnapshot * item.quantity),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Circular "+" button that lets admin/staff add more items directly to an already
 * table's order. Tapping it toggles the inline [CategoryMenuPicker] below the header.
 */
@Composable
private fun AddItemCircleButton(contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ReceiptTotal(label: String, amount: Double, bold: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = "RM %.2f".format(amount),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun CancelReasonDialog(
    strings: UiStrings,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.cancelOrder) },
        text = {
            Column {
                Text(strings.cancelReasonLabel)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    placeholder = { Text(strings.enterReasonHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason.ifBlank { strings.noReasonGiven }) },
            ) {
                Text(strings.confirmCancelButton, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.commonBack)
            }
        },
    )
}

private const val RECEIPT_DIALOG_COUNTDOWN_SECONDS = 10

/**
 * Asks whether to print a customer receipt after payment. Auto-closes after
 * [RECEIPT_DIALOG_COUNTDOWN_SECONDS] with no tap — same as tapping Skip (no print).
 * Payment itself always completes once this resolves, regardless of the print choice.
 */
@Composable
private fun ReceiptPrintConfirmDialog(
    strings: UiStrings,
    onResolve: (shouldPrint: Boolean) -> Unit,
) {
    var secondsRemaining by remember { mutableIntStateOf(RECEIPT_DIALOG_COUNTDOWN_SECONDS) }

    LaunchedEffect(Unit) {
        while (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining -= 1
        }
        onResolve(false)
    }

    AlertDialog(
        onDismissRequest = { /* must choose explicitly or wait for the countdown */ },
        title = { Text(strings.receiptPrintQuestion) },
        text = {
            Text(
                "${strings.receiptAutoCloseLabel} ${secondsRemaining}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = { onResolve(true) }) {
                Text(strings.receiptPrintYes)
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(false) }) {
                Text(strings.receiptPrintSkip)
            }
        },
    )
}
