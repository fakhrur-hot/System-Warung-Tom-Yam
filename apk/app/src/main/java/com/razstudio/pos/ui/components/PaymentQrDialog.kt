package com.razstudio.pos.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.razstudio.pos.R

/** Space reserved below the code for the close control, so the image can never push it off-screen. */
private val CLOSE_ROW_RESERVE = 104.dp

/** Upper bound on the code's size — beyond this it stops helping a phone camera and just wastes space. */
private val QR_MAX_SIDE = 520.dp

/** Lower bound, so a very short window still shows something scannable rather than a sliver. */
private val QR_MIN_SIDE = 200.dp

/**
 * The café's static Payment QR, shown for a customer to scan
 * (Requirements 13.4, 13.5, 13.6, 13.7, 13.8).
 *
 * ### The bug this layout exists to prevent
 *
 * The first version sized the image with `fillMaxWidth().aspectRatio(...)`, which constrains **width
 * only**. In landscape (2720x1224 on the test hardware) that asked for 2720dp of *height* on a window
 * ~1224px tall. On a real device the code overflowed every edge, its corner finder patterns were
 * cropped away — so it very likely would not have decoded at all — and, far worse, the close button was
 * pushed off-screen. Combined with `dismissOnBackPress = false` there was then **no way out**: the
 * operator was locked out of the POS until the app was force-stopped.
 *
 * The code is therefore bounded on **both** axes now, derived from the *shorter* window dimension minus
 * the space reserved for the close row. It is a hard [Modifier.size], not fill-one-axis-and-hope, and
 * the close control is a sibling the image cannot displace. The trap is structurally impossible rather
 * than merely unlikely.
 *
 * Portrait never exposed the fault because width happened to be the limiting dimension there.
 *
 * ### Other deliberate choices
 *
 * **Back dismisses now.** It did not, on the reasoning that a stray touch must not close the code
 * mid-scan. The lock-out above settles that trade: no-escape-hatch is a worse failure than
 * closed-slightly-early. Back is deliberate; an outside tap is accidental, so outside-tap stays off.
 *
 * **No order total, table, or amount beside the code** (Requirement 13.8). This is a *static payee* QR:
 * it encodes no amount and no order reference. The customer's own banking app shows them the payee and
 * they key the sum themselves, so anything rendered alongside risks being read as part of the payment
 * instruction — and there is no audit trail to catch that afterwards.
 *
 * **No brightness override.** Keeping the screen awake is what prevents a failed scan; how bright it is
 * remains the operator's setting.
 *
 * **No timer, no animation, no transform** (Requirement 13.5) — a customer may take a while to open
 * their banking app.
 */
@Composable
fun PaymentQrDialog(
    qr: Bitmap,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val config = LocalConfiguration.current

    // Bound by the SHORTER side so the code fits in landscape and portrait alike. This is the whole
    // fix: a square derived from min(width, height) cannot exceed the window on either axis.
    val shorterSide = minOf(config.screenWidthDp, config.screenHeightDp).dp
    val qrSide = (shorterSide - CLOSE_ROW_RESERVE)
        .coerceAtMost(QR_MAX_SIDE)
        .coerceAtLeast(QR_MIN_SIDE)

    // Keep the screen awake while the code is displayed, restoring the previous setting on the way out.
    // FLAG_KEEP_SCREEN_ON on the view rather than a WakeLock: no permission required, and it is
    // released automatically however this composable leaves the tree.
    DisposableEffect(Unit) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        AmbientSuppressor.push()
        onDispose {
            view.keepScreenOn = previous
            AmbientSuppressor.pop()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // The white card supplies the quiet zone a scanner needs. Padding sits INSIDE the fixed
                // square, so the code shrinks to accommodate it rather than the card growing past the
                // bound.
                Box(
                    modifier = Modifier
                        .size(qrSide)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Fit, never FillBounds: stretching a QR distorts its modules and can defeat a
                    // scanner outright. Fit also letterboxes a non-square source correctly.
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = context.getString(R.string.payment_qr_section),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Sibling of the image, not a child — the image cannot push this off-screen.
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
 * Counted rather than a boolean because more than one screen may legitimately hold ambient off at once;
 * a boolean would let whichever finished first re-enable it under the other. [pop] clamps at zero so an
 * unbalanced call cannot leave ambient suppressed for the rest of the process.
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
