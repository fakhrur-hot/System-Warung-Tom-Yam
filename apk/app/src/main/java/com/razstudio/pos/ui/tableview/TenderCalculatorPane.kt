package com.razstudio.pos.ui.tableview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.razstudio.pos.ui.components.CashNumpad
import com.razstudio.pos.ui.components.cashEntryAppend
import com.razstudio.pos.ui.components.cashEntryBackspace
import com.razstudio.pos.ui.components.formatRm
import com.razstudio.pos.ui.i18n.UiStrings

/**
 * The QR ⇄ numpad switch shown above the payment pane.
 *
 * The QR panel serves the customer paying by wallet; a cash customer needs the opposite tool in
 * the same spot — somewhere to key what was handed over and read the change off. One pane, two
 * modes, because the two never happen at once for the same payer.
 */
@Composable
fun QrNumpadToggle(
    showNumpad: Boolean,
    onChange: (Boolean) -> Unit,
    strings: UiStrings,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = !showNumpad,
            onClick = { onChange(false) },
            label = { Text("QR") },
        )
        FilterChip(
            selected = showNumpad,
            onClick = { onChange(true) },
            label = { Text(strings.numpadLabel) },
        )
    }
}

/**
 * A change calculator, nothing more: key what the customer handed over, read the change off.
 *
 * Deliberately **not** wired into the payment flow. The whole-bill cash path already has its own
 * tender pad (`CashTenderDialog`) that records tendered/change into the drawer ledger; this pane
 * exists for the counters that settle from this screen directly — and for split shares, which have
 * no tender pad at all — where the cashier was doing the subtraction in their head. It shows the
 * arithmetic where the money changes hands; the Pay buttons behave exactly as before.
 *
 * Entry state is plain `remember`: rotation no longer recreates the activity (configChanges), and
 * a keyed amount from one customer must not survive into the next one's payment anyway — the pane
 * resets when the sheet closes, which is the natural end of a tender.
 */
@Composable
fun CashTenderCalculator(
    totalSen: Long,
    strings: UiStrings,
    modifier: Modifier = Modifier,
) {
    var tenderedSen by remember { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        TenderRow(strings.grandTotal, formatRm(totalSen), emphasised = true)

        // Grey until something is keyed — the exact convention CashTenderDialog and the float
        // editor already use: grey means "showing you a default, not something you entered".
        TenderRow(
            label = strings.cashReceivedLabel,
            value = formatRm(tenderedSen ?: totalSen),
            dimmed = tenderedSen == null,
        )

        val changeSen = (tenderedSen ?: totalSen) - totalSen
        TenderRow(
            label = strings.changeDueLabel,
            value = formatRm(changeSen),
            emphasised = true,
            // A negative isn't change, it's a shortfall — painting it the error colour is what
            // stops a cashier handing back "change" on money that doesn't cover the bill.
            isError = changeSen < 0,
        )

        Spacer(Modifier.height(8.dp))
        CashNumpad(
            onDigit = { d -> tenderedSen = cashEntryAppend(tenderedSen, d) },
            onBackspace = { tenderedSen = cashEntryBackspace(tenderedSen) },
            onClear = { tenderedSen = null },
        )
    }
}

@Composable
private fun TenderRow(
    label: String,
    value: String,
    dimmed: Boolean = false,
    emphasised: Boolean = false,
    isError: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = if (emphasised) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isError -> MaterialTheme.colorScheme.error
                dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
