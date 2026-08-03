package com.razstudio.pos.ui.tableview

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.razstudio.pos.data.local.PaymentCategory
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.viewmodels.GatewayCheckoutState
import kotlinx.coroutines.delay

/**
 * Full-screen gateway checkout (designs.md Screen 3, task 8.1/8.4).
 *
 * Every channel the evaluated aggregator documents is a hosted page, not a seamless API returning
 * a QR payload to render ourselves (F1, F6 #1). What that page becomes here depends on
 * [GatewayCheckoutState.AwaitingPayment.method]'s [PaymentCategory]:
 *
 * - `QR_PAYNET` (DuitNow): QR-encode the checkout URL for the customer's **own** phone to scan,
 *   with the same URL underneath as a fallback link, and mirror the bitmap to the customer
 *   display when one is configured (task 4.2's `PaymentQr` state — see the ViewModel call sites).
 * - `E_WALLET` (TNG/GrabPay/Boost/ShopeePay): no QR here at all — the customer's wallet code was
 *   already scanned by the cashier before this attempt was even initiated (task 8.3), so this
 *   just shows a "confirming" spinner while the same poll loop as every other method runs.
 * - `ONLINE_BANKING` (FPX) / `CARD`: an embedded [WebView] loads the checkout URL directly — the
 *   bank/card page is meant to be interacted with right there, not scanned from a second device
 *   (task 8.4). Navigating back to our own callback URL is the "callback intercept" signal that
 *   nudges [onNudgePoll] rather than waiting for the next scheduled poll tick.
 *
 * A true full-screen [Dialog] rather than a [androidx.compose.material3.ModalBottomSheet] — this
 * must sit on top of [OrderDetailSheet] too, and back/outside-tap must not silently abandon a
 * payment the customer may be mid-scan or mid-bank-login on; [onCancel] is the only way out while
 * PENDING.
 */
@Composable
fun GatewayCheckoutOverlay(
    state: GatewayCheckoutState,
    strings: UiStrings,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onNudgePoll: () -> Unit = {},
) {
    Dialog(
        onDismissRequest = { if (state !is GatewayCheckoutState.AwaitingPayment) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = state !is GatewayCheckoutState.AwaitingPayment,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (state) {
                is GatewayCheckoutState.Initiating -> InitiatingContent(state, strings)
                is GatewayCheckoutState.AwaitingPayment ->
                    AwaitingPaymentContent(state, strings, onCancel, onNudgePoll)
                is GatewayCheckoutState.Failed -> FailedContent(state.message, strings, onDismiss)
                GatewayCheckoutState.TimedOut -> TimedOutContent(strings, onDismiss)
            }
        }
    }
}

@Composable
private fun InitiatingContent(state: GatewayCheckoutState.Initiating, strings: UiStrings) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(strings.gatewayCheckoutInitiating, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${paymentMethodLabel(state.method, strings)} · RM %.2f".format(state.amount),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AwaitingPaymentContent(
    state: GatewayCheckoutState.AwaitingPayment,
    strings: UiStrings,
    onCancel: () -> Unit,
    onNudgePoll: () -> Unit,
) {
    when (state.method.category) {
        PaymentCategory.ONLINE_BANKING, PaymentCategory.CARD ->
            HostedCheckoutWebViewContent(state, strings, onCancel, onNudgePoll)
        PaymentCategory.E_WALLET -> ConfirmingContent(state, strings, onCancel)
        else -> QrDisplayContent(state, strings, onCancel)
    }
}

/** DuitNow QR — the customer scans it with their own phone (task 8.1). */
@Composable
private fun QrDisplayContent(
    state: GatewayCheckoutState.AwaitingPayment,
    strings: UiStrings,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var secondsRemaining by remember(state.transactionId) {
        mutableLongStateOf(((state.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(state.transactionId) {
        while (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining = ((state.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MethodAmountHeader(state, strings)
        Spacer(Modifier.height(24.dp))

        // A white plate regardless of theme — a dark background behind a QR destroys the quiet
        // zone and scanners fail on it. Same rule as the customer-display PaymentQr state.
        if (state.qr != null) {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = state.qr.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(
            strings.gatewayCheckoutScanToPay,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${strings.gatewayCheckoutExpiresIn} ${secondsRemaining / 60}:${(secondsRemaining % 60).toString().padStart(2, '0')}",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.checkoutUrl)))
            }
        }) {
            Text(strings.gatewayCheckoutOpenLink)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCancel) {
            Text(strings.gatewayCancelPayment)
        }
    }
}

/** E-wallet merchant-scan (task 8.3) — the code was already captured before this attempt
 *  started, so there is nothing left to show the cashier except that it's being checked. */
@Composable
private fun ConfirmingContent(
    state: GatewayCheckoutState.AwaitingPayment,
    strings: UiStrings,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MethodAmountHeader(state, strings)
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(strings.gatewayCheckoutConfirming, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCancel) {
            Text(strings.gatewayCancelPayment)
        }
    }
}

/**
 * FPX / Card hosted checkout (task 8.4) — the bank/card page loads directly in an embedded
 * [WebView], since a login form is meant to be filled in right there, not scanned from a second
 * device. Detects a navigation back to our own `payment-callback` URL (the return URL Fiuu
 * redirects to after the bank/card flow finishes) and nudges the poll loop immediately instead of
 * waiting for its next scheduled tick — the "callback intercept" the task calls for.
 */
@Composable
private fun HostedCheckoutWebViewContent(
    state: GatewayCheckoutState.AwaitingPayment,
    strings: UiStrings,
    onCancel: () -> Unit,
    onNudgePoll: () -> Unit,
) {
    var isLoading by remember(state.transactionId) { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            MethodAmountHeader(state, strings)
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                // Our own callback URL is a server-to-server endpoint, not a page
                                // meant to be viewed — reaching it means the bank/card flow
                                // completed and redirected here, which is the signal to check
                                // status now rather than on the next scheduled poll tick.
                                if (url != null && url.contains("/functions/v1/payment-callback")) {
                                    onNudgePoll()
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                            }
                        }
                        loadUrl(state.checkoutUrl)
                    }
                },
            )
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(strings.gatewayCheckoutLoadingPage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Closing here without having completed is an abandonment, not a timeout — same
        // cancellation path as every other method (designs.md's error matrix / A17).
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(strings.gatewayCancelPayment)
        }
    }
}

@Composable
private fun MethodAmountHeader(state: GatewayCheckoutState.AwaitingPayment, strings: UiStrings) {
    Text(
        paymentMethodLabel(state.method, strings),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "RM %.2f".format(state.amount),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun FailedContent(message: String, strings: UiStrings, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            strings.gatewayCheckoutFailedTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(strings.gatewayCheckoutBackToCheckout)
        }
    }
}

@Composable
private fun TimedOutContent(strings: UiStrings, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            strings.gatewayCheckoutTimedOutTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(strings.gatewayCheckoutTimedOutBody, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(strings.gatewayCheckoutBackToCheckout)
        }
    }
}
