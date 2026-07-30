@file:OptIn(ExperimentalMaterial3Api::class)

package com.razstudio.pos.ui.tableview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.local.MenuCategory
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.ui.i18n.AppLanguage
import com.razstudio.pos.ui.i18n.UiStrings

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
    onAdd: (item: MenuItem, note: String?, size: String?, unitPrice: Double?) -> Unit,
    onRemove: (Int) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    categoryOrder: List<String> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
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
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = strings.commonClose)
                }
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
                ReceiptTotalRow(strings.subtotal, grand, bold = false)
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
)

/** Expand a menu item into its tappable rows: 3 for a variable-price (S/M/L) item, else 1. */
private fun sizedRows(item: MenuItem, name: String, marketPriceLabel: String): List<SizedRow> {
    if (item.hasVariablePrice) {
        val opts = listOf("S" to item.priceOption1, "M" to item.priceOption2, "L" to item.priceOption3)
        val rows = opts.mapNotNull { (label, price) ->
            if (price != null && price > 0)
                SizedRow(item, "$name ($label)", "RM %.2f".format(price), label, price)
            else null
        }
        if (rows.isNotEmpty()) return rows
    }
    val priceText = if (item.marketPrice) marketPriceLabel else "RM %.2f".format(item.price)
    return listOf(SizedRow(item, name, priceText, null, null))
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
    onAdd: (item: MenuItem, note: String?, size: String?, unitPrice: Double?) -> Unit,
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
            val rows = results.flatMap { sizedRows(it, language.menuName(it), strings.marketPriceMode) }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { "${it.item.id}_${it.size ?: ""}" }) { row ->
                    MenuEntryRow(
                        name = row.displayName,
                        priceText = row.priceText,
                        addContentDescription = strings.addToCart,
                        noteLabel = strings.noteOptionalLabel,
                        onAdd = { note -> onAdd(row.item, note, row.size, row.unitPrice) },
                    )
                }
            }
        } else {
            val itemsInCategory = menuItems.filter { selectedCategory in it.allCategories() }
            val rows = itemsInCategory.flatMap { sizedRows(it, language.menuName(it), strings.marketPriceMode) }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { "${it.item.id}_${it.size ?: ""}" }) { row ->
                    MenuEntryRow(
                        name = row.displayName,
                        priceText = row.priceText,
                        addContentDescription = strings.addToCart,
                        noteLabel = strings.noteOptionalLabel,
                        onAdd = { note -> onAdd(row.item, note, row.size, row.unitPrice) },
                    )
                }
            }
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
