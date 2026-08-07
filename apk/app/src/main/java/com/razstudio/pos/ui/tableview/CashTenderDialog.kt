package com.razstudio.pos.ui.tableview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.local.PaymentTransaction
import com.razstudio.pos.ui.components.CashNumpad
import com.razstudio.pos.ui.components.cashEntryAppend
import com.razstudio.pos.ui.components.cashEntryBackspace
import com.razstudio.pos.ui.components.formatRm
import com.razstudio.pos.ui.i18n.UiStrings

/**
 * The cash-tender pad shown when Pay Cash is pressed.
 *
 * The tendered amount starts as the bill total in grey — "assume exact payment" — so the fast
 * path is one tap on Confirm. The first digit keyed replaces the assumption entirely; digits push
 * in from the right, sen first (RM 123.45 is 1-2-3-4-5, no decimal key — see CashNumpad). Change
 * is computed live, and Confirm stays disabled while the tendered amount is short of the bill.
 */
@Composable
internal fun CashTenderDialog(
    totalRinggit: Double,
    strings: UiStrings,
    onConfirm: (tenderedSen: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val totalSen = PaymentTransaction.fromRinggit(totalRinggit)

    // null = nothing keyed → exact payment assumed.
    var tendered by remember { mutableStateOf<Long?>(null) }
    val effectiveSen = tendered ?: totalSen
    val changeSen = effectiveSen - totalSen

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.payCash) },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        strings.grandTotal,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatRm(totalSen),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = formatRm(effectiveSen),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (tendered == null)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(8.dp))
                CashNumpad(
                    onDigit = { tendered = cashEntryAppend(tendered, it) },
                    onBackspace = { tendered = cashEntryBackspace(tendered) },
                    onClear = { tendered = null },
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Change",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (changeSen >= 0) formatRm(changeSen) else "Insufficient",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (changeSen < 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = changeSen >= 0,
                onClick = { onConfirm(effectiveSen) },
            ) { Text(strings.payCash) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.commonClose) } },
    )
}
