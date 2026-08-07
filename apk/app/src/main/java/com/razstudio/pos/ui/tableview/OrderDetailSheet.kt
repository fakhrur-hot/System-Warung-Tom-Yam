@file:OptIn(ExperimentalMaterial3Api::class)

package com.razstudio.pos.ui.tableview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.res.Configuration
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.razstudio.pos.data.customChargeMenuItem
import com.razstudio.pos.data.isCustomCharge
import com.razstudio.pos.data.toNewOrderItem
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.data.local.OrderActions
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.data.local.OrderStatus
import com.razstudio.pos.data.local.PaymentCategory
import com.razstudio.pos.data.local.PaymentMethod
import com.razstudio.pos.data.local.PaymentTransaction
import com.razstudio.pos.ui.i18n.AppLanguage
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.theme.scrollPanel
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
    /**
     * A cash payment was confirmed on the tender pad: the customer handed over [tenderedSen] for
     * a bill of [totalSen]. Fired so the caller can append the movement to the cash-drawer ledger
     * (and kick the drawer open for the change) — this sheet stays ViewModel-free, so the ledger
     * write is the caller's job, wired the same way every other side effect here is. No-op by
     * default: callers without a drawer (customer-facing surfaces) simply don't record.
     */
    onCashTendered: (orderId: String, totalSen: Long, tenderedSen: Long) -> Unit = { _, _, _ -> },
    onCancel: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // ── Dismissing this sheet takes a deliberate act ─────────────────────────────────
    //
    // A swipe anywhere on the sheet used to close it. On a bill that is 85% of the screen and full
    // of scrollable lists and steppers, that is one careless downward flick away from losing a
    // half-built round mid-service — and the cashier then has to find the table and start again.
    //
    // So `confirmValueChange` refuses Hidden unless [handleDismissArmed] was set, and the ONLY thing
    // that sets it is a downward drag on the handle. Material3 1.3.1 has no `sheetGesturesEnabled`
    // to switch this off wholesale (it arrives in 1.5), and gating the state change is better than
    // that flag would be anyway: the scrim tap keeps working, since it calls `onDismissRequest`
    // directly rather than driving the sheet through Hidden.
    var handleDismissArmed by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || handleDismissArmed },
    )
    val sheetScope = rememberCoroutineScope()
    var showCancelDialog by remember { mutableStateOf(false) }
    var showAddItemPicker by remember(state.order?.id) { mutableStateOf(false) }
    // The hand-typed "+ Customized" charge fields. Keyed on the order like the picker so a
    // half-typed charge cannot follow the cashier onto a different table's bill.
    var showCustomCharge by remember(state.order?.id) { mutableStateOf(false) }
    var stagedCart by remember(state.order?.id) { mutableStateOf(listOf<StagedCartLine>()) }
    var pendingPaymentMethod by remember { mutableStateOf<String?>(null) }
    var pendingGatewayMethod by remember { mutableStateOf<PaymentMethod?>(null) }
    // Pay Cash now routes through the tender pad first; this reveals it.
    var showCashTender by remember { mutableStateOf(false) }
    // Split state lives here rather than in the ViewModel: it is a cashier's working scratchpad for
    // one customer standing at the counter, and it must not survive the sheet closing.
    var splitMode by remember { mutableStateOf(false) }
    // rememberSaveable, not remember: with auto-rotate on, turning the phone while a customer's
    // share is being tallied recreates the activity, and a plain remember drops the cashier back
    // to the sheet mid-transaction with the person still standing there.
    var showSplitDialog by rememberSaveable { mutableStateOf(false) }

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

    // ── How wide the sheet is allowed to get ──────────────────────────────────────
    //
    // Material3 caps a ModalBottomSheet at 640.dp by default. On the D3 Mini's 1280x800 landscape
    // that is almost exactly HALF the screen: the receipt and the actions were squeezed into the
    // middle while a quarter of the display sat empty on either side. The two-pane landscape
    // layout below only pays off if it is given the room.
    //
    // 90% rather than the whole width so the scrim still reads as a modal — an edge-to-edge sheet
    // looks like a screen you navigated to, and the cashier loses the "tap outside to dismiss"
    // affordance that the visible strip advertises.
    //
    // Portrait is untouched: `Dp.Unspecified` restores Material's own default, which already fills
    // a phone's width and does not want a percentage applied to it.
    val configuration = LocalConfiguration.current
    val isLandscapeLayout = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val sheetMaxWidth = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        (configuration.screenWidthDp * 0.9f).dp
    } else {
        androidx.compose.ui.unit.Dp.Unspecified
    }

    ModalBottomSheet(
        // `confirmValueChange` above is not enough on its own: Material3 1.3.1 does not consult it
        // for swipe-to-dismiss, so a drag on the body settled the sheet to Hidden and fired this
        // callback anyway (measured on device — the body swipe closed the bill).
        //
        // So the decision is made here, by cause:
        //  - dragged away (target is Hidden) and NOT armed by the handle → refuse, and re-show so
        //    the sheet springs back instead of vanishing.
        //  - anything else — scrim tap, back press, the handle's own armed hide → dismiss normally.
        //    Those keep working; the point is to stop an accidental flick, not to trap the cashier.
        onDismissRequest = {
            val draggedAway = sheetState.targetValue == SheetValue.Hidden
            if (handleDismissArmed || !draggedAway) {
                onDismiss()
            } else {
                sheetScope.launch { sheetState.show() }
            }
        },
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        dragHandle = {
            SheetGrabHandle(
                onDragDownToDismiss = {
                    // Arm first, then hide: the gate above refuses Hidden until this is set.
                    handleDismissArmed = true
                    sheetScope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                },
            )
        },
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
        // ── How tall the sheet is, and how much of it the bill gets ──────────────────
        //
        // The sheet is pinned to [SHEET_HEIGHT_FRACTION] of the screen rather than being left to
        // hug its content. Two reasons: a bill that grows a session no longer makes the whole sheet
        // jump to a new height under the cashier's hand, and the bill panel gets a predictable share
        // of a known total instead of a fixed 260.dp that was generous on a phone and tiny on the
        // D3 Mini's 800dp-tall landscape.
        //
        // The bill panel takes the slack via a proportional cap, NOT `weight(1f)`. Both layout
        // branches wrap their content in `verticalScroll`, which measures children with unbounded
        // height — so a weight there resolves to zero and the list would vanish. Splitting the
        // receipt pane into above-list / list / below-list to get a true fill is a bigger change
        // than this one, and the proportion below lands within a few dp of the same result.
        val screenHeightDp = configuration.screenHeightDp
        val sheetHeight = (screenHeightDp * SHEET_HEIGHT_FRACTION).dp

        // ── Payment QR, resolved once for the whole sheet ────────────────────────────
        //
        // Hoisted out of the payment section because the button moved to the Items row — but the
        // rule for whether it may be shown is unchanged, and still routed through OrderActions so
        // what runs here is the same rule OrderActionsPaymentQrTest pins rather than a copy of it.
        //
        // Keyed on the order id so the hash is re-read each time the sheet opens for a different
        // order. An unkeyed remember would pin whatever existed at first composition — on a staff
        // device that is null, and the QR chip would stay hidden even after the image downloaded.
        val qrContext = LocalContext.current
        val paymentQrHash = remember(order.id) { AppConfigStore(qrContext).paymentQrHash() }
        val paymentQrBitmap = remember(paymentQrHash) {
            if (paymentQrHash == null) null else PaymentQrPipeline.loadFromInternal(qrContext)
        }
        var showPaymentQr by remember { mutableStateOf(false) }
        // Absent rather than shown-and-disabled: a control that dies in front of a waiting customer
        // is worse than no control.
        val canShowPaymentQr = paymentQrBitmap != null &&
            OrderActions.canShowPaymentQr(
                hasPaymentPermission = permissions.canTakePayment,
                status = order.status,
                paymentQrHash = paymentQrHash,
                hasStoredImage = true,
            )
        if (showPaymentQr && paymentQrBitmap != null) {
            PaymentQrDialog(qr = paymentQrBitmap, onDismiss = { showPaymentQr = false })
        }

        val receiptPane: @Composable ColumnScope.() -> Unit = {
            // ── Header: table and status on ONE line ──────────────────────────────
            // The status used to sit on its own line below, which cost a whole row of a sheet that
            // is short on vertical space on a phone. It is two words; it belongs beside the table.
            //
            // The internal order UUID is deliberately absent — a database id means nothing to staff
            // and only clutters the header.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${strings.tableWord}: $tableLabel",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = order.status.readableLabel(strings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // QR lives here rather than under the payment buttons: presenting the code is
                    // something a cashier does WHILE reading the bill, not after choosing how to
                    // settle, and buried under Pay Cash / Pay QR it was three scrolls away. Same chip
                    // as Customized so the row reads as one strip.
                    if (canShowPaymentQr) {
                        SheetActionChip(label = strings.showQrButton) { showPaymentQr = true }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (canAddItems) {
                        CustomChargeButton(strings = strings) {
                            showCustomCharge = !showCustomCharge
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        AddItemCircleButton(
                            contentDescription = strings.addItemCd,
                            // Collapsing no longer discards the staged round: the staged list now
                            // renders below regardless of this toggle (a typed charge needs it), so
                            // clearing here would throw away lines still on screen. Individual
                            // lines have their own delete button.
                            onClick = { showAddItemPicker = !showAddItemPicker },
                        )
                    }
                }
            }
            // Breathing room so the chips do not sit flush against the white scroll panel below —
            // touching, they read as one control attached to the list.
            Spacer(modifier = Modifier.height(8.dp))

            // A hand-typed charge stages into the SAME stagedCart as picker items, so a round of
            // "one more Teh Tarik plus a RM 5.00 corkage" is one order round, one kitchen print,
            // and one confirm button — see the staged-cart block below.
            AnimatedVisibility(visible = showCustomCharge && canAddItems) {
                CustomChargeForm(
                    strings = strings,
                    onAdd = { name, price ->
                        stagedCart = stagedCart + StagedCartLine(
                            menuItem = customChargeMenuItem(name, price),
                            quantity = 1,
                            unitPrice = price,
                        )
                    },
                )
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
                }
            }

            // The staged round, OUTSIDE the picker's visibility: a custom charge can be typed with
            // the menu picker shut, and a staged line the cashier cannot see or send is a line that
            // silently vanishes when they walk away. Shown whenever anything is staged, from either
            // source, and confirmed by one button.
            if (stagedCart.isNotEmpty() && canAddItems) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
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
                                // A hand-typed charge shows its price here — it is the only place
                                // the cashier can check what they typed before sending it.
                                if (line.menuItem.isCustomCharge) {
                                    Text(
                                        text = "RM %.2f".format((line.unitPrice ?: line.menuItem.price) * line.quantity),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
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
                                stagedCart.map {
                                    it.menuItem.toNewOrderItem(it.quantity, it.note, it.size, it.unitPrice)
                                },
                            )
                            stagedCart = emptyList()
                            showAddItemPicker = false
                            showCustomCharge = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${strings.addItemsToOrderButton} (${stagedCart.sumOf { it.quantity }})")
                    }
                }
            }

            // ── Items grouped by session (order-placement round), then by category ──
            val sessionGroups = state.items.groupBy { it.sessionNumber }.toSortedMap()

            // This list is a 260.dp scroll box, so a newly-added round lands BELOW the fold with the
            // divider and "Subtotal" sitting right under it — which reads as "the item I just added
            // isn't there", when it is, one scroll down. Jumping to the end whenever the line count
            // grows puts the round the cashier just created where they are already looking.
            val itemsListState = rememberLazyListState()
            LaunchedEffect(state.items.size) {
                val last = itemsListState.layoutInfo.totalItemsCount - 1
                if (last > 0) itemsListState.animateScrollToItem(last)
            }

            // The scroll area keeps the LIGHT ground while the sheet around it is a step darker
            // (see ThemePreset's surfaceContainer mapping). This is the receipt — the one region a
            // cashier reads line by line with a customer waiting — so it gets the highest contrast
            // on the sheet, and its edges make the scrollable region visibly bounded instead of
            // blending into the actions below it.
            Surface(
                color = MaterialTheme.colorScheme.scrollPanel,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    // Takes ALL the leftover height in the sheet. With a fixed 85% sheet and a
                    // one-item bill, a capped panel left a band of dead space under Cancel Order and
                    // pushed every action up into the middle of the screen. Weighted, the panel grows
                    // to fill whatever the fixed content does not use, so the totals and the payment
                    // buttons always sit at the bottom of the sheet no matter how short the order is —
                    // and the cashier's thumb finds them in the same place every time.
                    .weight(1f),
            ) {
            LazyColumn(
                state = itemsListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
            ) {
                sessionGroups.forEach { (sessionNumber, sessionItems) ->
                    // Partition into confirmed (sentToKitchen=true) and pending (sentToKitchen=false)
                    val (confirmedItems, pendingItems) = sessionItems.partition { it.sentToKitchen }

                    // ── Confirmed items — render as before ──────────────────────────────
                    if (confirmedItems.isNotEmpty()) {
                        item {
                            // Session number and its reprint action on ONE row. The button used to be
                            // a full-width bar under the round's items, which cost a whole row per
                            // session inside a 260dp scroll box — with four sessions, half the
                            // scrollable height was buttons. Sitting beside the heading it also no
                            // longer needs to name the session: the row it is on says which.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${strings.orderSessionLabel} $sessionNumber",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (permissions.canSendToKitchen) {
                                    TextButton(
                                        onClick = { onReprintSession(order.id, sessionNumber) },
                                        enabled = !state.isLoading,
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = strings.reprintToKitchenButton,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
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
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))

            // ── Grand Total only ──────────────────────────────────────────────────
            // No subtotal line. This stall sells food and drink at the price on the menu — there is
            // no service charge, no tax, nothing between the lines and the total. A subtotal that
            // always equals the grand total is a row of noise that makes a cashier check whether
            // they differ, every single time.
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

                // ── The café's payment QR, shown rather than hidden behind a button ──────────
                //
                // Landscape leaves a large dead area above the pay controls, and a code the
                // CUSTOMER has to scan is a poor fit for a dialog the cashier must open first.
                // Portrait keeps the "Show QR" button below, where there is no room for this.
                if (isLandscapeLayout) {
                    val panelContext = LocalContext.current
                    val panelPrefs = remember(panelContext) {
                        panelContext.getSharedPreferences("app_local_prefs", android.content.Context.MODE_PRIVATE)
                    }
                    var qrBrand by remember {
                        mutableStateOf(PaymentQrBrand.fromName(panelPrefs.getString("payment_qr_brand", null)))
                    }
                    // Gated on the stored image alone, deliberately — unlike the "Show QR" button,
                    // which also checks the config hash. The two can disagree (see OrderActions),
                    // and for a panel that only draws what it has, a hash mismatch hiding a
                    // perfectly good code is a worse failure than showing a stale one.
                    val panelQr = remember(order.id) { PaymentQrPipeline.loadFromInternal(panelContext) }
                    panelQr?.let { bitmap ->
                        // Capped against the screen's HEIGHT, not the pane's width. A landscape
                        // phone is only ~400dp tall, and a code sized to fill half its width would
                        // be taller than the sheet — pushing Pay Cash, Pay QR and the split radio
                        // below the fold, so the pane meant to speed the cashier up hid the only
                        // controls they need. The D3's landscape canvas is twice as tall and is
                        // unaffected by the cap.
                        PaymentQrPanel(
                            qr = bitmap,
                            brand = qrBrand,
                            onBrandChange = { chosen ->
                                qrBrand = chosen
                                panelPrefs.edit().putString("payment_qr_brand", chosen.name).apply()
                            },
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .widthIn(max = (configuration.screenHeightDp * 0.45f).dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
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
                // Both choices on ONE row. They are two halves of a single question — "one bill or
                // several?" — and stacking them put the payment buttons between the options, so the
                // second choice was below the actions belonging to the first.
                if (allowSplitPayment) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .selectable(
                                    selected = !splitMode,
                                    onClick = { splitMode = false },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = !splitMode, onClick = { splitMode = false })
                            Text(
                                text = strings.payWholeBillOption,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .selectable(
                                    selected = splitMode,
                                    onClick = { splitMode = true },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = splitMode, onClick = { splitMode = true })
                            Text(
                                text = strings.splitPaymentOption,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                            )
                        }
                    }
                }

                if (!splitMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            // Through the tender pad, not straight to payment: the pad computes
                            // the change and feeds the cash-drawer ledger before the existing
                            // receipt-confirm flow continues unchanged.
                            onClick = { showCashTender = true },
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

                if (splitMode && allowSplitPayment) {
                    Button(
                        onClick = { showSplitDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isLoading && state.items.isNotEmpty(),
                    ) {
                        Text(strings.splitPaymentButton)
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
                    .height(sheetHeight)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // Bounded, not scrolling, for the same reason as portrait — the bill panel inside
                // needs a real height to weight against. The ACTIONS column keeps its own scroll.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                        // Even 50:50 rather than a fixed 300.dp column. The receipt only needs as
                        // much width as its longest item line, and on a 1280px landscape screen the
                        // old split gave it ~836px against the actions' 300 — the pane the cashier
                        // actually touches was the cramped one, and the payment QR below had
                        // nowhere to go.
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 16.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) { actionsPane() }
                }
            }
        } else {
            // No verticalScroll here any more, deliberately: a scrolling column is measured with
            // unbounded height, which makes the bill panel's `weight(1f)` resolve to zero. The sheet
            // is a known 85% instead, the panel absorbs the slack, and the panel's own LazyColumn is
            // what scrolls when an order outgrows the space.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 16.dp),
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
    // No hide-and-reappear any more: with the per-share prompt gone there is no second dialog to
    // avoid stacking with, so this stays open across shares and simply shows the shrunk list for the
    // next customer. It closes the instant the bill is cleared (see the SettleWholeOrder branch).
    if (showSplitDialog) {
        SplitPaymentDialog(
            items = state.items,
            strings = strings,
            isLoading = state.isLoading,
            gatewayMethods = gatewayMethods,
            onPay = { plan, method ->
                // A gateway method code routes to the async checkout+poll flow instead of
                // completing immediately — same generic (Plan, String) callback either way.
                when (plan) {
                    is SplitPaymentPlanner.Plan.SettleWholeOrder -> {
                        showSplitDialog = false
                        splitMode = false
                        val gatewayMethod = PaymentMethod.fromCode(method)?.takeIf { !it.worksOffline }
                        if (gatewayMethod != null) pendingGatewayMethod = gatewayMethod
                        else pendingPaymentMethod = method
                    }
                    is SplitPaymentPlanner.Plan.SliceOff -> {
                        // Charged immediately: no prompt, no receipt for an individual share.
                        //
                        // This used to raise a print-confirm per share, so a group of four answered
                        // four dialogs. The receipt question is now asked ONCE, when the bill is
                        // finally cleared — the last payer always resolves to SettleWholeOrder (see
                        // SplitPaymentPlanner: `takesEverything`), which routes into the ordinary
                        // whole-bill path and its single 10s prompt.
                        //
                        // The cost, stated because it is a real loss: an individual payer can no
                        // longer walk away with a receipt for their own share. Anyone who needs one
                        // for expenses has to be handled another way.
                        state.order?.let { order ->
                            val gateway = PaymentMethod.fromCode(method)?.takeIf { !it.worksOffline }
                            when {
                                gateway?.category == PaymentCategory.E_WALLET ->
                                    onRequestMerchantScanSplit(
                                        order.id, order.tableId, plan, gateway, false,
                                    )
                                gateway != null ->
                                    onGatewaySplitCheckout(
                                        order.id, order.tableId, plan, gateway, false,
                                    )
                                else ->
                                    onSplitShare(order.id, order.tableId, plan, method, false)
                            }
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

    // ── Cash tender pad ───────────────────────────────────────────────────────────
    // Sits BEFORE the receipt-print confirm in the flow: tender → (ledger records, drawer opens
    // for the change) → the same ReceiptPrintConfirmDialog and onPayment path as ever.
    if (showCashTender && state.order != null) {
        CashTenderDialog(
            totalRinggit = state.order.total,
            strings = strings,
            onConfirm = { tenderedSen ->
                onCashTendered(
                    state.order.id,
                    PaymentTransaction.fromRinggit(state.order.total),
                    tenderedSen,
                )
                showCashTender = false
                pendingPaymentMethod = "CASH"
            },
            onDismiss = { showCashTender = false },
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

/**
 * How much of the screen height the order sheet occupies.
 *
 * Fixed rather than content-hugging so the sheet does not resize as sessions are added, and so the
 * bill panel can be given a share of a known total. 85% leaves the scrim visible at the top, which
 * is what still advertises "tap outside to dismiss" — an edge-to-edge sheet reads as a screen you
 * navigated to, and the cashier loses that affordance.
 */
private const val SHEET_HEIGHT_FRACTION = 0.85f

private data class StagedCartLine(
    val menuItem: MenuItem,
    val quantity: Int,
    val note: String? = null,
    val size: String? = null,
    val unitPrice: Double? = null,
)


/**
 * The category caption above a group of receipt lines, from the line's frozen `categorySnapshot`.
 *
 * Two things this gets right that the previous version did not.
 *
 * A hand-typed charge has NO menu category — its snapshot is empty — and lumping it under "Others"
 * claimed it belonged to a menu bucket it was never in. Blank now reads as "Customized", matching
 * the button that created it.
 *
 * And a café's own preset categories ("SAYUR", "MINUMAN (AIS)") are shown verbatim rather than
 * collapsed into "Others". The old `else` branch swallowed every custom category the menu defines,
 * so a stall with its own categories saw its whole receipt filed under one meaningless heading.
 */
private fun categoryLabel(category: String, strings: UiStrings): String = when {
    category.isBlank() -> strings.customChargeButton
    else -> when (category.uppercase()) {
        "FOOD" -> strings.catFood
        "BEVERAGES" -> strings.catBeverages
        "SIDE_DISHES", "SIDE DISHES" -> strings.catSideDishes
        "OTHERS" -> strings.catOthers
        else -> category
    }
}

/**
 * Status as words a person reads, not an enum name.
 *
 * `SENT_TO_KITCHEN` was being printed raw in the header — screaming caps with underscores, which is
 * a database value leaking into a cashier's face. The four live states reuse the same short labels
 * the table grid already uses, so one order speaks the same vocabulary everywhere; the terminal and
 * unknown states fall back to a de-underscored, sentence-cased name rather than needing strings that
 * would almost never be seen.
 */
private fun OrderStatus.readableLabel(strings: UiStrings): String = when (this) {
    OrderStatus.RECEIVED -> strings.statusNew
    OrderStatus.SENT_TO_KITCHEN -> strings.statusKitchen
    OrderStatus.PREPARING -> strings.statusPreparing
    OrderStatus.READY -> strings.statusReady
    else -> name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

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
