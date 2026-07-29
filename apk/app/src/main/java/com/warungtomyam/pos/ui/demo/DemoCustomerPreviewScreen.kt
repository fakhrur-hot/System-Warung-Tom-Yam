package com.warungtomyam.pos.ui.demo

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.warungtomyam.pos.data.local.MenuCategory
import com.warungtomyam.pos.data.local.MenuItem
import com.warungtomyam.pos.ui.i18n.AppLanguage
import com.warungtomyam.pos.ui.i18n.LanguageButton
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import kotlinx.coroutines.launch

/**
 * Customer-side page preview in Demo Mode.
 * Shows what the customer ordering page would look like — menu items grouped by category,
 * with prices, availability status, and a simulated cart with expandable bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoCustomerPreviewScreen(
    demoViewModel: DemoViewModel,
    languageViewModel: LanguageViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val allMenuItems by demoViewModel.allMenuItems.collectAsState()
    val tables by demoViewModel.tables.collectAsState()
    // Customers pick their own language on the real ordering site; preview that here too,
    // using the same shared language selector as the rest of the app (Requirement: customer
    // language options in the demo preview, not just admin/staff screens).
    val language by languageViewModel.language.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val cart = remember { mutableStateMapOf<String, Int>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showCartSheet by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    val categories = MenuCategory.entries
    val selectedCategory = categories[selectedTabIndex]

    val filteredItems = allMenuItems.filter { item ->
        val itemCategory = MenuCategory.entries.find { it.name == item.category }
            ?: MenuCategory.OTHERS
        itemCategory == selectedCategory
    }

    val cartTotal = cart.entries.sumOf { (itemId, qty) ->
        val item = allMenuItems.find { it.id == itemId }
        (item?.price ?: 0.0) * qty
    }
    val cartItemCount = cart.values.sum()

    val demoTable = tables.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customer Preview") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = { LanguageButton() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            DemoCartBar(
                itemCount = cartItemCount,
                total = cartTotal,
                onExpand = { showCartSheet = true },
                onPlaceOrder = { showConfirmDialog = true },
                isOrderEnabled = cartItemCount > 0
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Table info banner
            if (demoTable != null) {
                Text(
                    text = "\uD83D\uDCCD Table: ${demoTable.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Category tabs
            TabRow(selectedTabIndex = selectedTabIndex) {
                categories.forEachIndexed { index, category ->
                    Tab(
                        selected = index == selectedTabIndex,
                        onClick = { selectedTabIndex = index },
                        text = { Text(category.displayName) }
                    )
                }
            }

            // Menu items list
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No items in ${selectedCategory.displayName}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        CustomerMenuItemCard(
                            item = item,
                            displayName = language.menuName(item),
                            quantity = cart[item.id] ?: 0,
                            onAdd = {
                                if (item.available) {
                                    cart[item.id] = (cart[item.id] ?: 0) + 1
                                }
                            },
                            onRemove = {
                                val current = cart[item.id] ?: 0
                                if (current <= 1) cart.remove(item.id)
                                else cart[item.id] = current - 1
                            }
                        )
                    }
                }
            }
        }
    }

    // Expandable cart BottomSheet
    if (showCartSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCartSheet = false },
            sheetState = sheetState
        ) {
            CartSheetContent(
                cart = cart,
                allMenuItems = allMenuItems,
                language = language,
                cartTotal = cartTotal,
                onIncrement = { itemId ->
                    cart[itemId] = (cart[itemId] ?: 0) + 1
                },
                onDecrement = { itemId ->
                    val current = cart[itemId] ?: 0
                    if (current <= 1) cart.remove(itemId)
                    else cart[itemId] = current - 1
                }
            )
        }
    }

    // Confirmation dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Confirm order and send to kitchen?") },
            text = null,
            confirmButton = {
                TextButton(onClick = {
                    val tableName = demoTable?.label ?: "Demo"
                    scope.launch {
                        snackbarHostState.showSnackbar("Demo: Order placed for $tableName")
                    }
                    cart.clear()
                    showConfirmDialog = false
                    showCartSheet = false
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showConfirmDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF44336)
                    )
                ) {
                    Text("No")
                }
            }
        )
    }
}

/**
 * Always-visible bottom cart bar with drag handle indicator.
 */
@Composable
private fun DemoCartBar(
    itemCount: Int,
    total: Double,
    onExpand: () -> Unit,
    onPlaceOrder: () -> Unit,
    isOrderEnabled: Boolean
) {
    Surface(
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag handle indicator (three-dot pill)
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.LightGray)
                    .clickable { onExpand() }
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Cart summary row + Place Order button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.clickable { onExpand() }) {
                    Text(
                        text = "$itemCount item${if (itemCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "RM %.2f".format(total),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Button(
                    onClick = onPlaceOrder,
                    enabled = isOrderEnabled,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = Color.LightGray,
                        disabledContentColor = Color.Gray
                    )
                ) {
                    Text("Place Order")
                }
            }
        }
    }
}

/**
 * Content for the expanded cart BottomSheet.
 * Groups items by category and shows +/- controls.
 */
@Composable
private fun CartSheetContent(
    cart: Map<String, Int>,
    allMenuItems: List<MenuItem>,
    language: AppLanguage,
    cartTotal: Double,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit
) {
    val cartItemsByCategory = cart.entries
        .mapNotNull { (itemId, qty) ->
            allMenuItems.find { it.id == itemId }?.let { item ->
                Triple(item, itemId, qty)
            }
        }
        .groupBy { (item, _, _) ->
            MenuCategory.entries.find { it.name == item.category } ?: MenuCategory.OTHERS
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Your Cart",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (cart.isEmpty()) {
            Text(
                text = "Cart is empty",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                textAlign = TextAlign.Center
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Iterate categories in defined order
                val orderedCategories = MenuCategory.entries.filter { it in cartItemsByCategory.keys }
                orderedCategories.forEach { category ->
                    val items = cartItemsByCategory[category] ?: return@forEach

                    item(key = "header_${category.name}") {
                        Text(
                            text = category.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }

                    items(items, key = { it.second }) { (menuItem, itemId, qty) ->
                        CartItemRow(
                            item = menuItem,
                            displayName = language.menuName(menuItem),
                            quantity = qty,
                            onIncrement = { onIncrement(itemId) },
                            onDecrement = { onDecrement(itemId) }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Total row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "RM %.2f".format(cartTotal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Single cart item row with name, quantity, unit price, subtotal, and +/- controls.
 */
@Composable
private fun CartItemRow(
    item: MenuItem,
    displayName: String,
    quantity: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    val subtotal = item.price * quantity
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Item name and price details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${quantity}× RM %.2f".format(item.price) + " = RM %.2f".format(subtotal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // +/- controls
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrement) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Decrease",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = "$quantity",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onIncrement) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Increase",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun CustomerMenuItemCard(
    item: MenuItem,
    displayName: String,
    quantity: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.available)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Item info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (item.available)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "RM %.2f".format(item.price),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.available)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                if (!item.available) {
                    Text(
                        text = "Unavailable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Quantity controls
            if (item.available) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (quantity > 0) {
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }
                        Text(
                            text = "$quantity",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(24.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Default.Add, contentDescription = "Add to cart")
                    }
                }
            }
        }
    }
}
