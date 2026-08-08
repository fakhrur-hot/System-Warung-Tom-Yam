package com.razstudio.pos.ui.tableview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.razstudio.pos.data.promos.AffiliateProduct
import kotlinx.coroutines.delay

/**
 * A larger affiliate product card for the ambient display (screensaver mode).
 *
 * Shows a single featured product at a time — rotating to the next product every 3 minutes.
 * Renders nothing when [products] is empty. Positioned at the bottom of the ambient screen,
 * below the table status board.
 *
 * A tap on the card calls [onClick] (dismissing ambient mode and opening the affiliate URL is the
 * caller's job — see `AmbientOverlay`) instead of relying on the parent's generic
 * tap-anywhere-dismisses `detectTapGestures`, so the affiliate placement can actually drive a
 * click-through rather than being a purely passive impression surface.
 */
@Composable
fun AmbientAffiliateCard(
    products: List<AffiliateProduct>,
    onClick: (AffiliateProduct) -> Unit = {},
    onImpression: (AffiliateProduct) -> Unit = {},
    rotationIntervalMs: Long = 180_000L, // 3 minutes
    modifier: Modifier = Modifier,
) {
    if (products.isEmpty()) return

    // Auto-rotate to the next product every rotationIntervalMs.
    var currentIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(products.size) {
        while (true) {
            delay(rotationIntervalMs)
            currentIndex = (currentIndex + 1) % products.size
        }
    }

    val product = products[currentIndex % products.size]

    // One impression per product shown, re-fired whenever rotation lands on a new one.
    LaunchedEffect(product.id) { onImpression(product) }

    // Larger card format than the table grid tiles — horizontal layout with product image
    // and label side by side. Uses dim styling matching the ambient screen's OLED-safe palette.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .height(80.dp)
            .clickable { onClick(product) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Product image — square thumbnail
        AsyncImage(
            model = product.imageUrl,
            contentDescription = product.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp)),
            error = painterResource(android.R.drawable.ic_menu_gallery),
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Product label and "Ad" indicator
        Text(
            text = product.label,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(8.dp))

        // "Ad" badge — subtle, semi-transparent
        Text(
            text = "Ad",
            modifier = Modifier
                .background(
                    color = Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White.copy(alpha = 0.38f),
            fontSize = 10.sp,
        )
    }
}
