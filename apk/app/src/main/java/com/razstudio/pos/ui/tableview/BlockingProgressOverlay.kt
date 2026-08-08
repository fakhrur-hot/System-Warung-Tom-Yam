package com.razstudio.pos.ui.tableview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer

/**
 * A busy state the cashier cannot miss, drawn over the whole sheet.
 *
 * ## Why this exists rather than a spinner in the layout
 *
 * The order and payment sheets used to show progress as one more item in their scrolling content: a
 * `CircularProgressIndicator` between the totals and the buttons. Two things went wrong with that,
 * both of them on the exact screen where the money moves.
 *
 * It could be **off-screen**. The receipt pane scrolls, and a long or multi-session order pushes the
 * spinner past the fold — the cashier taps Pay and the sheet looks completely unchanged.
 *
 * And it did not **block anything**. The buttons disable themselves via `enabled = !isLoading`, but a
 * disabled button is a subtle colour change on a busy screen. With no scrim, nothing said "wait" —
 * so a tap that appeared to do nothing invited a second tap, on a screen where the operations are
 * order creation and payment.
 *
 * So this covers the sheet, sits above every pane, and swallows input while it is up: it is
 * impossible to be looking at the wrong part of the screen, and impossible to tap through it. It
 * disappears exactly when the work finishes, which is the signal the cashier was missing.
 *
 * Deliberately semi-transparent — the bill stays readable underneath, so the cashier keeps the
 * context of what is being paid rather than staring at an opaque panel.
 */
@Composable
fun BlockingProgressOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    if (!visible) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // Consume every gesture, including the sheet's own drag, so nothing underneath can be
            // touched — and a second Pay tap cannot land — while the request is in flight.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                strokeWidth = 5.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            if (label != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
