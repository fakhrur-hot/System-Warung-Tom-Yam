@file:OptIn(ExperimentalMaterial3Api::class)

package com.razstudio.pos.ui.tableview

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.CUSTOM_CHARGE_NAME_MAX
import com.razstudio.pos.data.parseCustomChargePrice
import com.razstudio.pos.data.local.MenuCategory
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.ui.i18n.AppLanguage
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.theme.scrollPanel

/** One line in the order-entry cart, already resolved to a display name in the active language. */
data class CartLine(
    val menuItemId: String,
    val name: String,
    val unitPrice: Double,
    val quantity: Int,
    /** Optional special instruction for this line (e.g. "no chili"). */
    val note: String? = null,
)

/**
 * Shared new-order entry modal used by admin, staff, and demo. Presents the menu in
 * tabbed categories (Food / Beverages / Side Dishes / Others), a running cart with a
 * receipt-style subtotal + grand total, and a submit button. Item and cart names are
 * shown in [language]; all chrome is localised via [strings].
 */
@Composable
fun OrderEntrySheet(
    tableLabel: String,
    menuItems: List<MenuItem>,
    cart: List<CartLine>,
    language: AppLanguage,
    strings: UiStrings,
    isSubmitting: Boolean,
    onAdd: (item: MenuItem, note: String?, size: String?, unitPrice: Double?, variant: String?) -> Unit,
    /**
     * A hand-typed charge with no menu item behind it — see [CustomChargeButton]. The caller turns
     * the name+price into a cart line with [com.razstudio.pos.data.customChargeMenuItem].
     */
    onAddCustom: (name: String, price: Double) -> Unit,
    onRemove: (Int) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    categoryOrder: List<String> = emptyList(),
) {
    // ── Dismissing this sheet takes a deliberate act ─────────────────────────────────
    // Same guard as OrderDetailSheet, and it matters at least as much here: this sheet holds a cart
    // that exists NOWHERE else until Submit is pressed. A careless downward flick while scrolling
    // the menu threw away everything the cashier had rung up, with a customer waiting.
    //
    // The armed flag is set only by a drag on the handle. Material3 1.3.1 does not consult
    // `confirmValueChange` for swipe-to-dismiss (measured on device), so the real decision is made
    // in `onDismissRequest` below — this is the first of the two gates, not the only one.
    var handleDismissArmed by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || handleDismissArmed },
    )
    val sheetScope = rememberCoroutineScope()
    // Whether the hand-typed charge fields are open. Local scratch state, like the note fields in
    // the menu rows — it has no business surviving the sheet.
    var showCustomCharge by remember { mutableStateOf(false) }

    // Same 640.dp Material default that squeezed OrderDetailSheet into half of the D3 Mini's
    // 1280x800 landscape — see the fuller note there. Widened to match, so the two table-view
    // sheets do not jump between different widths as a cashier moves from taking an order to
    // settling it. Portrait keeps Material's own default.
    val configuration = LocalConfiguration.current
    val sheetMaxWidth = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        (configuration.screenWidthDp * 0.9f).dp
    } else {
        Dp.Unspecified
    }

    ModalBottomSheet(
        // Decided by cause, exactly as in OrderDetailSheet: a drag that was not armed by the handle
        // springs the sheet back instead of discarding the cart. The X button, the scrim tap and back
        // press all still close it — those are unambiguous, and trapping a cashier is not the goal.
        onDismissRequest = {
            val draggedAway = sheetState.targetValue == SheetValue.Hidden
            if (handleDismissArmed || !draggedAway) {
                onDismiss()
            } else {
                sheetScope.launch { sheetState.show() }
            }
        },
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        dragHandle = {
            SheetGrabHandle(
                onDragDownToDismiss = {
                    handleDismissArmed = true
                    sheetScope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                },
            )
        },
    ) {
        // Boxed so the busy state covers the sheet — see [BlockingProgressOverlay]. The spinner
        // inside the Submit button was the only signal that an order was being sent, and on a
        // scrolled cart that button is often not the part of the screen being looked at.
        Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                // The header, CategoryMenuPicker (its own inner LazyColumn is height-capped at
                // 320.dp, so nesting it here is safe), the staged cart lines, and the Submit
                // button all lived in a plain, non-scrolling Column. With enough cart lines
                // (reported: 13+ lines across a few categories) their combined height exceeded
                // the sheet's visible area with no way to reach the rest — Submit Order
                // scrolled off the bottom of the screen and stayed unreachable. This is the
                // exact same class of bug as the customer website's cart sheet (see
                // website/src/components/CartBar.tsx): a list that can grow taller than its
                // container needs its OWN scroll, not an assumption that it'll always fit.
                .verticalScroll(rememberScrollState()),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${strings.newOrder} — $tableLabel",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Weighted so a long café-specific table label ellipsizes instead of pushing
                    // the "+ Customized" and close buttons off the row.
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Same row as the title/close, mirroring where it sits on the Items row in
                // OrderDetailSheet; the fields open full-width below.
                CustomChargeButton(strings = strings) { showCustomCharge = !showCustomCharge }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = strings.commonClose)
                }
            }

            AnimatedVisibility(visible = showCustomCharge) {
                CustomChargeForm(strings = strings, onAdd = onAddCustom)
            }

            CategoryMenuPicker(
                menuItems = menuItems,
                language = language,
                strings = strings,
                onAdd = onAdd,
                categoryOrder = categoryOrder,
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Cart (receipt-style, monospace)
            if (cart.isEmpty()) {
                Text(
                    text = strings.emptyCart,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Text(
                    text = strings.cart,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                cart.forEachIndexed { index, line ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${line.quantity}× ${line.name}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "RM %.2f".format(line.unitPrice * line.quantity),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            IconButton(
                                onClick = { onRemove(index) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = strings.commonDelete,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        // Special-instruction note, indented under its line.
                        if (!line.note.isNullOrBlank()) {
                            Text(
                                text = "   + ${line.note}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                val grand = cart.sumOf { it.unitPrice * it.quantity }
                // Grand total only — see OrderDetailSheet: menu prices, no tax, no service charge,
                // so a subtotal is the same number printed twice.
                ReceiptTotalRow(strings.grandTotal, grand, bold = true)

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onSubmit,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.submitOrder)
                    }
                }
            }
        }

            BlockingProgressOverlay(
                visible = isSubmitting,
                label = strings.processingLabel,
            )
        }
    }
}

/** One tappable menu row. A plain item makes one row; a Small/Medium/Large item makes one
 *  row per defined size, each carrying its own price + size label. */
private data class SizedRow(
    val item: MenuItem,
    val displayName: String,
    val priceText: String,
    val size: String?,
    val unitPrice: Double?,
    /** "HOT" / "COLD" for a Hot/Cold-split item; null otherwise. */
    val variant: String? = null,
)

/** Expand a menu item into its tappable rows: respects priceTierCount (2 or 3) and Hot/Cold. */
private fun sizedRows(item: MenuItem, name: String, marketPriceLabel: String, strings: UiStrings): List<SizedRow> {
    if (item.hasVariablePrice) {
        val slots = if (item.priceTierCount == 2)
            listOf(Triple("S", item.priceOption1, item.coldPriceOption1), Triple("L", item.priceOption3, item.coldPriceOption3))
        else
            listOf(Triple("S", item.priceOption1, item.coldPriceOption1),
                   Triple("M", item.priceOption2, item.coldPriceOption2),
                   Triple("L", item.priceOption3, item.coldPriceOption3))
        val rows = slots.flatMap { (label, hot, cold) ->
            expandHotCold(item, "$name ($label)", label, hot, cold, strings)
        }
        if (rows.isNotEmpty()) return rows
    }
    if (item.hotColdEnabled) return expandHotCold(item, name, null, item.price, item.coldPrice, strings)
    val priceText = if (item.marketPrice) marketPriceLabel else "RM %.2f".format(item.price)
    return listOf(SizedRow(item, name, priceText, null, null, null))
}

/** One row per HOT/COLD variant actually priced (>0); a non-Hot/Cold item yields its one row unchanged. */
private fun expandHotCold(item: MenuItem, baseName: String, size: String?, hot: Double?, cold: Double?, strings: UiStrings): List<SizedRow> {
    if (!item.hotColdEnabled) {
        return if (hot != null && hot > 0) listOf(SizedRow(item, baseName, "RM %.2f".format(hot), size, hot, null)) else emptyList()
    }
    return listOfNotNull(
        hot?.takeIf { it > 0 }?.let { SizedRow(item, "$baseName (${strings.hotVariantLabel})", "RM %.2f".format(it), size, it, "HOT") },
        cold?.takeIf { it > 0 }?.let { SizedRow(item, "$baseName (${strings.coldVariantLabel})", "RM %.2f".format(it), size, it, "COLD") },
    )
}

/**
 * Tabbed category (Food / Beverages / Side Dishes / Others) menu list with tap-to-add rows.
 * Shared by [OrderEntrySheet] (new order on a free table) and [OrderDetailSheet] (adding
 * items directly to an already-occupied table's order).
 */
@Composable
fun CategoryMenuPicker(
    menuItems: List<MenuItem>,
    language: AppLanguage,
    strings: UiStrings,
    onAdd: (item: MenuItem, note: String?, size: String?, unitPrice: Double?, variant: String?) -> Unit,
    modifier: Modifier = Modifier,
    categoryOrder: List<String> = emptyList(),
) {
    // Categories are dynamic — driven by the menu itself — so custom preset categories
    // like "SAYUR" or "MINUMAN (AIS)" appear here, not just the legacy 4.
    // An item appears under every category in allCategories() (primary + "also show in"
    // extras), so a Kerabu dish also tagged "Telur" shows on both tabs — not just its primary.
    // When a saved category order is supplied, tabs follow it (admin's reordering); otherwise
    // they fall back to first-seen order in the menu.
    val categories = remember(menuItems, categoryOrder) {
        val derived = menuItems.flatMap { it.allCategories() }.distinct()
        if (categoryOrder.isEmpty()) derived
        else derived.sortedBy { categoryOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }
    var selectedCategory by remember(categories) { mutableStateOf(categories.firstOrNull() ?: "") }
    // Search is a pinned leftmost tab. It's never the default view — the first real category
    // is. Selecting it shows an empty list until 3+ characters are typed.
    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        if (categories.isNotEmpty()) {
            // Tab 0 is Search; real category tabs follow (offset by 1).
            val selectedIndex = if (searchMode) 0
                else categories.indexOf(selectedCategory).coerceAtLeast(0) + 1
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                edgePadding = 0.dp,
            ) {
                Tab(
                    selected = searchMode,
                    onClick = { searchMode = true },
                    text = { Icon(Icons.Default.Search, contentDescription = strings.searchMenu) },
                )
                categories.forEach { category ->
                    Tab(
                        selected = !searchMode && category == selectedCategory,
                        onClick = { searchMode = false; selectedCategory = category },
                        text = { Text(categoryLabel(category, strings)) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (searchMode) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(strings.searchMenu) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Runs only at 3+ typed characters; empty otherwise (initial state).
            val q = query.trim()
            val results = if (q.length >= 3) {
                val lc = q.lowercase()
                menuItems.filter {
                    language.menuName(it).lowercase().contains(lc) ||
                        it.nameEn.lowercase().contains(lc) ||
                        it.code.lowercase().contains(lc)
                }
            } else {
                emptyList()
            }
            val rows = results.flatMap { sizedRows(it, language.menuName(it), strings.marketPriceMode, strings) }
            // Lighter ground than the sheet around it — the menu list is the scroll area, and it
            // reads as a bounded inset panel rather than melting into the dialog. See the
            // surfaceContainer mapping in ThemePreset.
            Surface(
                color = MaterialTheme.colorScheme.scrollPanel,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { "${it.item.id}_${it.size ?: ""}_${it.variant ?: ""}" }) { row ->
                    MenuEntryRow(
                        name = row.displayName,
                        priceText = row.priceText,
                        addContentDescription = strings.addToCart,
                        noteLabel = strings.noteOptionalLabel,
                        onAdd = { note -> onAdd(row.item, note, row.size, row.unitPrice, row.variant) },
                    )
                }
            }
            }
        } else {
            val itemsInCategory = menuItems.filter { selectedCategory in it.allCategories() }
            val rows = itemsInCategory.flatMap { sizedRows(it, language.menuName(it), strings.marketPriceMode, strings) }
            // Lighter ground than the sheet around it — the menu list is the scroll area, and it
            // reads as a bounded inset panel rather than melting into the dialog. See the
            // surfaceContainer mapping in ThemePreset.
            Surface(
                color = MaterialTheme.colorScheme.scrollPanel,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { "${it.item.id}_${it.size ?: ""}_${it.variant ?: ""}" }) { row ->
                    MenuEntryRow(
                        name = row.displayName,
                        priceText = row.priceText,
                        addContentDescription = strings.addToCart,
                        noteLabel = strings.noteOptionalLabel,
                        onAdd = { note -> onAdd(row.item, note, row.size, row.unitPrice, row.variant) },
                    )
                }
            }
            }
        }
    }
}

