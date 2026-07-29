package com.warungtomyam.pos.ui.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.warungtomyam.pos.data.local.MenuCategory
import com.warungtomyam.pos.data.local.MenuItem
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.UiStrings
import com.warungtomyam.pos.ui.i18n.uiStrings
import java.util.UUID

/**
 * Demo mode menu management screen backed by DemoViewModel.
 * Provides full CRUD for menu items within the demo session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoMenuManagementScreen(
    demoViewModel: DemoViewModel = hiltViewModel(),
    onBack: () -> Unit,
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val allMenuItems by demoViewModel.allMenuItems.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var itemToEdit by remember { mutableStateOf<MenuItem?>(null) }
    var itemToDelete by remember { mutableStateOf<MenuItem?>(null) }

    val categories = MenuCategory.entries
    val selectedCategory = categories[selectedTabIndex]

    val filteredItems = allMenuItems.filter { item ->
        val itemCategory = MenuCategory.entries.find { it.name == item.category }
            ?: MenuCategory.OTHERS
        itemCategory == selectedCategory
    }

    // Collect UI errors from ViewModel
    LaunchedEffect(Unit) {
        demoViewModel.uiError.collect { error ->
            snackbarHostState.showSnackbar(error)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.menuManagementTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.commonBack)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = strings.addItemButton)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Category tabs
            TabRow(selectedTabIndex = selectedTabIndex) {
                categories.forEachIndexed { index, category ->
                    Tab(
                        selected = index == selectedTabIndex,
                        onClick = { selectedTabIndex = index },
                        text = { Text(categoryLabel(category, strings)) }
                    )
                }
            }

            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${strings.noItemsInCategory} ${categoryLabel(selectedCategory, strings)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = strings.tapPlusToAdd,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        DemoMenuItemCard(
                            item = item,
                            strings = strings,
                            onToggleAvailability = {
                                demoViewModel.toggleMenuItemAvailability(item)
                            },
                            onEdit = { itemToEdit = item },
                            onDelete = { itemToDelete = item }
                        )
                    }
                }
            }
        }
    }

    // Add Item Dialog
    if (showAddDialog) {
        MenuItemFormDialog(
            title = strings.addItemTitle,
            strings = strings,
            initialCategory = selectedCategory,
            onDismiss = { showAddDialog = false },
            onConfirm = { nameEn, nameBm, price, category ->
                val newItem = MenuItem(
                    id = UUID.randomUUID().toString(),
                    category = category.name,
                    price = price,
                    available = true,
                    askMeDaily = false,
                    nameEn = nameEn,
                    nameBm = nameBm
                )
                demoViewModel.addMenuItem(newItem)
                showAddDialog = false
            }
        )
    }

    // Edit Item Dialog
    itemToEdit?.let { item ->
        MenuItemFormDialog(
            title = strings.editItemTitle,
            strings = strings,
            initialNameEn = item.nameEn,
            initialNameBm = item.nameBm,
            initialPrice = item.price,
            initialCategory = MenuCategory.entries.find { it.name == item.category }
                ?: MenuCategory.OTHERS,
            onDismiss = { itemToEdit = null },
            onConfirm = { nameEn, nameBm, price, category ->
                val updatedItem = item.copy(
                    nameEn = nameEn,
                    nameBm = nameBm,
                    price = price,
                    category = category.name
                )
                demoViewModel.editMenuItem(updatedItem)
                itemToEdit = null
            }
        )
    }

    // Delete Confirmation Dialog
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(strings.deleteItemTitle) },
            text = { Text("${strings.deleteItemConfirm} \"${item.nameEn}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        demoViewModel.deleteMenuItem(item.id)
                        itemToDelete = null
                    }
                ) {
                    Text(strings.commonDelete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text(strings.commonCancel)
                }
            }
        )
    }
}

/** Maps a fixed [MenuCategory] to its localized display label. */
private fun categoryLabel(category: MenuCategory, strings: UiStrings): String = when (category) {
    MenuCategory.FOOD -> strings.catFood
    MenuCategory.BEVERAGES -> strings.catBeverages
    MenuCategory.SIDE_DISHES -> strings.catSideDishes
    MenuCategory.OTHERS -> strings.catOthers
}

@Composable
private fun DemoMenuItemCard(
    item: MenuItem,
    strings: UiStrings,
    onToggleAvailability: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.nameEn,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.askMeDaily) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ) {
                            Text(strings.dailyBadge, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "RM %.2f".format(item.price),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Actions
            Switch(
                checked = item.available,
                onCheckedChange = { onToggleAvailability() },
                modifier = Modifier.size(48.dp)
            )

            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = strings.commonEdit,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = strings.commonDelete,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuItemFormDialog(
    title: String,
    strings: UiStrings,
    initialNameEn: String = "",
    initialNameBm: String = "",
    initialPrice: Double = 0.0,
    initialCategory: MenuCategory = MenuCategory.FOOD,
    onDismiss: () -> Unit,
    onConfirm: (nameEn: String, nameBm: String, price: Double, category: MenuCategory) -> Unit
) {
    var nameEn by remember { mutableStateOf(initialNameEn) }
    var nameBm by remember { mutableStateOf(initialNameBm) }
    var priceText by remember {
        mutableStateOf(if (initialPrice > 0.0) "%.2f".format(initialPrice) else "")
    }
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var nameEnError by remember { mutableStateOf(false) }
    var priceError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = nameEn,
                    onValueChange = {
                        nameEn = it
                        nameEnError = false
                    },
                    label = { Text("${strings.nameEnglishLabel} *") },
                    isError = nameEnError,
                    supportingText = if (nameEnError) {
                        { Text("Name is required") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = nameBm,
                    onValueChange = { nameBm = it },
                    label = { Text(strings.menuNameFieldLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = priceText,
                    onValueChange = {
                        priceText = it
                        priceError = false
                    },
                    label = { Text("${strings.priceLabel} *") },
                    isError = priceError,
                    supportingText = if (priceError) {
                        { Text("Valid price is required") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = !categoryExpanded }
                ) {
                    OutlinedTextField(
                        value = categoryLabel(selectedCategory, strings),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(strings.categoryLabel) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        MenuCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(categoryLabel(category, strings)) },
                                onClick = {
                                    selectedCategory = category
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedPrice = priceText.toDoubleOrNull()
                    val isNameValid = nameEn.isNotBlank()
                    val isPriceValid = parsedPrice != null && parsedPrice > 0

                    nameEnError = !isNameValid
                    priceError = !isPriceValid

                    if (isNameValid && isPriceValid) {
                        onConfirm(nameEn.trim(), nameBm.trim(), parsedPrice!!, selectedCategory)
                    }
                }
            ) {
                Text(strings.commonSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.commonCancel)
            }
        }
    )
}
