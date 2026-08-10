package com.razstudio.pos.ui.tableview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.data.local.PaymentMethod
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.util.PaymentQrPipeline
import com.razstudio.pos.ui.viewmodels.SplitPaymentPlanner

/**
 * Settle one customer of a group, one at a time, until the table is clear.
 *
 * ## The shape of the job
 *
 * A cashier is standing in front of somebody who wants to pay for *their* nasi goreng and nothing
 * else. So the dialog is a list of what is still on the table with a counter per line, a running
 * total of what this person owes, and the same two payment buttons they already know. Pay, and the
 * list shrinks by exactly what was paid for. The next person steps up. Repeat until it is empty.
 *
 * The remainder is shown next to the amount throughout, because the question a cashier is actually
 * being asked across the counter is "how much is left?" — not "how much have we taken so far".
 *
 * ## Why the payment QR is on screen the whole time
 *
 * A split table is where the QR is needed most and where the old flow served it worst: each person
 * pays separately, so the code had to be produced once *per customer*, and it lived behind a button
 * on the sheet underneath this dialog. The cashier had to close the split, show the QR, dismiss it,
 * and reopen the split with their selection lost.
 *
 * So the code sits beside the tally — 40% of the dialog, on the left in landscape and on top in
 * portrait, where a customer standing across the counter can reach it with their phone while the
 * cashier keeps working on the right.
 *
 * ## Why this is a raw Dialog and not an AlertDialog
 *
 * `AlertDialog` sizes itself to its content under a platform width cap, which cannot express "70% of
 * the screen, split 40:60". `usePlatformDefaultWidth = false` hands back the geometry, and the
 * Surface below is the alert container rebuilt at the size this layout needs.
 *
 * ## Fix items only goes down
 *
 * The second mode handles food that never arrived: a line that was cooked, charged and lost on the
 * way. Its stepper reduces and clears; it cannot add. Adding here would be taking a fresh order at
 * the payment screen — after the kitchen has closed the ticket and with no slip printed — so the
 * control simply does not exist rather than existing and being refused.
 *
 * ## What this deliberately does not do
 *
 * There is no "pay the rest" shortcut. When a selection covers everything left, [SplitPaymentPlanner]
 * returns `SettleWholeOrder` and the caller pays the original order through the ordinary path — the
 * one that ends the table session and offers the receipt. A shortcut here would be a second way to
 * close a table, and the two would drift.
 */
