@file:OptIn(ExperimentalMaterial3Api::class)

package com.razstudio.pos.ui.tableview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.res.Configuration
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.data.local.OrderActions
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.data.local.PaymentCategory
import com.razstudio.pos.data.local.PaymentMethod
import com.razstudio.pos.data.local.PaymentTransaction
import com.razstudio.pos.ui.i18n.AppLanguage
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.viewmodels.SplitPaymentPlanner
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
    onVoidItems: (orderId: String, lines: List<VoidLine>, reason: String) -> Unit = { _, _, _ -> },
    /**
     * Settle one customer's share of a group bill. Only ever called with a SliceOff plan.
     * [printReceipt] comes from the same print-confirm dialog every payment method shows — every
     * split-off customer gets the choice, not just whoever pays the last share.
     */
    onSplitShare: (
        orderId: String,
        tableId: String?,
        plan: SplitPaymentPlanner.Plan.SliceOff,
        method: String,
        printReceipt: Boolean,
    ) -> Unit = { _, _, _, _, _ -> },
    /**
     * Whether this caller can settle shares. Off by default, and the whole split UI disappears with
     * it — the staff table view has no handler wired, and a radio that leads to a pay button doing
     * nothing is worse than no radio at all.
     */
    allowSplitPayment: Boolean = false,
    /**
     * Gateway channels to show alongside Pay Cash / Pay QR (task 7.2, A13). Empty by default, and
     * the whole row disappears with it — the caller computes this from
     * `ModeCapabilities.gatewayPaymentsEnabled` and the café's enabled channels, never read here
     * directly, so this sheet stays free of a `BackendGateway`/`ModeViewModel` dependency.
     */
    gatewayMethods: List<PaymentMethod> = emptyList(),
    /**
     * Start a gateway checkout for the whole bill (task 8.1/8.2). Unlike [onPayment], this does
     * not complete the order immediately — the caller polls the acquirer first and only then calls
     * the equivalent of [onPayment] itself.
     */
    onGatewayCheckout: (
        orderId: String,
        method: PaymentMethod,
        amount: Double,
        printReceipt: Boolean,
    ) -> Unit = { _, _, _, _ -> },
    /** Gateway equivalent of [onSplitShare] — only ever called with a SliceOff plan. */
    onGatewaySplitCheckout: (
        orderId: String,
        tableId: String?,
        plan: SplitPaymentPlanner.Plan.SliceOff,
        method: PaymentMethod,
        printReceipt: Boolean,
    ) -> Unit = { _, _, _, _, _ -> },
    /**
     * A merchant-scan channel (`PaymentCategory.E_WALLET` — TNG/GrabPay/Boost/ShopeePay) was
     * chosen for the whole bill (task 8.3). The caller opens a camera scanner and, once a barcode
     * is captured, starts the checkout itself — this sheet never touches the camera directly.
     */
    onRequestMerchantScan: (
        orderId: String,
        tableId: String?,
        method: PaymentMethod,
        amount: Double,
        printReceipt: Boolean,
    ) -> Unit = { _, _, _, _, _ -> },
    /** Merchant-scan equivalent of [onGatewaySplitCheckout]. */
    onRequestMerchantScanSplit: (
        orderId: String,
        tableId: String?,
        plan: SplitPaymentPlanner.Plan.SliceOff,
        method: PaymentMethod,
        printReceipt: Boolean,
    ) -> Unit = { _, _, _, _, _ -> },
    /**
     * A gateway attempt is still PENDING from before this order was last open (task 8.5) — offers
     * to resume it. Absent (null) once nothing is pending, same "absent, not disabled" rule as
     * everywhere else in this sheet.
     */
    onResumeGatewayCheckout: (PaymentTransaction) -> Unit = {},
    onCancel: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCancelDialog by remember { mutableStateOf(false) }
    var showAddItemPicker by remember(state.order?.id) { mutableStateOf(false) }
    var stagedCart by remember(state.order?.id) { mutableStateOf(listOf<StagedCartLine>()) }
    var pendingPaymentMethod by remember { mutableStateOf<String?>(null) }
    var pendingGatewayMethod by remember { mutableStateOf<PaymentMethod?>(null) }
    // Split state lives here rather than in the ViewModel: it is a cashier's working scratchpad for
    // one customer standing at the counter, and it must not survive the sheet closing.
    var splitMode by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    // A SliceOff (not the final share) waiting on the same print-confirm choice every other
    // payment method gets, before it is handed to onSplitShare/onGatewaySplitCheckout/
    // onRequestMerchantScanSplit. SplitPaymentDialog hides while this is set so the two dialogs
    // never stack.
    var pendingSplitAction by remember { mutableStateOf<PendingSplitAction?>(null) }

    // ── Voiding unserved lines at payment time ─────────────────────────────────────
    // The café's actual counter situation: the customer is leaving, says a dish never came, and wants
    // to pay for what they got. Both are keyed on the order id so switching tables cannot carry a
    // half-made selection onto a different bill.
    var editItemsMode by remember(state.order?.id) { mutableStateOf(false) }
    // itemId -> quantity the cashier wants to KEEP. Absent means untouched (keep all of it), which
    // is why this starts empty rather than pre-filled: only lines actually adjusted are ever sent.
    var keepQuantities by remember(state.order?.id) { mutableStateOf(mapOf<String, Int>()) }
    var showVoidDialog by remember { mutableStateOf(false) }

    /** Quantity that would remain on a line, defaulting to its current quantity. */
    fun keptQty(item: OrderItem): Int = keepQuantities[item.id] ?: item.quantity

    /** Lines the cashier actually changed — both the payload and the "is there anything to do" test. */
    val adjustedLines = state.items
        .filter { keptQty(it) != it.quantity }
        .map { VoidLine(itemId = it.id, keepQuantity = keptQty(it)) }

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
                                    ),
                                    editable = editItemsMode,
                                    keepQuantity = keptQty(item),
                                    onKeepQuantityChange = { q ->
                                        keepQuantities = keepQuantities + (item.id to q)
                                    },
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
                                ),
                                // Pending lines are reducible too, and are the safest case: the
                                // kitchen never received them, so nothing was cooked.
                                editable = editItemsMode,
                                keepQuantity = keptQty(item),
                                onKeepQuantityChange = { q ->
                                    keepQuantities = keepQuantities + (item.id to q)
                                },
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

            // While marking unserved lines, show what the customer would actually pay. The server
            // recomputes this from the surviving lines, so it is a preview of that same arithmetic —
            // the cashier can read the figure out loud before committing to it.
            if (editItemsMode && adjustedLines.isNotEmpty()) {
                val projected = state.items.sumOf { it.unitPriceSnapshot * keptQty(it) }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = strings.newTotalLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "RM %.2f".format(projected),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

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

            // ── Errors, shown inside the sheet ────────────────────────────────────
            // The host screen already pushes these to a Scaffold snackbar, but that snackbar renders
            // BEHIND this ModalBottomSheet, so while the sheet is open it is invisible. Voiding made
            // that gap matter: a refusal ("that would remove every line") looked exactly like nothing
            // happening — the dialog closed and the bill was unchanged with no explanation. The host
            // clears the error only after its snackbar finishes, so this shows for the same window.
            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }

            // ── Action buttons ────────────────────────────────────────────────────
            // (Kitchen reprint is now per-session, rendered under each session block above,
            // so the kitchen can reprint one round at a time rather than the whole ticket.)

            // Payment buttons — Cash and QR, side by side. Tapping either opens the
            // print-receipt confirm dialog below; the actual payment call only fires
            // once that dialog resolves (Yes/Skip/timeout).
            // ── Marking unserved lines ────────────────────────────────────────────
            // While in this mode the payment buttons are deliberately replaced rather than merely
            // disabled: a half-made selection must not be payable, and putting Pay Cash next to a
            // live checkbox list is how the wrong amount gets taken.
            if (editItemsMode) {
                Text(
                    text = strings.voidItemsDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showVoidDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading && adjustedLines.isNotEmpty(),
                ) {
                    Text("${strings.voidItemsConfirm} (${adjustedLines.size})")
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        editItemsMode = false
                        keepQuantities = emptyMap()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                ) {
                    Text(strings.commonBack)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!editItemsMode &&
                permissions.canTakePayment && OrderActions.canTakePayment(order.status)
            ) {
                // ── Crash/resume recovery (task 8.5) ─────────────────────────────────────────
                // A gateway attempt left PENDING when this device last closed the sheet (or
                // crashed) — surfaced here rather than silently re-showing the ordinary Pay
                // Cash/QR row as though nothing were in flight, which would invite a second,
                // duplicate charge attempt for the same bill.
                state.pendingGatewayTransaction?.let { pending ->
                    val pendingMethod = PaymentMethod.fromCode(pending.paymentMethod)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                strings.gatewayPendingBannerTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (pendingMethod != null) {
                                Text(
                                    "${paymentMethodLabel(pendingMethod, strings)} · RM %.2f".format(pending.ringgit),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(
                            onClick = { onResumeGatewayCheckout(pending) },
                            enabled = !state.isLoading,
                        ) {
                            Text(strings.gatewayPendingResumeButton)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── Two ways to settle, chosen before the money moves ────────────────────────
                //
                // Whole-bill stays first and stays the default: most tables pay once, and a cashier
                // should not have to dismiss a choice to do the ordinary thing.
                //
                // Split exists because groups arrive together and pay separately. Without it the
                // cashier either made one person cover everyone or did the arithmetic on paper and
                // told the till something untrue about who paid how.
                // A radio group of one is not a choice. With split unavailable the screen stays
                // exactly as it was before this feature existed.
                if (allowSplitPayment) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = !splitMode,
                                onClick = { splitMode = false },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = !splitMode, onClick = { splitMode = false })
                        Text(strings.payWholeBillOption, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (!splitMode) {
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

                    if (gatewayMethods.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        GatewayMethodRow(
                            methods = gatewayMethods,
                            strings = strings,
                            enabled = !state.isLoading,
                            onSelect = { method -> pendingGatewayMethod = method },
                        )
                    }
                }

                if (allowSplitPayment) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = splitMode,
                                onClick = { splitMode = true },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = splitMode, onClick = { splitMode = true })
                        Text(strings.splitPaymentOption, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (splitMode && allowSplitPayment) {
                    Button(
                        onClick = { showSplitDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isLoading && state.items.isNotEmpty(),
                    ) {
                        Text(strings.splitPaymentButton)
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
                // Keyed on the order id so the hash is re-read each time the sheet is opened for a
                // different order, rather than being captured once for the lifetime of the composition.
                // That is what makes task 16.3's ordering hold: PaymentQrResolver runs when Table View
                // loads its branding, which is strictly before any sheet can be opened from it, so by
                // the time this reads the hash the cache is already reconciled. An unkeyed remember
                // would have pinned whatever value existed at first composition — on a staff device
                // that is null, and the Show QR button would stay hidden even after the download.
                val paymentQrHash = remember(order.id) { AppConfigStore(qrContext).paymentQrHash() }
                val paymentQrBitmap = remember(paymentQrHash) {
                    if (paymentQrHash == null) null else PaymentQrPipeline.loadFromInternal(qrContext)
                }
                var showPaymentQr by remember { mutableStateOf(false) }

                // Routed through OrderActions so the rule that runs here is the same one
                // OrderActionsPaymentQrTest pins, rather than a duplicate of it that can drift.
                // Bound to a local first: the rule already requires a non-null image, but a function
                // call cannot smart-cast, and this keeps the dialog's argument provably non-null
                // instead of reaching for !!.
                val qrToShow = paymentQrBitmap
                if (qrToShow != null &&
                    OrderActions.canShowPaymentQr(
                        hasPaymentPermission = permissions.canTakePayment,
                        status = order.status,
                        paymentQrHash = paymentQrHash,
                        hasStoredImage = true,
                    )
                ) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showPaymentQr = true },
                        enabled = !state.isLoading,
                    ) {
                        Text(strings.showQrButton)
                    }
                    if (showPaymentQr) {
                        PaymentQrDialog(
                            qr = qrToShow,
                            onDismiss = { showPaymentQr = false },
                        )
                    }
                }

                // ── Edit Items — the "pay for what you got" entry point ───────────────────────
                // Sits in the payment area, directly under Pay Cash / Pay QR, because that is the
                // moment it is needed: the customer is at the counter settling up and says a dish
                // never arrived. Gated on canCancel rather than a new permission — voiding a line is
                // a partial cancellation, so anyone trusted to void the whole order can drop a line
                // from it, and no café has to configure anything to get this.
                if (permissions.canCancel && state.items.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            editItemsMode = true
                            keepQuantities = emptyMap()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isLoading,
                    ) {
                        Text(strings.editItemsButton)
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

    // ── Split payment ─────────────────────────────────────────────────────────────
    //
    // The last share is deliberately NOT handled here. When a selection covers everything left the
    // planner returns SettleWholeOrder, and this hands it to `pendingPaymentMethod` — the ordinary
    // path, which shows the 10-second receipt prompt, ends the table session and returns to the
    // table grid. Closing a table in two different places would give two behaviours to keep in step.
    // Hidden rather than dismissed while a split-off share is waiting on the print-confirm
    // dialog below — the two must never stack, and the split dialog reappears (still open, still
    // showing the shrunk list) the moment the choice resolves, for the next customer in the group.
    if (showSplitDialog && pendingSplitAction == null) {
        SplitPaymentDialog(
            items = state.items,
            strings = strings,
            isLoading = state.isLoading,
            gatewayMethods = gatewayMethods,
            onPay = { plan, method ->
                // A gateway method code routes to the async checkout+poll flow instead of
                // completing immediately — same generic (Plan, String) callback either way, so
                // this dialog itself needs no gateway-specific branching (task 7.3: "nothing
                // special beyond appearing there").
                when (plan) {
                    is SplitPaymentPlanner.Plan.SettleWholeOrder -> {
                        showSplitDialog = false
                        splitMode = false
                        val gatewayMethod = PaymentMethod.fromCode(method)?.takeIf { !it.worksOffline }
                        if (gatewayMethod != null) pendingGatewayMethod = gatewayMethod
                        else pendingPaymentMethod = method
                    }
                    is SplitPaymentPlanner.Plan.SliceOff -> {
                        // Every split-off share gets the same print-confirm choice as a whole-bill
                        // payment, not just whoever settles the last one — resolved below, which
                        // is what actually dispatches to onSplitShare/onGatewaySplitCheckout/
                        // onRequestMerchantScanSplit.
                        state.order?.let { order ->
                            pendingSplitAction = PendingSplitAction(order.id, order.tableId, plan, method)
                        }
                    }
                    SplitPaymentPlanner.Plan.NothingSelected -> Unit
                }
            },
            onReduceItems = { lines ->
                state.order?.let { onVoidItems(it.id, lines, strings.splitEditItems) }
            },
            onDismiss = { showSplitDialog = false },
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

    // ── Split-share receipt-print confirm dialog ──────────────────────────────────
    val splitAction = pendingSplitAction
    if (splitAction != null) {
        ReceiptPrintConfirmDialog(
            strings = strings,
            onResolve = { shouldPrint ->
                val gatewayMethod = PaymentMethod.fromCode(splitAction.method)?.takeIf { !it.worksOffline }
                when {
                    gatewayMethod?.category == PaymentCategory.E_WALLET ->
                        onRequestMerchantScanSplit(
                            splitAction.orderId, splitAction.tableId, splitAction.plan, gatewayMethod, shouldPrint,
                        )
                    gatewayMethod != null ->
                        onGatewaySplitCheckout(
                            splitAction.orderId, splitAction.tableId, splitAction.plan, gatewayMethod, shouldPrint,
                        )
                    else ->
                        onSplitShare(
                            splitAction.orderId, splitAction.tableId, splitAction.plan, splitAction.method, shouldPrint,
                        )
                }
                pendingSplitAction = null
            },
        )
    }

    // ── Gateway checkout receipt-print confirm dialog ─────────────────────────────
    // Asked BEFORE the checkout starts rather than after, unlike Cash/QR — a gateway payment
    // confirms asynchronously (task 8.1/8.2), and interrupting that with a second dialog once the
    // acquirer answers would be a worse counter experience than asking up front.
    val gatewayMethod = pendingGatewayMethod
    if (gatewayMethod != null && state.order != null) {
        ReceiptPrintConfirmDialog(
            strings = strings,
            onResolve = { shouldPrint ->
                // Merchant-scan channels (task 8.3) need a barcode captured before there is
                // anything to initiate — the caller opens the camera; every other category goes
                // straight to the checkout overlay as before.
                if (gatewayMethod.category == PaymentCategory.E_WALLET) {
                    onRequestMerchantScan(state.order.id, state.order.tableId, gatewayMethod, state.order.total, shouldPrint)
                } else {
                    onGatewayCheckout(state.order.id, gatewayMethod, state.order.total, shouldPrint)
                }
                pendingGatewayMethod = null
            },
        )
    }

    // ── Void confirmation ─────────────────────────────────────────────────────────
    if (showVoidDialog && state.order != null) {
        VoidItemsDialog(
            strings = strings,
            // Spelled out as "taking 1 off, 1 of 2 stays", because "1× Teh Tarik" on its own is
            // ambiguous about whether one is coming off or one is being kept.
            lines = state.items
                .filter { keptQty(it) != it.quantity }
                .map { item ->
                    val name = language.localizedSnapshotName(
                        item.nameSnapshot,
                        menuItems.find { it.id == item.menuItemId },
                    )
                    val off = item.quantity - keptQty(item)
                    "-${off}× $name  (${keptQty(item)}/${item.quantity} ${strings.remainingLabel})"
                },
            newTotal = state.items.sumOf { it.unitPriceSnapshot * keptQty(it) },
            onConfirm = { reason ->
                onVoidItems(state.order.id, adjustedLines, reason)
                showVoidDialog = false
                editItemsMode = false
                keepQuantities = emptyMap()
            },
            onDismiss = { showVoidDialog = false },
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

/** A split-off share (not the final one) waiting on the print-confirm dialog before it is handed
 *  to whichever of onSplitShare/onGatewaySplitCheckout/onRequestMerchantScanSplit fits [method]. */
private data class PendingSplitAction(
    val orderId: String,
    val tableId: String?,
    val plan: SplitPaymentPlanner.Plan.SliceOff,
    val method: String,
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

/**
 * One receipt line.
 *
 * In [editable] mode the cashier is reducing the bill for food that never arrived, so the row grows a
 * `− n +` stepper where **n is the quantity that stays on the bill**. A "2× Teh Tarik" line at RM 3.00
 * each steps down to 1× / RM 3.00, then to 0 — which strikes the line through and takes it off
 * entirely. Stepping above the original quantity is not offered: more food has to be priced and sent
 * to the kitchen, which is what "Add items to order" does.
 *
 * The money shown always follows the stepper, and the per-unit rate is spelled out beneath it
 * whenever the line is more than one, so the arithmetic the customer is being asked to accept
 * ("RM 3.00 each") is on screen rather than in the cashier's head.
 */
@Composable
private fun OrderItemRow(
    item: OrderItem,
    displayName: String,
    editable: Boolean = false,
    keepQuantity: Int = item.quantity,
    onKeepQuantityChange: (Int) -> Unit = {},
) {
    val effectiveQty = if (editable) keepQuantity else item.quantity
    val removed = editable && keepQuantity == 0
    val struck = if (removed) TextDecoration.LineThrough else null
    val dimmed = if (removed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onKeepQuantityChange((keepQuantity - 1).coerceAtLeast(0)) },
                    enabled = keepQuantity > 0,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "$keepQuantity",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(24.dp),
                )
                IconButton(
                    // Capped at what was actually ordered — this control only ever reduces a bill.
                    onClick = { onKeepQuantityChange((keepQuantity + 1).coerceAtMost(item.quantity)) },
                    enabled = keepQuantity < item.quantity,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${effectiveQty}× $displayName",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textDecoration = struck,
                color = dimmed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (editable && item.quantity > 1) {
                Text(
                    text = "  RM %.2f × %d".format(item.unitPriceSnapshot, effectiveQty),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            text = "RM %.2f".format(item.unitPriceSnapshot * effectiveQty),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            textDecoration = struck,
            color = dimmed,
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

/**
 * Last stop before the bill changes. Lists exactly which lines are coming off and what the customer
 * will pay instead, so the figure is confirmed against the person standing there rather than inferred
 * from a set of checkboxes. The reason is optional — a cashier mid-rush should not be blocked by a
 * text field — and defaults to "Not served", which is the case this exists for.
 */
@Composable
private fun VoidItemsDialog(
    strings: UiStrings,
    lines: List<String>,
    newTotal: Double,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.voidItemsTitle) },
        text = {
            Column {
                lines.forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = strings.newTotalLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "RM %.2f".format(newTotal),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    placeholder = { Text(strings.voidReasonHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.ifBlank { strings.voidDefaultReason }) }) {
                Text(strings.voidItemsConfirm)
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
