package com.razstudio.pos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Full-screen overlay that blocks all touch input and shows a loading spinner.
 *
 * When [visible] is true the overlay is rendered on top of the current content,
 * consuming every pointer event so nothing underneath can be tapped.  The
 * overlay is dismissed automatically (by setting [visible] to false) — it cannot
 * be dismissed by the user while a request is in flight.
 *
 * Usage:
 * ```
 * BlockingLoadingOverlay(visible = uiState.isSubmitting)
 * ```
 */
@Composable
fun BlockingLoadingOverlay(visible: Boolean) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Full-screen countdown overlay shown while an order is held before being sent. Blocks all
 * touch on the content underneath and offers a single Cancel action to abort the send.
 *
 * @param secondsRemaining non-null shows the overlay with the remaining seconds; null hides it.
 * @param onCancel invoked when the user taps Cancel (should abort the pending send).
 * @param cancelLabel localized "Cancel" label.
 */
@Composable
fun HoldCountdownOverlay(
    secondsRemaining: Int?,
    onCancel: () -> Unit,
    cancelLabel: String = "Cancel"
) {
    if (secondsRemaining == null) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "$secondsRemaining",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Sending to kitchen…",
                    style = MaterialTheme.typography.bodyMedium
                )
                Box(modifier = Modifier.height(16.dp))
                Button(onClick = onCancel) {
                    Text(cancelLabel)
                }
            }
        }
    }
}
