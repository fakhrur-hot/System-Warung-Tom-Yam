package com.warungtomyam.pos.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.warungtomyam.pos.data.local.MenuCategory
import com.warungtomyam.pos.data.local.MenuItem
import com.warungtomyam.pos.data.local.PrinterConfig
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.UiStrings
import com.warungtomyam.pos.ui.i18n.uiStrings
import com.warungtomyam.pos.ui.viewmodels.MenuViewModel
import coil.compose.AsyncImage
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.ui.window.Dialog

/**
 * Menu management screen showing all items grouped by category tabs.
 * Supports add, edit, delete, and quick availability toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuManagementScreen(
    viewModel: MenuViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onAddItem: (String) -> Unit,
    onEditItem: (String) -> Unit,
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val snackbarHostState = remember { SnackbarHostState() }
    var itemToDelete by remember { mutableStateOf<MenuItem?>(null) }
    var selectedImageUrl by remember { mutableStateOf<String?>(null) }
    // Category being edited (translations + printer routing); null when the dialog is closed.
    var editingCategory by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
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
                },
                actions = {
                    // Edit the currently-selected category: translations + which printer prints it.
                    val current = uiState.effectiveSelectedCategory
                    if (current.isNotBlank()) {
                        IconButton(onClick = { editingCategory = current }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit category")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onAddItem(uiState.effectiveSelectedCategory) }
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
            // Sync indicator
            if (uiState.isSyncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Category tabs — dynamic ordered category names. Custom names render verbatim;
            // the 4 legacy enum names keep their localized labels.
            val categories = uiState.categories
            val selectedIndex = categories.indexOf(uiState.effectiveSelectedCategory).coerceAtLeast(0)

            if (categories.isNotEmpty()) {
                androidx.compose.material3.ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    edgePadding = 0.dp
                ) {
                    categories.forEachIndexed { index, category ->
                        Tab(
                            selected = index == selectedIndex,
                            onClick = { viewModel.selectCategory(category) },
                            text = { Text(categoryTabLabel(category, strings)) }
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val items = uiState.filteredItems
                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${strings.noItemsInCategory} ${categoryTabLabel(uiState.effectiveSelectedCategory, strings)}",
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
                        items(items, key = { it.id }) { item ->
                            MenuItemCard(
                                item = item,
                                strings = strings,
                                onToggleAvailability = { viewModel.toggleAvailability(item.id) },
                                onEdit = { onEditItem(item.id) },
                                onDelete = { itemToDelete = item },
                                onImageClick = { url -> selectedImageUrl = url }
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(strings.deleteItemTitle) },
            text = { Text("${strings.deleteItemConfirm} \"${item.nameEn}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteItem(item.id)
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

    // Category editor: per-language display names + which printer prints this category.
    editingCategory?.let { cat ->
        CategoryEditorDialog(
            category = cat,
            translations = uiState.categoryTranslations[cat] ?: emptyMap(),
            kitchenPrinters = uiState.kitchenPrinters,
            currentPrinterId = viewModel.printerIdForCategory(cat),
            strings = strings,
            onDismiss = { editingCategory = null },
            onSave = { labels, printerId ->
                viewModel.saveCategory(cat, labels, printerId)
                editingCategory = null
            }
        )
    }

    // Full-screen image overlay (1:1) when selected
    selectedImageUrl?.let { url ->
        Dialog(onDismissRequest = { selectedImageUrl = null }) {
            Box(modifier = Modifier.padding(24.dp)) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
            }
        }
    }
}

/**
 * Tab label for a dynamic category NAME: custom names (e.g. "SAYUR") render verbatim, while
 * the 4 legacy enum names keep their localized labels for backward compatibility.
 */
private fun categoryTabLabel(name: String, strings: UiStrings): String = when (name) {
    MenuCategory.FOOD.name -> strings.catFood
    MenuCategory.BEVERAGES.name -> strings.catBeverages
    MenuCategory.SIDE_DISHES.name -> strings.catSideDishes
    MenuCategory.OTHERS.name -> strings.catOthers
    else -> name
}

/**
 * Dialog to edit a menu category: its per-language display labels (what the customer web
 * shows for the category tab) and which kitchen printer its order slips route to.
 */