/**
 * The sheet's grab handle — and the only gesture that can drag this sheet closed.
 *
 * ### Why it owns the gesture
 *
 * The sheet refuses to go Hidden on its own (see `confirmValueChange` in [OrderDetailSheet]), so a
 * flick anywhere on the bill springs back instead of closing. This handle is what re-enables the
 * intent: `draggable` here consumes the vertical gesture, accumulates it, and on release past
 * [DISMISS_DRAG_THRESHOLD] asks the caller to dismiss. Anything shorter is treated as a fumble and
 * the accumulator resets.
 *
 * The trade-off, stated plainly: the sheet does not follow the finger during the drag the way a
 * native swipe does — it dismisses on release. Making it track the finger means driving the sheet's
 * anchors directly, which Material3 1.3.1 does not expose.
 *
 * ### Why it looks like this
 *
 * Material's default handle is a 32x4dp bar at `onSurfaceVariant` — nearly invisible, and on a sheet
 * you now MUST use it to close, an invisible control is a trap. So: 56x7dp, in `primary` rather than
 * a grey, and wrapped in a 48dp-tall touch target so the finger does not have to find 7dp of bar.
 */
@Composable
fun SheetGrabHandle(onDragDownToDismiss: () -> Unit) {
    var dragged by remember { mutableStateOf(0f) }
    val thresholdPx = with(LocalDensity.current) { DISMISS_DRAG_THRESHOLD.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> dragged += delta },
                onDragStopped = {
                    if (dragged > thresholdPx) onDragDownToDismiss()
                    dragged = 0f
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** How far down the handle must travel before the sheet closes. Short enough to feel like a swipe,
 *  long enough that brushing the handle does not dismiss a bill. */
val DISMISS_DRAG_THRESHOLD = 48.dp

/**
 * ── The "+ Customized" charge ──────────────────────────────────────────────────────────────────
 *
 * A charge the menu does not carry: corkage, a replacement plate, a catering surcharge, a special
 * order priced at the counter. The cashier types a description and a price and it becomes an
 * ordinary bill line.
 *
 * This comes in two pieces on purpose. The button belongs ON the same row as the "+" menu button
 * (it is the same kind of act — put another line on this bill — just sourced from the cashier's head
 * instead of the menu), but that row is a tight header with no room for two text fields. So the
 * caller places [CustomChargeButton] on the row, holds the expanded flag, and renders
 * [CustomChargeForm] below at full width.
 */
@Composable
fun CustomChargeButton(strings: UiStrings, onClick: () -> Unit) {
    SheetActionChip(label = strings.customChargeButton, onClick = onClick)
}

/**
 * The squircle chip used by the small actions on a sheet's header row.
 *
 * Deliberately built from the same parts as AddItemCircleButton — primaryContainer fill,
 * onPrimaryContainer content, 36.dp tall — so a row of them reads as one control strip rather than
 * a button beside a link. A squircle (18.dp corners on a 36.dp box) instead of a full circle only
 * because a label needs the horizontal room.
 *
 * Shared rather than copied so "QR" and "Customized" cannot drift apart: they sit next to each other
 * and any difference in height, radius or fill between them is immediately visible.
 */
@Composable
fun SheetActionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

/**
 * The description + price fields behind [CustomChargeButton]. Wrap in your own `AnimatedVisibility`.
 *
 * The Add action stays disabled until BOTH a description and a parseable positive price are present
 * — a nameless line is unreadable on a receipt, and a zero-priced one is a free lunch. The price
 * field accepts "12", "12.50" and "12,50" (see [parseCustomChargePrice]) and bills at 2dp.
 *
 * [onAdd] receives the typed name and the parsed price; the caller turns that into a cart line with
 * [com.razstudio.pos.data.customChargeMenuItem], which is why this composable knows nothing about
 * carts. The fields clear themselves after a successful add so a second charge can be typed
 * straight away.
 */
@Composable
fun CustomChargeForm(
    strings: UiStrings,
    onAdd: (name: String, price: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }

    val canAdd = name.isNotBlank() && parseCustomChargePrice(priceText) != null

    fun addNow() {
        val price = parseCustomChargePrice(priceText) ?: return
        if (name.isBlank()) return
        onAdd(name.trim().take(CUSTOM_CHARGE_NAME_MAX), price)
        name = ""
        priceText = ""
    }

    Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= CUSTOM_CHARGE_NAME_MAX) name = it },
                label = { Text(strings.customChargeNameLabel) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = priceText,
                // Digits and one separator only: a decimal keyboard still offers "-" on some IMEs,
                // and a cashier never enters a negative charge here.
                onValueChange = { input ->
                    if (input.all { it.isDigit() || it == '.' || it == ',' }) priceText = input
                },
                label = { Text(strings.customChargePriceLabel) },
                prefix = { Text("RM ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(160.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = ::addNow,
            enabled = canAdd,
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(strings.customChargeAddButton)
        }
    }
}

private fun categoryLabel(category: String, strings: UiStrings): String = when (category) {
    MenuCategory.FOOD.name -> strings.catFood
    MenuCategory.BEVERAGES.name -> strings.catBeverages
    MenuCategory.SIDE_DISHES.name -> strings.catSideDishes
    MenuCategory.OTHERS.name -> strings.catOthers
    else -> category   // custom preset categories shown verbatim
}

@Composable
private fun MenuEntryRow(
    name: String,
    priceText: String,
    addContentDescription: String,
    noteLabel: String,
    onAdd: (String?) -> Unit,
) {
    // Each row carries its own optional special-instruction note. The note is captured here
    // and passed to onAdd so it lands on that specific cart line; it clears after adding.
    var showNote by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    fun addNow() {
        onAdd(note.trim().ifBlank { null })
        note = ""
        showNote = false
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = ::addNow),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = priceText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = ::addNow, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, contentDescription = addContentDescription, tint = MaterialTheme.colorScheme.primary)
            }
        }
        // "+ special instruction" toggle, shown under every menu item.
        Text(
            text = "+ $noteLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { showNote = !showNote }
                .padding(top = 2.dp),
        )
        if (showNote) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(noteLabel) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ReceiptTotalRow(label: String, amount: Double, bold: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = "RM %.2f".format(amount),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
