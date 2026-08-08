package com.razstudio.pos.ui.tableview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.razstudio.pos.data.promos.AffiliateProduct

/**
 * Affiliate product display on the table grid and dashboard.
 *
 * Adapts to orientation:
 * - **Landscape**: renders a compact banner (horizontal row: image + label + Ad badge).
 *   Takes less vertical space — ideal for POS tills in landscape.
 * - **Portrait**: renders centered square tiles in a horizontal LazyRow.
 *
 * Behaviour:
 * - Renders nothing when [products] is empty (no placeholder, no spinner).
 * - Error boundary: if data is corrupt, section disappears silently.
 */
@Composable
fun AffiliateSection(
    products: List<AffiliateProduct>,
    onProductClick: (AffiliateProduct) -> Unit,
    onImpression: (AffiliateProduct) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (products.isEmpty()) return

    // Error boundary: catch any exception during composition and hide section silently.
    val shouldRender = runCatching {
        products.forEach { it.url; it.imageUrl; it.label }
    }.isSuccess
    if (!shouldRender) return

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // Banner format: compact horizontal cards, centered
        LazyRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(
                items = products,
                key = { it.url },
            ) { product ->
                LaunchedEffect(product.id) { onImpression(product) }
                AffiliateBanner(
                    product = product,
                    onClick = { onProductClick(product) },
                )
            }
        }
    } else {
        // Portrait: square tiles, centered
        LazyRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(
                items = products,
                key = { it.url },
            ) { product ->
                LaunchedEffect(product.id) { onImpression(product) }
                ProductTile(
                    product = product,
                    onClick = { onProductClick(product) },
                )
            }
        }
    }
}

/**
 * Compact banner-style affiliate card for landscape mode.
 * Horizontal layout: small image + product label + "Ad" badge.
 * Takes ~48dp height — much shorter than the 100dp square tiles.
 *
 * `internal` (not `private`) so other screens that want this exact compact look — e.g. the
 * menu-picker list inserting one banner every few items — can reuse it instead of duplicating it.
 */
@Composable
internal fun AffiliateBanner(
    product: AffiliateProduct,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .width(220.dp)
            .height(48.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Product image — small square thumbnail
            AsyncImage(
                model = product.imageUrl,
                contentDescription = product.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp)),
                error = painterResource(android.R.drawable.ic_menu_gallery),
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Product label
            Text(
                text = product.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(6.dp))

            // "Ad" badge
            Text(
                text = "Ad",
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
