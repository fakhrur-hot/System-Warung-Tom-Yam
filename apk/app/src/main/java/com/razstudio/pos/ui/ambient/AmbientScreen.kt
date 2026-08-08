package com.razstudio.pos.ui.ambient

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.razstudio.pos.ui.tableview.AmbientAffiliateCard
import com.razstudio.pos.ui.tableview.TableState
import com.razstudio.pos.ui.tableview.TableUiStatus
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ambient (screensaver) visuals for an idle POS station.
 *
 * Everything here is drawn procedurally — no bundled artwork, no network, no image decoding — so it
 * can run for a whole service at negligible cost and never repeats a frame. The motif is the
 * cuisine's own world: warm lantern pools, drifting wok steam, and rising embers over a true-black
 * ground, with the live table grid as the hero.
 *
 * OLED protection is structural, not cosmetic:
 * - True `Color.Black` ground lets the panel switch those pixels off entirely.
 * - The whole content layer drifts continuously on two mismatched periods (97s / 131s), so no
 *   element ever settles on a fixed pixel and the drift path does not retrace itself.
 * - Nothing is drawn at full white; text tops out around 65% alpha and tiles stay dim.
 */
@Composable
fun AmbientScreen(
    tables: List<TableState>,
    newOrderLabel: String?,
    cafeName: String,
    isCustomerFacing: Boolean,
    strings: com.razstudio.pos.ui.i18n.UiStrings,
    affiliateProducts: List<com.razstudio.pos.data.promos.AffiliateProduct> = emptyList(),
    onAffiliateClick: (com.razstudio.pos.data.promos.AffiliateProduct) -> Unit = {},
    onAffiliateImpression: (com.razstudio.pos.data.promos.AffiliateProduct) -> Unit = {},
) {
    // Monotonic seconds since this screen appeared. A monotonic clock (rather than a looping
    // animateFloat) keeps the procedural motion seamless — a restarting transition would make every
    // particle jump at the loop boundary.
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { time.floatValue = (it - start) / 1000f }
        }
    }

    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AmbientBackdrop(timeProvider = { time.floatValue }, accent = accent)

        // Burn-in drift: two mismatched periods so the content never retraces the same path.
        val driftX = sin(time.floatValue * (2f * Math.PI.toFloat() / 97f)) * 14f
        val driftY = sin(time.floatValue * (2f * Math.PI.toFloat() / 131f)) * 14f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(driftX.roundToInt(), driftY.roundToInt()) }
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            AmbientHeader(
                cafeName = cafeName,
                occupied = tables.count { it.status != TableUiStatus.FREE },
                total = tables.size,
                strings = strings,
            )

            // weight(1f), not fillMaxSize(): the grid must yield the last row of space to the
            // resume hint below it, otherwise the hint is pushed off-screen entirely.
            Box(modifier = Modifier.weight(1f)) {
                AmbientTableGrid(
                    tables = tables,
                    isCustomerFacing = isCustomerFacing,
                    timeProvider = { time.floatValue },
                    strings = strings,
                )

                newOrderLabel?.let { label ->
                    NewOrderPulse(
                        label = label,
                        accent = accent,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            // Affiliate product card — below the table grid, above the resume hint.
            AmbientAffiliateCard(
                products = affiliateProducts,
                onClick = onAffiliateClick,
                onImpression = onAffiliateImpression,
            )

            Text(
                text = strings.ambientTapToResume,
                color = Color.White.copy(alpha = 0.22f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Procedural backdrop: lantern glow pools, drifting steam, and rising embers.
 *
 * [timeProvider] is read inside the draw lambda rather than captured as a value, so a new frame
 * invalidates only the draw phase — composition never re-runs for the animation.
 */
@Composable
private fun AmbientBackdrop(timeProvider: () -> Float, accent: Color) {
    val embers = remember {
        List(18) {
            Ember(
                originX = Random.nextFloat(),
                speed = Random.nextFloat() * 26f + 12f,
                radius = Random.nextFloat() * 2.6f + 1.2f,
                sway = Random.nextFloat() * 34f + 12f,
                phase = Random.nextFloat() * 6.283f,
            )
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val t = timeProvider()
        val w = size.width
        val h = size.height

        // ── Lantern pools: three warm, very dim radial glows drifting on slow independent orbits.
        // Alphas are deliberately low (OLED safety) but high enough to read as a lit room rather
        // than an off panel — verified on-device against a true-black ground.
        val lanterns = listOf(
            Triple(0.15f, 0.24f, 0.13f),
            Triple(0.80f, 0.14f, 0.10f),
            Triple(0.50f, 0.84f, 0.085f),
        )
        lanterns.forEachIndexed { i, (baseX, baseY, alpha) ->
            val driftX = sin(t * 0.045f + i * 2.1f) * (w * 0.06f)
            val driftY = sin(t * 0.031f + i * 1.3f) * (h * 0.05f)
            val center = Offset(baseX * w + driftX, baseY * h + driftY)
            val radius = w * (0.30f + 0.02f * sin(t * 0.06f + i))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = alpha),
                        Color(0xFFE8A15C).copy(alpha = alpha * 0.45f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // ── Wok steam: broad soft wisps sliding across the lower third.
        for (i in 0 until 3) {
            val phase = t * (0.07f + i * 0.02f) + i * 1.9f
            val shiftX = sin(phase) * (w * 0.22f)
            val bandTop = h * (0.55f + i * 0.11f)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.045f - i * 0.010f),
                        Color.Transparent,
                    ),
                    startY = bandTop,
                    endY = h,
                ),
                topLeft = Offset(-w * 0.15f + shiftX, bandTop),
                size = Size(w * 1.3f, h - bandTop),
            )
        }

        // ── Embers: warm motes rising and swaying, dimming as they climb.
        embers.forEach { e ->
            val travel = h + 120f
            val y = h - ((e.speed * t) % travel)
            val x = e.originX * w + sin(t * 0.6f + e.phase) * e.sway
            val climbed = 1f - (y / h).coerceIn(0f, 1f)
            val fade = (1f - climbed).coerceIn(0f, 1f) * 0.42f
            if (fade > 0.004f) {
                drawCircle(
                    color = Color(0xFFFFB067).copy(alpha = fade),
                    radius = e.radius,
                    center = Offset(x, y),
                )
            }
        }
    }
}

private data class Ember(
    val originX: Float,
    val speed: Float,
    val radius: Float,
    val sway: Float,
    val phase: Float,
)

@Composable
private fun AmbientHeader(
    cafeName: String,
    occupied: Int,
    total: Int,
    strings: com.razstudio.pos.ui.i18n.UiStrings,
) {
    // Recomputed on the minute rather than per-frame — the clock text only changes that often.
    var clock by remember { mutableStateOf(currentClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            clock = currentClock()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.6f)) {
            Text(
                text = cafeName,
                color = Color.White.copy(alpha = 0.52f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (total == 0) strings.ambientNoTables
                       else strings.ambientTablesActive.format(occupied, total),
                color = Color.White.copy(alpha = 0.30f),
                fontSize = 13.sp,
            )
        }
        Text(
            text = clock,
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 30.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun currentClock(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())

@Composable
private fun AmbientTableGrid(
    tables: List<TableState>,
    isCustomerFacing: Boolean,
    timeProvider: () -> Float,
    strings: com.razstudio.pos.ui.i18n.UiStrings,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(tables, key = { it.table.id }) { state ->
            AmbientTableTile(
                state = state,
                isCustomerFacing = isCustomerFacing,
                timeProvider = timeProvider,
                strings = strings,
            )
        }
    }
}

/**
 * One table. Active tables breathe slowly so an occupied station reads at a glance without any
 * bright, static element sitting on the panel.
 */
@Composable
private fun AmbientTableTile(
    state: TableState,
    isCustomerFacing: Boolean,
    timeProvider: () -> Float,
    strings: com.razstudio.pos.ui.i18n.UiStrings,
) {
    val isActive = state.status != TableUiStatus.FREE
    val base = state.status.ambientColor()
    // A per-table phase offset keeps the grid from pulsing in unison.
    val phase = (state.table.id.hashCode() and 0xFF) / 255f * 6.283f

    Box(
        modifier = Modifier
            .aspectRatio(1.25f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0A0B0D)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val breathe = if (isActive) 0.78f + 0.22f * sin(timeProvider() * 0.9f + phase) else 1f
            drawRect(color = base.copy(alpha = base.alpha * breathe))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            Text(
                text = state.table.label,
                color = Color.White.copy(alpha = if (isActive) 0.62f else 0.28f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = state.ambientDetail(isCustomerFacing, strings),
                    color = Color.White.copy(alpha = if (isActive) 0.38f else 0.18f),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}

/** Dim, low-luminance status fills. Semantic hues are preserved but heavily darkened for OLED. */
private fun TableUiStatus.ambientColor(): Color = when (this) {
    TableUiStatus.FREE -> Color(0xFF15171B).copy(alpha = 1f)
    TableUiStatus.RECEIVED -> Color(0xFF4A2B6B).copy(alpha = 0.55f)
    TableUiStatus.SENT_TO_KITCHEN -> Color(0xFF1E3A63).copy(alpha = 0.55f)
    TableUiStatus.PREPARING -> Color(0xFF6B3D0F).copy(alpha = 0.58f)
    TableUiStatus.READY -> Color(0xFF14512F).copy(alpha = 0.62f)
}

/**
 * Tile subtitle. In customer-facing mode this is deliberately non-numeric — occupancy only, so a
 * screen the dining room can see never exposes order values.
 */
private fun TableState.ambientDetail(
    isCustomerFacing: Boolean,
    strings: com.razstudio.pos.ui.i18n.UiStrings,
): String {
    if (status == TableUiStatus.FREE) return if (isCustomerFacing) "" else strings.free
    val statusWord = when (status) {
        TableUiStatus.READY -> strings.ambientReady
        TableUiStatus.PREPARING -> strings.ambientCooking
        else -> strings.ambientSeated
    }
    if (isCustomerFacing) return statusWord
    // Staff mode shows the running total; fall back to the localized status if the order is gone.
    val total = order?.total ?: return statusWord
    return "RM ${"%.2f".format(total)}"
}

/** A new order sliding in — dim, warm, and short-lived so nothing persists on the panel. */
@Composable
private fun NewOrderPulse(label: String, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.16f))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFFFFC48A).copy(alpha = 0.82f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
