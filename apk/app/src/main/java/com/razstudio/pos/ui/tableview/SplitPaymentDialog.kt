package com.razstudio.pos.ui.tableview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.ui.i18n.UiStrings
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
    onPay: (SplitPaymentPlanner.Plan, String) -> Unit,
    onReduceItems: (List<VoidLine>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Per-line counters. Keyed by order-item id so a reload that reorders the list cannot shift a
    // customer's selection onto somebody else's food.
    val taken = remember { mutableStateMapOf<String, Int>() }
    var fixMode by remember { mutableStateOf(false) }

    val plan = SplitPaymentPlanner.plan(items, taken)
    val amount = when (plan) {
        is SplitPaymentPlanner.Plan.SettleWholeOrder -> plan.amount
        is SplitPaymentPlanner.Plan.SliceOff -> plan.amount
        SplitPaymentPlanner.Plan.NothingSelected -> 0.0
    }
    val remainder = SplitPaymentPlanner.remainderAfter(items, taken)
    val canPay = plan !is SplitPaymentPlanner.Plan.NothingSelected && !isLoading

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.splitDialogTitle) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                FilterChip(
                    selected = fixMode,
                    onClick = { fixMode = !fixMode },
                    label = { Text(strings.splitEditItems) },
                )
                if (fixMode) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.splitEditItemsHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

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
                                onClick = {
                                    val reduced = items.associate { it.id to it.quantity }
                                        .toMutableMap()
                                        .apply { this[item.id] = (item.quantity - 1).coerceAtLeast(0) }
                                    onReduceItems(SplitPaymentPlanner.reduceTo(items, reduced))
                                },
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

                Spacer(modifier = Modifier.height(12.dp))
                AmountRow(strings.splitThisCustomerPays, amount, emphasised = true)
                AmountRow(strings.splitRemaining, remainder, emphasised = false)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPay(plan, "CASH") }, enabled = canPay) { Text(strings.payCash) }
                Button(onClick = { onPay(plan, "QR") }, enabled = canPay) { Text(strings.payQR) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text(strings.commonCancel) }
        },
    )
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
