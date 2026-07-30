package com.razstudio.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.razstudio.pos.data.local.MenuCategory
import com.razstudio.pos.ui.components.HoldCountdownOverlay
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.i18n.AppLanguage
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.ManualDineInViewModel

/**
 * Manual Dine-In Entry Screen.
 * Flow: Select table → Browse menu → Add to cart → Submit order (source: STAFF).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualDineInScreen(
    viewModel: ManualDineInViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle success → navigate back
    LaunchedEffect(uiState.orderSubmitted) {
        if (uiState.orderSubmitted) {
            snackbarHostState.showSnackbar(strings.orderSubmittedMsg)
            onBack()
        }
    }

    // Show error
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (uiState.step) {
                            ManualDineInViewModel.Step.SELECT_TABLE -> strings.selectTableTitle
                            ManualDineInViewModel.Step.SELECT_ITEMS -> strings.selectItemsTitle
                            ManualDineInViewModel.Step.CART_REVIEW -> strings.reviewOrderTitle
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (uiState.step) {
                            ManualDineInViewModel.Step.SELECT_TABLE -> onBack()
                            ManualDineInViewModel.Step.SELECT_ITEMS -> viewModel.goToTableSelect()
                            ManualDineInViewModel.Step.CART_REVIEW -> viewModel.goToMenuSelect()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.commonBack)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (uiState.step == ManualDineInViewModel.Step.SELECT_ITEMS && uiState.cartItems.isNotEmpty()) {
                FloatingActionButton(onClick = { viewModel.goToCartReview() }) {
                    BadgedBox(
                        badge = {
                            Badge { Text("${uiState.cartItems.sumOf { it.quantity }}") }
                        }
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = strings.cart)
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (uiState.step) {
                ManualDineInViewModel.Step.SELECT_TABLE -> {
                    TableSelectGrid(
                        tables = uiState.tables,
                        strings = strings,
                        onTableSelected = { tableId -> viewModel.selectTable(tableId) }
                    )
                }
                ManualDineInViewModel.Step.SELECT_ITEMS -> {
                    MenuItemsList(
                        menuItems = uiState.menuItems,
                        cartItems = uiState.cartItems,
                        strings = strings,
                        language = language,
                        onAddItem = { itemId -> viewModel.addToCart(itemId) },
                        onRemoveItem = { itemId -> viewModel.removeFromCart(itemId) },
                        onSetNote = { itemId, note -> viewModel.setItemNote(itemId, note) },
                        onAddSized = { itemId, size, price -> viewModel.addSized(itemId, size, price) },
                        onRemoveSized = { itemId, size -> viewModel.removeSized(itemId, size) }
                    )
                }
                ManualDineInViewModel.Step.CART_REVIEW -> {
                    CartReview(
                        cartItems = uiState.cartItems,
                        menuItems = uiState.menuItems,
                        selectedTableLabel = uiState.selectedTableLabel,
                        isSubmitting = uiState.isSubmitting,
                        strings = strings,
                        language = language,
                        onUpdateNote = { itemId, note -> viewModel.updateNote(itemId, note) },
                        onSubmit = { viewModel.submitOrder() }
                    )
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    // Pre-send hold countdown (admin/staff 3s) — cancellable.
    HoldCountdownOverlay(
        secondsRemaining = uiState.holdRemaining,
        onCancel = { viewModel.cancelSubmitHold() }
    )
}

private val AD_BANNER_HEIGHT = 50.dp

@Composable
private fun TableSelectGrid(
    tables: List<ManualDineInViewModel.TableItem>,
    strings: UiStrings,
    onTableSelected: (String) -> Unit
) {
    if (tables.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.noTablesConfigured, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = 12.dp + AD_BANNER_HEIGHT
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(tables) { table ->
                val bgColor = if (table.isFree) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        // Occupied tables are selectable too — a New Dine-In order on an occupied
                        // table appends to its existing order rather than being blocked.
                        .clickable { onTableSelected(table.id) }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = table.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (table.isFree) strings.freeLabel else strings.occupiedLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(AD_BANNER_HEIGHT),
            factory = { context ->
                AdView(context).apply {
                    setAdSize(AdSize.BANNER)
                    adUnitId = "ca-app-pub-8323843054100465/6611158260"
                    loadAd(AdRequest.Builder().build())
                }
            }
        )
    }
}

/** Localized section label for a stored category string (enum name, e.g. "FOOD"). */
private fun categoryLabel(category: String, strings: UiStrings): String =
    when (category.uppercase()) {
        MenuCategory.FOOD.name -> strings.catFood
        MenuCategory.BEVERAGES.name -> strings.catBeverages
        MenuCategory.SIDE_DISHES.name -> strings.catSideDishes
        MenuCategory.OTHERS.name -> strings.catOthers
        else -> category   // custom preset categories shown verbatim
    }