@Composable
fun SplitPaymentDialog(
    items: List<OrderItem>,
    strings: UiStrings,
    isLoading: Boolean,
    /** Gateway channels to offer alongside Cash/QR for this share (task 7.3, A13). Empty by
     *  default — the whole row disappears with it, same rule as [OrderDetailSheet]'s. */
    gatewayMethods: List<PaymentMethod> = emptyList(),
    /** Mirrors [StaffPermissions.qrOnly] — hides "Pay Cash" for this share too, so a café-wide
     *  QR-only staff session has no cash-taking path left, split or otherwise. */
    qrOnly: Boolean = false,
    onPay: (SplitPaymentPlanner.Plan, String) -> Unit,
    onReduceItems: (List<VoidLine>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Per-line counters. Keyed by order-item id so a reload that reorders the list cannot shift a
    // customer's selection onto somebody else's food.
    //
    // Saved across configuration changes, because on a phone with auto-rotate this map IS the
    // customer's share. Losing it to a turn of the wrist means re-tapping every line with somebody
    // waiting to pay, and the cashier has no way to tell a reset selection from one they had not
    // finished making — both look like a partial tally.
    val taken = rememberSaveable(saver = TakenSaver) { mutableStateMapOf<String, Int>() }
    var fixMode by rememberSaveable { mutableStateOf(false) }

    // Reducing a line is a void: the café stops charging for food it already cooked, and there is
    // no undo. A single tap is too cheap for that, so the item waits here until it is confirmed.
    var pendingReduce by remember { mutableStateOf<OrderItem?>(null) }

    val plan = SplitPaymentPlanner.plan(items, taken)
    val amount = when (plan) {
        is SplitPaymentPlanner.Plan.SettleWholeOrder -> plan.amount
        is SplitPaymentPlanner.Plan.SliceOff -> plan.amount
        SplitPaymentPlanner.Plan.NothingSelected -> 0.0
    }
    val canPay = plan !is SplitPaymentPlanner.Plan.NothingSelected && !isLoading

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Landscape takes 70% of both axes. Portrait takes 70% of the height only and keeps the width
    // an alert already had — a phone dialog is nearly full-width regardless, so narrowing it to a
    // percentage would only cramp the item rows for no gain.
    val dialogWidth: Dp = if (isLandscape) {
        (configuration.screenWidthDp * 0.7f).dp
    } else {
        minOf(configuration.screenWidthDp - 48, 560).dp
    }
    val dialogHeight: Dp = (configuration.screenHeightDp * 0.7f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(dialogWidth)
                .height(dialogHeight),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            // Boxed for the busy overlay. This dialog had NO progress indicator at all — a share
            // payment only greyed out its buttons, so tapping Pay Cash looked identical to tapping
            // nothing while the order was created, charged and the bill shrunk. (BlockingProgressOverlay)
            Box(modifier = Modifier.fillMaxSize()) {
            if (isLandscape) {
                Row(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                        SplitQrPane(strings = strings, amount = amount)
                    }
                    VerticalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Column(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                        SplitTally(
                            items = items, strings = strings, isLoading = isLoading,
                            gatewayMethods = gatewayMethods, taken = taken,
                            fixMode = fixMode, onFixModeChange = { fixMode = it },
                            amount = amount, canPay = canPay, qrOnly = qrOnly,
                            plan = plan, onPay = onPay, onDismiss = onDismiss,
                            onRequestReduce = { pendingReduce = it },
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Box(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                        SplitQrPane(strings = strings, amount = amount)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Column(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
                        SplitTally(
                            items = items, strings = strings, isLoading = isLoading,
                            gatewayMethods = gatewayMethods, taken = taken,
                            fixMode = fixMode, onFixModeChange = { fixMode = it },
                            amount = amount, canPay = canPay, qrOnly = qrOnly,
                            plan = plan, onPay = onPay, onDismiss = onDismiss,
                            onRequestReduce = { pendingReduce = it },
                        )
                    }
                }
            }

                BlockingProgressOverlay(
                    visible = isLoading,
                    label = strings.processingLabel,
                )
            }
        }
    }

    pendingReduce?.let { item ->
        val clearsLine = item.quantity <= 1
        AlertDialog(
            onDismissRequest = { pendingReduce = null },
            title = { Text(strings.fixItemConfirmTitle) },
            text = {
                Text(
                    if (clearsLine) strings.fixItemConfirmClears.format(item.nameSnapshot)
                    else strings.fixItemConfirmBody.format(item.nameSnapshot)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val reduced = items.associate { it.id to it.quantity }
                        .toMutableMap()
                        .apply { this[item.id] = (item.quantity - 1).coerceAtLeast(0) }
                    onReduceItems(SplitPaymentPlanner.reduceTo(items, reduced))
                    // Any selection of this line is stale the moment its quantity drops; leaving it
                    // would let a customer be charged for food that was just written off.
                    taken.remove(item.id)
                    pendingReduce = null
                }) { Text(strings.fixItemConfirmAction) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReduce = null }) { Text(strings.commonCancel) }
            },
        )
    }
}

/**
 * The café's payment QR — or, flipped by the toggle, a cash tender calculator — sized to whatever
 * share of the dialog this pane was given.
 *
 * The QR is squared off against the *smaller* of the pane's two dimensions, minus the room the
 * brand label and its picker need. Sizing on width alone — which is what a plain `fillMaxWidth`
 * image does — overflows the moment the pane is wider than it is tall, which is exactly the portrait
 * case: a full-width strip only 40% of the dialog's height.
 *
 * [amount] is the CURRENT selection's total, so the calculator follows the tally live: change a
 * counter on the right and the change due on the left is already correct.
 */
@Composable
private fun SplitQrPane(strings: UiStrings, amount: Double) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("app_local_prefs", android.content.Context.MODE_PRIVATE)
    }
    var brand by remember {
        mutableStateOf(PaymentQrBrand.fromName(prefs.getString("payment_qr_brand", null)))
    }
    // Gated on the stored image alone, matching the order sheet's panel — see the note there on why
    // the config hash is deliberately not consulted.
    val qr = remember { PaymentQrPipeline.loadFromInternal(context) }

    var showTenderPad by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QrNumpadToggle(
            showNumpad = showTenderPad,
            onChange = { showTenderPad = it },
            strings = strings,
        )
        if (showTenderPad) {
            // Scrolls because the pane is a fixed 40% of the dialog: on a portrait phone that is
            // shorter than the numpad, and clipping the bottom row would hide the 0.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                CashTenderCalculator(
                    totalSen = Math.round(amount * 100),
                    strings = strings,
                )
            }
            return@Column
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (qr == null) {
                // The pane keeps its share of the dialog rather than collapsing: a layout that changes
                // shape depending on whether a café has uploaded a QR is harder to learn than one that
                // always looks the same and occasionally says it is empty.
                Text(
                    text = strings.paymentQrNone,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                val side = minOf(maxWidth, maxHeight - BRAND_CHROME_HEIGHT).coerceAtLeast(96.dp)
                PaymentQrPanel(
                    qr = qr,
                    brand = brand,
                    onBrandChange = { chosen ->
                        brand = chosen
                        prefs.edit().putString("payment_qr_brand", chosen.name).apply()
                    },
                    modifier = Modifier.width(side),
                )
            }
        }
    }
}

