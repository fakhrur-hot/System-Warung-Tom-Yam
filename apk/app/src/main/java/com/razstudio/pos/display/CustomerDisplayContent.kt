package com.razstudio.pos.display

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.razstudio.pos.ui.i18n.UiStrings

/**
 * What the customer sees, laid out for **800 × 480 landscape at mdpi** — the geometry read off the
 * unit, not the 480 × 800 in the vendor sheet, which would render this sideways. (H6)
 *
 * At density 160 the usable design space is 800 × 480 **dp**, which is small. The whole layout is
 * built around that: large type, few elements, and never more than one idea on screen. Text is
 * sized for someone standing at a counter roughly an arm's length away, not for a phone in the
 * hand — so body text is 20sp and the total is 44sp, far larger than anywhere else in the app.
 */
@Composable
fun CustomerDisplayContent(state: CustomerDisplayState, s: UiStrings) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (state) {
            is CustomerDisplayState.Idle -> IdleScreen(state, s)
            is CustomerDisplayState.Order -> OrderScreen(state, s)
            is CustomerDisplayState.PaymentQr -> PaymentQrScreen(state, s)
            is CustomerDisplayState.ThankYou -> ThankYouScreen(state, s)
        }
    }
}

@Composable
private fun IdleScreen(state: CustomerDisplayState.Idle, s: UiStrings) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        state.logo?.let { logo ->
            Image(
                bitmap = logo.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(160.dp)
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = state.cafeName,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = s.customerDisplayWelcome,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OrderScreen(state: CustomerDisplayState.Order, s: UiStrings) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Lines on the left, running total pinned on the right. The total must never scroll out
        // of view — it is the one number the customer is actually checking.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(20.dp)
        ) {
            state.tableLabel?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            val listState = rememberLazyListState()
            // Follow the newest line. A customer watching items go on expects to see the one just
            // added, and staff cannot reach across to scroll a customer-facing screen.
            LaunchedEffect(state.lines.size) {
                if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.lines) { line ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${line.quantity}×",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(48.dp)
                        )
                        Text(
                            text = line.name,
                            fontSize = 20.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "RM %.2f".format(line.lineTotal),
                            fontSize = 20.sp
                        )
                    }
                }
            }
        }

        TotalPanel(total = state.total, label = s.grandTotal)
    }
}

@Composable
private fun TotalPanel(total: Double, label: String) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "RM %.2f".format(total),
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun PaymentQrScreen(state: CustomerDisplayState.PaymentQr, s: UiStrings) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // The QR gets a white plate regardless of theme. A dark-themed background behind a QR
        // destroys the quiet zone and scanners fail on it — this is not a styling preference.
        Box(
            modifier = Modifier
                .size(380.dp)
                .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = state.qr.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = s.customerDisplayScanToPay,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            state.caption?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(modifier = Modifier.width(220.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                text = "RM %.2f".format(state.amount),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ThankYouScreen(state: CustomerDisplayState.ThankYou, s: UiStrings) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = s.customerDisplayThankYou,
            fontSize = 46.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        // Change is shown large and only when there is some — it is the last thing a cash
        // customer wants to verify, and showing "RM 0.00" after a card payment is noise.
        state.changeDue?.takeIf { it > 0.0 }?.let { change ->
            Spacer(Modifier.height(20.dp))
            Text(
                text = s.customerDisplayChange,
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "RM %.2f".format(change),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
