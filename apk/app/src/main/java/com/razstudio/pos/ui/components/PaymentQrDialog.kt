package com.razstudio.pos.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.razstudio.pos.R

/**
 * Task 17.1 — the café's static Payment QR, shown full-size for a customer to scan
 * (Requirements 13.4, 13.5, 13.6, 13.7, 13.8).
 *
 * ### What this code must NOT do, and why
 *
 * **No order total, table, or amount alongside the code** (Requirement 13.8). The QR is a *static payee
 * identifier*: it encodes no amount and no order reference. The customer's own banking app shows them
 * the payee and they key the sum in themselves. Anything rendered next to the code risks being read as
 * part of the payment instruction, and there is no audit trail to catch the mistake afterwards.
 *
 * **No brightness override.** An earlier draft raised `screenBrightness` to maximum; that was
 * deliberately dropped. A POS terminal's brightness is set by the operator, yanking it to full is
 * startling in a dim café, and once an app changes it, it owns restoring it correctly across rotation,
 * backgrounding, and process death. Keeping the screen *awake* is the part that actually prevents a
 * failed scan (Requirement 13.6) — how bright it is stays the device's own business.
 *
 * **No timer, no animation, no transform** (Requirement 13.5). A customer may take a while to open
 * their banking app; a modal that fades, moves, or dismisses itself is a modal that ruins a scan
 * mid-way. Dismissal is an explicit action only — including [DialogProperties] that refuse
 * back-press and outside-tap, so a stray touch while handing the phone over cannot close it.
 *
 * The caller is responsible for only showing this when a Payment QR is actually configured; see the
 * Show QR button's `paymentQrHash != null` guard (task 17.3).
 */
@Composable
fun PaymentQrDialog(
    qr: Bitmap,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current

    // Keep the screen awake for as long as the code is on screen, and put it back exactly as it was on
    // the way out. FLAG_KEEP_SCREEN_ON on the view is preferred over a WakeLock: it needs no permission
    // and is released automatically if this composable leaves the tree by any route, including a crash.
    DisposableEffect(Unit) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        // Suppress the ambient/screensaver display too. Its idle detector would otherwise dim the
        // screen or replace it with the table board — over the very code someone is mid-scan on
        // (Requirement 13.7).
        AmbientSuppressor.push()
        onDispose {
            view.keepScreenOn = previous
            AmbientSuppressor.pop()
        }
    }

    Dialog(
        onDismissRequest = { /* explicit action only — see class docs */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Darkened backdrop (Requirement 13.4). Drawn here rather than relying on the platform scrim
        // so the contrast around the code is predictable regardless of theme.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // The image, as large as its own aspect ratio allows. A QR must not be distorted, so
                // ContentScale.Fit — never FillBounds, which would stretch the modules and can defeat
                // a scanner outright.
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = context.getString(R.string.payment_qr_section),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            if (qr.height > 0) qr.width.toFloat() / qr.height.toFloat() else 1f
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(12.dp),
                )

                // The only control. Labelled rather than an icon so it is unambiguous to whoever is
                // holding the device — often the customer, not the operator.
                TextButton(onClick = onDismiss) {
                    Text(
                        text = context.getString(R.string.payment_qr_close),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * Reference-counted suppression of the ambient/screensaver display.
 *
 * Counted rather than a plain boolean because more than one screen may legitimately want the ambient
 * display held off at the same time; a boolean would let whichever finished first re-enable it under
 * the other. `pop()` clamps at zero so an unbalanced call cannot drive the count negative and leave
 * ambient mode suppressed for the rest of the process.
 */
object AmbientSuppressor {
    @Volatile
    private var depth: Int = 0

    val isSuppressed: Boolean get() = depth > 0

    @Synchronized
    fun push() {
        depth += 1
    }

    @Synchronized
    fun pop() {
        if (depth > 0) depth -= 1
    }
}
