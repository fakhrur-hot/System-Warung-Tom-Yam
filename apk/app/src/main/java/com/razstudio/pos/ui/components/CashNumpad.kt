package com.razstudio.pos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Cash-register money entry: digits push in from the right, sen first.
 *
 * "RM 123.45" is keyed as 1-2-3-4-5 — there is no decimal-point key at all, because on a till the
 * decimal is not information: prices are always exact sen and the point is always two from the
 * right. This is how every physical cash register since the mechanical era has taken amounts, so
 * a cashier's muscle memory transfers as-is.
 *
 * The amount lives as nullable sen: **null means "nothing keyed yet"**, which callers render as a
 * greyed-out default (the exact bill total on the tender pad, RM 0.00 on the float editor). The
 * first digit replaces the default rather than appending to it — matching "assume the customer
 * pays exact unless the cashier says otherwise".
 */
const val CASH_ENTRY_MAX_SEN = 99_999_999L // RM 999,999.99 — beyond any till, guards overflow

fun cashEntryAppend(current: Long?, digit: Int): Long {
    val next = (current ?: 0L) * 10 + digit
    return if (next > CASH_ENTRY_MAX_SEN) (current ?: 0L) else next
}

/** Backspace: drop the rightmost digit; empty entry returns to null (the greyed default). */
fun cashEntryBackspace(current: Long?): Long? {
    val next = (current ?: return null) / 10
    return if (next == 0L) null else next
}

fun formatRm(sen: Long): String = "RM %,.2f".format(sen / 100.0)

/**
 * The 4×3 digit pad. `C` clears back to the greyed default, `⌫` drops one digit.
 * Pure input surface — the amount display belongs to the caller, beside its own context
 * (bill total, change due, current balance).
 */
@Composable
fun CashNumpad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "⌫"),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    OutlinedButton(
                        onClick = {
                            when (key) {
                                "C" -> onClear()
                                "⌫" -> onBackspace()
                                else -> onDigit(key.toInt())
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        Text(key, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}