/** Height taken by the brand label above the code and the rail picker below it. */
private val BRAND_CHROME_HEIGHT = 76.dp

/** The right-hand (landscape) / lower (portrait) half: what is being paid for, and the pay buttons. */
@Composable
private fun ColumnScope.SplitTally(
    items: List<OrderItem>,
    strings: UiStrings,
    isLoading: Boolean,
    gatewayMethods: List<PaymentMethod>,
    taken: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>,
    fixMode: Boolean,
    onFixModeChange: (Boolean) -> Unit,
    amount: Double,
    canPay: Boolean,
    qrOnly: Boolean = false,
    plan: SplitPaymentPlanner.Plan,
    onPay: (SplitPaymentPlanner.Plan, String) -> Unit,
    onDismiss: () -> Unit,
    onRequestReduce: (OrderItem) -> Unit,
) {
    // Title and the Fix-items chip share one row: they were stacked, and together with the
    // remainder line below cost the item list three rows of a pane that is only 60% of the dialog.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.splitDialogTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = fixMode,
            onClick = { onFixModeChange(!fixMode) },
            label = { Text(strings.splitEditItems) },
        )
    }
    if (fixMode) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = strings.splitEditItemsHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))

    // The list takes the slack so the totals and the pay buttons stay pinned to the bottom of the
    // pane. A cashier reaching for Pay Cash should find it in the same place on every table,
    // regardless of how many lines the group ordered.
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
    ) {
        items.forEach { item ->
            val take = (taken[item.id] ?: 0).coerceIn(0, item.quantity)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.nameSnapshot, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "RM %.2f × %d".format(item.unitPriceSnapshot, item.quantity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (fixMode) {
                    // Decrement only. See the class note on why there is no counterpart.
                    IconButton(
                        onClick = { onRequestReduce(item) },
                        enabled = !isLoading,
                    ) { Icon(Icons.Default.Remove, contentDescription = null) }
                    Text("${item.quantity}", fontWeight = FontWeight.Bold)
                } else {
                    IconButton(
                        onClick = { taken[item.id] = (take - 1).coerceAtLeast(0) },
                        enabled = take > 0 && !isLoading,
                    ) { Icon(Icons.Default.Remove, contentDescription = null) }
                    Text(
                        text = "$take",
                        fontWeight = if (take > 0) FontWeight.Bold else FontWeight.Normal,
                    )
                    IconButton(
                        onClick = { taken[item.id] = (take + 1).coerceAtMost(item.quantity) },
                        enabled = take < item.quantity && !isLoading,
                    ) { Icon(Icons.Default.Add, contentDescription = null) }
                }
            }
            HorizontalDivider()
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    // The remainder row ("still on the table") was dropped from here: it duplicated arithmetic the
    // list already shows, and its row was worth more as item-list space on a phone.
    AmountRow(strings.splitThisCustomerPays, amount, emphasised = true)

    if (gatewayMethods.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        GatewayMethodRow(
            methods = gatewayMethods,
            strings = strings,
            enabled = canPay,
            onSelect = { method -> onPay(plan, method.code) },
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss, enabled = !isLoading) { Text(strings.commonCancel) }
        Spacer(modifier = Modifier.weight(1f))
        if (!qrOnly) {
            Button(onClick = { onPay(plan, "CASH") }, enabled = canPay) { Text(strings.payCash) }
        }
        Button(onClick = { onPay(plan, "QR") }, enabled = canPay) { Text(strings.payQR) }
    }
}

@Composable
private fun AmountRow(label: String, amount: Double, emphasised: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasised) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "RM %.2f".format(amount),
            style = if (emphasised) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * Persists the per-line share selection across configuration changes.
 *
 * Flattened to an alternating id/count list rather than saved as a Map, because the Bundle-backed
 * saver only guarantees the primitive and parcelable types, and a list of Strings and Ints is the
 * simplest shape that is certainly one of them.
 */
private val TakenSaver: Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>, Any> =
    Saver(
        save = { map -> ArrayList<Any>(map.flatMap { listOf(it.key, it.value) }) },
        restore = { saved ->
            @Suppress("UNCHECKED_CAST")
            val flat = saved as List<Any>
            mutableStateMapOf<String, Int>().apply {
                flat.chunked(2).forEach { pair ->
                    val key = pair.getOrNull(0) as? String
                    val value = pair.getOrNull(1) as? Int
                    if (key != null && value != null) put(key, value)
                }
            }
        },
    )
