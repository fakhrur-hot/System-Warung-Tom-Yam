package com.razstudio.pos.ui.tableview

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Which payment rail the café's uploaded QR belongs to.
 *
 * The stored QR is just an image — nothing in it tells a customer whether to open DuitNow, Touch
 * 'n Go, or something else, and a code presented with no label is the most common reason a
 * customer hesitates at the counter. This is that missing label.
 *
 * [brandColor] is each rail's own brand colour, used as the plate behind the code.
 */
enum class PaymentQrBrand(
    val label: String,
    val brandColor: Color,
    /** Drawn on the brand plate above the code. Kept as text until real logo assets are added. */
    val shortMark: String,
) {
    /** DuitNow — Malaysia's national QR. Pink is PayNet's own brand colour, #EC008C. */
    DUITNOW("DuitNow", Color(0xFFEC008C), "D"),

    /** Touch 'n Go eWallet. Blue matches the wallet's own mark. */
    TOUCH_N_GO("Touch 'n Go", Color(0xFF1B4E9B), "TnG"),

    /** No rail claimed — the code is shown plain, which is the old behaviour. */
    NONE("—", Color(0xFF4A4A4A), "");

    companion object {
        fun fromName(value: String?): PaymentQrBrand =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}

/**
 * The café's payment QR, shown inline in the order sheet's actions pane.
 *
 * Sits above the pay controls rather than behind a button, because that pane had a large dead area
 * in landscape and a QR the customer must actually look at is a poor fit for a dialog the cashier
 * has to open first. The "Show QR" button remains for the portrait layout, where there is no room
 * for this.
 *
 * The code itself is drawn on **white regardless of theme**: a dark plate behind a QR destroys the
 * quiet zone and scanners fail on it. The brand colour is the surround, never the code's
 * background — the same rule the customer display and gateway checkout already follow.
 */
@Composable
fun PaymentQrPanel(
    qr: Bitmap,
    brand: PaymentQrBrand,
    onBrandChange: (PaymentQrBrand) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(brand.brandColor)
                .padding(10.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (brand != PaymentQrBrand.NONE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Stand-in for the rail's logo: a white roundel carrying its short mark.
                        // Swapping in the real artwork is a drop-in replacement for this Box.
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = brand.shortMark,
                                color = brand.brandColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                        Text(
                            text = brand.label,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // White plate, always. See the class note — this is a scanning requirement.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Which rail this code is for. A dropdown rather than a fixed label because a café can
        // swap the uploaded image without reinstalling, and the label has to follow it.
        Box {
            Row(
                modifier = Modifier
                    .clickable { menuOpen = true }
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = brand.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                PaymentQrBrand.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onBrandChange(option)
                            menuOpen = false
                        },
                    )
                }
            }
        }
    }
}
