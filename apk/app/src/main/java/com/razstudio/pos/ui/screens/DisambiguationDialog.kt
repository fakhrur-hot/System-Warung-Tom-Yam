package com.razstudio.pos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.local.Order

/**
 * Disambiguation dialog shown when an admin taps an AMBIGUOUS payment in the history list.
 * Displays candidate orders with table numbers and timestamps, allowing the admin to
 * manually resolve the ambiguous match by selecting the correct order.
 *
 * @param capturedPaymentId The ID of the ambiguous CapturedPayment record.
 * @param amountSen The payment amount in sen (integer) for display.
 * @param candidates The list of candidate orders that match the payment amount.
 * @param onOrderSelected Called when the admin selects an order; triggers resolveAmbiguousPayment().
 * @param onDismiss Called when the dialog is dismissed without selection.
 */
@Composable
fun DisambiguationDialog(
    capturedPaymentId: String,
    amountSen: Long,
    candidates: List<Order>,
    onOrderSelected: (capturedPaymentId: String, orderId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Order") },
        text = {
            Column {
                Text(
                    "Multiple orders match RM %.2f".format(amountSen / 100.0),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                candidates.forEach { order ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onOrderSelected(capturedPaymentId, order.id) },
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(order.tableId ?: "Order #${order.orderNumber ?: "—"}")
                            },
                            supportingContent = {
                                Text(order.createdAt.take(16).replace("T", " "))
                            },
                            trailingContent = {
                                Text("RM %.2f".format(order.total))
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},  // No confirm — user picks an order directly
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
