package com.razstudio.pos.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.local.DailyRevenue
import com.razstudio.pos.data.local.HourlyRevenue
import com.razstudio.pos.data.local.PaymentMethodCount
import com.razstudio.pos.data.local.PopularItemRow
import com.razstudio.pos.data.promos.AffiliateProduct
import com.razstudio.pos.ui.tableview.AffiliateSection
import com.razstudio.pos.ui.viewmodels.DashboardUiState
import com.razstudio.pos.ui.viewmodels.DashboardViewModel

/**
 * Live dashboard page — shown as page 0 in the HorizontalPager on AdminHomeScreen.
 * Swipe right to get to the Table Grid (page 1).
 *
 * All data is from DashboardViewModel, which queries the current business day.
 * Charts are custom Compose Canvas — no external library, following MyBrain-master's pattern.
 */
@Composable
fun DashboardPage(
    viewModel: DashboardViewModel = hiltViewModel(),
    affiliateProducts: List<AffiliateProduct> = emptyList(),
    onAffiliateProductClick: (AffiliateProduct) -> Unit = {},
    onAffiliateImpression: (AffiliateProduct) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val activeOrders by viewModel.activeOrderCount.collectAsState()

    // Refresh data when the page appears
    LaunchedEffect(Unit) { viewModel.refresh() }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── KPI Summary Cards (all 5 in one row) ─────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KpiCard(
                title = "Revenue",
                value = "RM %.2f".format(state.totalRevenue),
                modifier = Modifier.weight(1f),
                accent = Color(0xFF10B981),
            )
            KpiCard(
                title = "Orders",
                value = "${state.totalOrders}",
                subtitle = if (state.yesterdayOrders > 0) "vs ${state.yesterdayOrders} yday" else null,
                modifier = Modifier.weight(1f),
                accent = Color(0xFF3B82F6),
            )
            KpiCard(
                title = "Avg",
                value = "RM %.2f".format(state.avgOrderValue),
                modifier = Modifier.weight(1f),
                accent = Color(0xFF8B5CF6),
            )
            KpiCard(
                title = "Active",
                value = "$activeOrders",
                modifier = Modifier.weight(1f),
                accent = Color(0xFFF59E0B),
            )
            KpiCard(
                title = "Cancelled",
                value = "${state.cancelledCount}",
                subtitle = "%.1f%%".format(state.cancelledRate * 100),
                modifier = Modifier.weight(1f),
                accent = if (state.cancelledRate > 0.1f) Color(0xFFEF4444) else Color(0xFF6B7280),
            )
        }

        // ── Hourly revenue line chart ────────────────────────────────────────────────
        if (state.hourlyRevenue.isNotEmpty()) {
            DashboardCard(title = "Hourly Revenue") {
                HourlyRevenueChart(
                    data = state.hourlyRevenue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                )
            }
        }

        // ── Daily trend revenue line chart ───────────────────────────────────────────
        if (state.dailyRevenue.isNotEmpty()) {
            DashboardCard(title = "Daily Trend") {
                DailyTrendChart(
                    data = state.dailyRevenue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                )
            }
        }

        // ── Payment split donut ──────────────────────────────────────────────────────
        if (state.paymentSplit.isNotEmpty()) {
            DashboardCard(title = "Payment Methods") {
                PaymentDonutChart(
                    data = state.paymentSplit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                )
            }
        }

        // ── Best sellers bar chart ───────────────────────────────────────────────────
        if (state.bestSellers.isNotEmpty()) {
            DashboardCard(title = "Best Sellers") {
                BestSellersChart(
                    items = state.bestSellers,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── Affiliate product tiles ──────────────────────────────────────────────────
        AffiliateSection(
            products = affiliateProducts,
            onProductClick = onAffiliateProductClick,
            onImpression = onAffiliateImpression,
        )

        Spacer(modifier = Modifier.height(80.dp)) // Room for FAB
    }
}

// ── KPI Card ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun KpiCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = accent,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Dashboard section card ───────────────────────────────────────────────────────────────────

@Composable
private fun DashboardCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

// ── Hourly Revenue line chart (Canvas) ───────────────────────────────────────────────────────

@Composable
private fun HourlyRevenueChart(
    data: List<HourlyRevenue>,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val maxRevenue = data.maxOf { it.revenue }.coerceAtLeast(1.0)
        val count = data.size
        val stepX = w / (count - 1).coerceAtLeast(1)

        // Grid lines
        for (i in 0..3) {
            val y = h * i / 4f
            drawLine(surfaceVariant, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        // Build path
        val points = data.mapIndexed { index, hourly ->
            Offset(
                x = stepX * index,
                y = h * (1f - (hourly.revenue / maxRevenue).toFloat()),
            )
        }

        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val curr = points[i]
                val cx = (prev.x + curr.x) / 2
                cubicTo(cx, prev.y, cx, curr.y, curr.x, curr.y)
            }
        }

        // Gradient fill
        val fillPath = Path().apply {
            addPath(path)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }

        drawPath(
            fillPath,
            brush = Brush.verticalGradient(
                listOf(primary.copy(alpha = 0.3f), Color.Transparent),
                endY = h,
            ),
        )

        // Line
        drawPath(path, color = primary, style = Stroke(width = 3f, cap = StrokeCap.Round))

        // Dots
        points.forEach { pt ->
            drawCircle(primary, radius = 4f, center = pt)
        }
    }
}

