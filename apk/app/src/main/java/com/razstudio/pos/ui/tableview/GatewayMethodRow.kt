package com.razstudio.pos.ui.tableview

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.local.PaymentMethod
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.viewmodels.SplitPaymentPlanner

/**
 * A merchant-scan attempt waiting on a barcode (task 8.3), between tapping an e-wallet tile and
 * the resulting [com.razstudio.pos.ui.viewmodels.GatewayCheckoutState.Initiating]. Lives at the
 * screen level (`AdminHomeScreen`/`StaffTableViewScreen`) because the camera needs a full-screen
 * surface `OrderDetailSheet`'s `ModalBottomSheet` cannot host. [splitPlan] non-null means the scan
 * is for one customer's share, not the whole bill.
 */
data class MerchantScanRequest(
    val orderId: String,
    val tableId: String?,
    val method: PaymentMethod,
    val amount: Double,
    val printReceipt: Boolean,
    val splitPlan: SplitPaymentPlanner.Plan.SliceOff? = null,
)

/** Display name for a [PaymentMethod] at checkout. CASH/STATIC_QR keep their own dedicated Pay
 *  Cash / Pay QR buttons elsewhere and never reach this. */
fun paymentMethodLabel(method: PaymentMethod, strings: UiStrings): String = when (method) {
    PaymentMethod.CASH -> strings.payCash
    PaymentMethod.STATIC_QR -> strings.payQR
    PaymentMethod.DUITNOW_QR -> strings.paymentMethodDuitNowQr
    PaymentMethod.TNG -> strings.paymentMethodTng
    PaymentMethod.GRABPAY -> strings.paymentMethodGrabPay
    PaymentMethod.BOOST -> strings.paymentMethodBoost
    PaymentMethod.SHOPEEPAY -> strings.paymentMethodShopeePay
    PaymentMethod.FPX -> strings.paymentMethodFpx
    PaymentMethod.CARD -> strings.paymentMethodCard
}

/**
 * Row of gateway-channel tiles, joining the existing Pay Cash / Pay QR buttons rather than
 * replacing them (designs.md A13). Horizontally scrollable — up to 7 channels (DuitNow QR, TNG,
 * GrabPay, Boost, ShopeePay, FPX, Card) would overflow a fixed-width row on the narrower portrait
 * layout otherwise.
 *
 * Absent entirely when [methods] is empty — the caller passes an empty list whenever
 * `ModeCapabilities.gatewayPaymentsEnabled` is false or the café has no channels enabled, matching
 * Show QR's own "absent, not disabled" rule (Requirement 13.3): a till with no gateway configured
 * shows nothing extra rather than a row of tiles that would only ever fail.
 */
@Composable
fun GatewayMethodRow(
    methods: List<PaymentMethod>,
    strings: UiStrings,
    enabled: Boolean,
    onSelect: (PaymentMethod) -> Unit,
) {
    if (methods.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        methods.forEach { method ->
            OutlinedButton(onClick = { onSelect(method) }, enabled = enabled) {
                Text(paymentMethodLabel(method, strings))
            }
        }
    }
}