@Composable
private fun CategoryEditorDialog(
    category: String,
    translations: Map<String, String>,
    kitchenPrinters: List<PrinterConfig>,
    currentPrinterId: String?,
    strings: UiStrings,
    onDismiss: () -> Unit,
    onSave: (labels: Map<String, String>, printerId: String?) -> Unit,
) {
    var en by remember { mutableStateOf(translations["en"] ?: "") }
    var bm by remember { mutableStateOf(translations["bm"] ?: "") }
    var zh by remember { mutableStateOf(translations["zh"] ?: "") }
    var ta by remember { mutableStateOf(translations["ta"] ?: "") }
    var th by remember { mutableStateOf(translations["th"] ?: "") }
    var printerId by remember { mutableStateOf(currentPrinterId) }
    var menuOpen by remember { mutableStateOf(false) }

    val selectedPrinterName = kitchenPrinters.firstOrNull { it.id == printerId }?.name ?: "Default (catch-all)"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category: $category") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Display name by language", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(en, { en = it }, label = { Text("English") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bm, { bm = it }, label = { Text("Bahasa Melayu") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(zh, { zh = it }, label = { Text("中文") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ta, { ta = it }, label = { Text("தமிழ்") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(th, { th = it }, label = { Text("ไทย") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(4.dp))
                Text("Print kitchen slips to", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedPrinterName)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Default (catch-all kitchen)") },
                            onClick = { printerId = null; menuOpen = false }
                        )
                        kitchenPrinters.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = { printerId = p.id; menuOpen = false }
                            )
                        }
                    }
                }
                if (kitchenPrinters.isEmpty()) {
                    Text(
                        "No kitchen printers yet — add them under Printers settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val labels = buildMap {
                    if (en.isNotBlank()) put("en", en.trim())
                    if (bm.isNotBlank()) put("bm", bm.trim())
                    if (zh.isNotBlank()) put("zh", zh.trim())
                    if (ta.isNotBlank()) put("ta", ta.trim())
                    if (th.isNotBlank()) put("th", th.trim())
                }
                onSave(labels, printerId)
            }) { Text(strings.commonSave) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.commonCancel) } }
    )
}

@Composable
private fun MenuItemCard(
    item: MenuItem,
    strings: UiStrings,
    onToggleAvailability: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onImageClick: (String) -> Unit
) {
    val cardColor = if (item.available)
        MaterialTheme.colorScheme.surface
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    // Edit + Delete are hidden by default and revealed by swiping the row content left.
    // The availability Switch stays pinned on the far right, outside the swipe area.
    val revealPx = with(LocalDensity.current) { 96.dp.toPx() } // two 48dp icon buttons
    val offsetX = remember(item.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Swipeable region (everything except the toggle).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds()
            ) {
                // Hidden actions, revealed on the right as the content slides left.
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        scope.launch { offsetX.animateTo(0f) }
                        onEdit()
                    }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = strings.commonEdit,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = {
                        scope.launch { offsetX.animateTo(0f) }
                        onDelete()
                    }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = strings.commonDelete,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Foreground content — opaque so it covers the actions when closed; slides
                // horizontally under the drag, snapping open/closed on release.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                        .background(cardColor)
                        .pointerInput(item.id) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, delta ->
                                    change.consume()
                                    scope.launch {
                                        offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f))
                                    }
                                },
                                onDragEnd = {
                                    val target = if (offsetX.value < -revealPx / 2) -revealPx else 0f
                                    scope.launch { offsetX.animateTo(target) }
                                }
                            )
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.imageUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = item.nameEn,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onImageClick(item.imageUrl) }
                        )
                    }

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
                        val priceLine = buildString {
                            if (item.code.isNotBlank()) append("${item.code} · ")
                            append(if (item.marketPrice) strings.marketPriceMode else "RM %.2f".format(item.price))
                        }
                        Text(
                            text = priceLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Always-visible availability toggle — pinned far right, excluded from the swipe.
            Switch(
                checked = item.available,
                onCheckedChange = { onToggleAvailability() },
                modifier = Modifier.padding(end = 12.dp)
            )
        }
    }
}