// ── Daily Trend line chart (Canvas, MyBrain-style) ───────────────────────────────────────────

@Composable
private fun DailyTrendChart(
    data: List<DailyRevenue>,
    modifier: Modifier = Modifier,
) {
    val lineColor = Color(0xFF8B5CF6) // Purple accent from MyBrain theme
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        // Date labels row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            data.forEach { day ->
                // Show short day label (e.g., "Mon", "Tue")
                val dayLabel = try {
                    val localDate = java.time.LocalDate.parse(day.date)
                    localDate.dayOfWeek.name.take(3).lowercase()
                        .replaceFirstChar { it.uppercase() }
                } catch (_: Exception) {
                    day.date.takeLast(2)
                }
                Text(
                    text = dayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        // Line chart canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (data.isEmpty()) return@Canvas
            val w = size.width
            val h = size.height
            val maxRevenue = data.maxOf { it.revenue }.coerceAtLeast(1.0)
            val count = data.size
            val stepX = w / (count - 1).coerceAtLeast(1)

            // Grid lines
            for (i in 0..3) {
                val y = h * i / 4f
                drawLine(surfaceVariant, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }

            // Build points
            val points = data.mapIndexed { index, daily ->
                Offset(
                    x = stepX * index,
                    y = h * (1f - (daily.revenue / maxRevenue).toFloat()),
                )
            }

            // Smooth path using cubic bezier (MyBrain MoodFlowChart pattern)
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    val prev = points[i - 1]
                    val curr = points[i]
                    val cx = (prev.x + curr.x) / 2
                    cubicTo(cx, prev.y, cx, curr.y, curr.x, curr.y)
                }
            }

            // Gradient fill under the curve
            val fillPath = Path().apply {
                addPath(path)
                lineTo(points.last().x, h)
                lineTo(points.first().x, h)
                close()
            }
            drawPath(
                fillPath,
                brush = Brush.verticalGradient(
                    listOf(lineColor.copy(alpha = 0.25f), Color.Transparent),
                    endY = h,
                ),
            )

            // Line stroke
            drawPath(path, color = lineColor, style = Stroke(width = 3.5f, cap = StrokeCap.Round))

            // Dots at each data point
            points.forEach { pt ->
                drawCircle(color = Color.White, radius = 5f, center = pt)
                drawCircle(color = lineColor, radius = 4f, center = pt)
            }
        }
    }
}

// ── Payment donut chart (Canvas) ─────────────────────────────────────────────────────────────

private val PAYMENT_COLORS = listOf(
    Color(0xFF10B981), // Cash (green)
    Color(0xFF3B82F6), // QR (blue)
    Color(0xFFF59E0B), // DuitNow (amber)
    Color(0xFF8B5CF6), // GrabPay (purple)
    Color(0xFFEF4444), // TNG (red)
    Color(0xFF6B7280), // Other (gray)
)

@Composable
private fun PaymentDonutChart(
    data: List<PaymentMethodCount>,
    modifier: Modifier = Modifier,
) {
    val total = data.sumOf { it.revenue }.coerceAtLeast(1.0)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Donut
        Canvas(
            modifier = Modifier
                .size(120.dp)
                .aspectRatio(1f),
        ) {
            val strokeWidth = 28f
            val radius = (size.minDimension - strokeWidth) / 2
            var startAngle = -90f

            data.forEachIndexed { index, item ->
                val sweep = (item.revenue / total * 360f).toFloat()
                drawArc(
                    color = PAYMENT_COLORS[index % PAYMENT_COLORS.size],
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(
                        (size.width - radius * 2) / 2,
                        (size.height - radius * 2) / 2,
                    ),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                startAngle += sweep
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Legend
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            data.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(PAYMENT_COLORS[index % PAYMENT_COLORS.size])
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${item.paymentMethod ?: "Unknown"}: RM %.2f".format(item.revenue),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

// ── Best sellers horizontal bar chart ────────────────────────────────────────────────────────

@Composable
private fun BestSellersChart(
    items: List<PopularItemRow>,
    modifier: Modifier = Modifier,
) {
    val maxQty = items.maxOfOrNull { it.totalQuantity } ?: 1
    val primary = MaterialTheme.colorScheme.primary

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.nameSnapshot,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(100.dp),
                    maxLines = 1,
                )
                Box(modifier = Modifier.weight(1f).height(16.dp)) {
                    val fraction = item.totalQuantity.toFloat() / maxQty
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRoundRect(
                            color = primary.copy(alpha = 0.15f),
                            size = size,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
                        )
                        drawRoundRect(
                            color = primary,
                            size = Size(size.width * fraction, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${item.totalQuantity}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