@Composable
private fun MenuItemsList(
    menuItems: List<ManualDineInViewModel.MenuItemDisplay>,
    cartItems: List<ManualDineInViewModel.CartItem>,
    strings: UiStrings,
    language: AppLanguage,
    onAddItem: (String) -> Unit,
    onRemoveItem: (String) -> Unit,
    onSetNote: (String, String) -> Unit = { _, _ -> },
    onAddSized: (String, String, Double) -> Unit = { _, _, _ -> },
    onRemoveSized: (String, String) -> Unit = { _, _ -> }
) {
    if (menuItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.noMenuItemsAvailable, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    // Menu search — filters the list by any-language name match as you type.
    var query by remember { mutableStateOf("") }
    val visibleItems = menuItems.filter { it.matches(query) }

    // Group into separate category sections. Categories are dynamic (driven by the menu),
    // so custom preset categories like "SAYUR" appear in the order they occur in the menu.
    // An item appears under each of its allCategories() (primary + "also show in" extras),
    // so a dish tagged into a second category shows in both sections here too.
    val grouped = buildMap<String, MutableList<ManualDineInViewModel.MenuItemDisplay>> {
        for (mi in visibleItems) {
            val cats = mi.categories.ifEmpty { listOf(mi.category) }
            for (cat in cats) getOrPut(cat.uppercase()) { mutableListOf() }.add(mi)
        }
    }
    val orderedCategories = visibleItems
        .flatMap { it.categories.ifEmpty { listOf(it.category) } }
        .map { it.uppercase() }.distinct()
        .filter { grouped[it]?.isNotEmpty() == true }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(strings.searchMenu) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        orderedCategories.forEach { category ->
            val itemsInCategory = grouped[category].orEmpty()

            item(key = "header_$category") {
                Text(
                    text = categoryLabel(category, strings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            items(itemsInCategory, key = { it.id }) { item ->
                // The plain (no-size) quantity — the stepper only applies to non-variable items.
                val cartQty = cartItems.find { it.menuItemId == item.id && it.size == null }?.quantity ?: 0
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Photo thumbnail — fixed square sized to the row height so it
                            // never stretches the row; cropped to fill and rounded.
                            if (item.imageUrl.isNotBlank()) {
                                AsyncImage(
                                    model = item.imageUrl,
                                    contentDescription = item.localizedName(language),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.localizedName(language),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                // Single price line only for non-variable items; S/M/L items
                                // show their prices on the per-size rows below.
                                if (!item.hasVariablePrice) {
                                    Text(
                                        text = if (item.marketPrice) strings.marketPriceMode
                                               else "RM ${String.format("%.2f", item.price)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            if (!item.hasVariablePrice) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (cartQty > 0) {
                                        IconButton(onClick = { onRemoveItem(item.id) }) {
                                            Icon(Icons.Default.Remove, contentDescription = strings.commonDelete)
                                        }
                                        Text(
                                            text = "$cartQty",
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.width(24.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    IconButton(onClick = { onAddItem(item.id) }) {
                                        Icon(Icons.Default.Add, contentDescription = strings.addToCart)
                                    }
                                }
                            }
                        }

                        if (item.hasVariablePrice) {
                            // One stepper row per Small/Medium/Large size — each is its own cart line.
                            val sizes = listOf("S" to item.priceOption1, "M" to item.priceOption2, "L" to item.priceOption3)
                            sizes.forEach { (label, price) ->
                                if (price != null && price > 0) {
                                    val sizeQty = cartItems.find { it.menuItemId == item.id && it.size == label }?.quantity ?: 0
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$label · RM ${String.format("%.2f", price)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (sizeQty > 0) {
                                            IconButton(onClick = { onRemoveSized(item.id, label) }) {
                                                Icon(Icons.Default.Remove, contentDescription = strings.commonDelete)
                                            }
                                            Text(
                                                text = "$sizeQty",
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.width(24.dp),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                        IconButton(onClick = { onAddSized(item.id, label, price) }) {
                                            Icon(Icons.Default.Add, contentDescription = strings.addToCart)
                                        }
                                    }
                                }
                            }
                        } else {
                            // "+ special instruction" under each menu item (adds/updates its note).
                            MenuItemNoteField(
                                existingNote = cartItems.find { it.menuItemId == item.id && it.size == null }?.note ?: "",
                                label = strings.noteOptionalLabel,
                                onNoteChange = { onSetNote(item.id, it) },
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun CartReview(
    cartItems: List<ManualDineInViewModel.CartItem>,
    menuItems: List<ManualDineInViewModel.MenuItemDisplay>,
    selectedTableLabel: String,
    isSubmitting: Boolean,
    strings: UiStrings,
    language: AppLanguage,
    onUpdateNote: (String, String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "${strings.tableLabelPrefix}: $selectedTableLabel",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(cartItems) { cartItem ->
                val menuItem = menuItems.find { it.id == cartItem.menuItemId }
                if (menuItem != null) {
                    CartItemRow(
                        item = cartItem,
                        menuItem = menuItem,
                        strings = strings,
                        language = language,
                        onUpdateNote = { note -> onUpdateNote(cartItem.menuItemId, note) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        // Total
        val total = cartItems.sumOf { cartItem ->
            val base = menuItems.find { it.id == cartItem.menuItemId }?.price ?: 0.0
            val price = cartItem.unitPrice ?: base
            price * cartItem.quantity
        }
        Text(
            text = "${strings.totalLabelPrefix}: RM ${String.format("%.2f", total)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSubmit,
            enabled = !isSubmitting && cartItems.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(strings.submitOrder)
        }
    }
}

@Composable
private fun CartItemRow(
    item: ManualDineInViewModel.CartItem,
    menuItem: ManualDineInViewModel.MenuItemDisplay,
    strings: UiStrings,
    language: AppLanguage,
    onUpdateNote: (String) -> Unit
) {
    var noteText by remember(item.menuItemId, item.size) { mutableStateOf(item.note ?: "") }
    val unitPrice = item.unitPrice ?: menuItem.price
    val sizeSuffix = item.size?.let { " ($it)" } ?: ""

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${item.quantity}x ${menuItem.localizedName(language)}$sizeSuffix",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "RM ${String.format("%.2f", unitPrice * item.quantity)}",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        // The note editor is only shown for plain (no-size) lines — a size line's note would
        // otherwise be keyed only by item id and could land on the wrong size.
        if (item.size == null) {
            OutlinedTextField(
                value = noteText,
                onValueChange = { newNote ->
                    noteText = newNote
                    onUpdateNote(newNote)
                },
                label = { Text(strings.noteOptionalLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

/**
 * Collapsible "+ special instruction" field shown under a menu item in the New Dine-In list.
 * Starts open when the item already has a note; typing pushes the note up via [onNoteChange]
 * (which adds the item to the cart if needed).
 */
@Composable
private fun MenuItemNoteField(
    existingNote: String,
    label: String,
    onNoteChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(existingNote.isNotBlank()) }
    var text by remember { mutableStateOf(existingNote) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "+ $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(top = 4.dp)
        )
        if (expanded) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onNoteChange(it) },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}
