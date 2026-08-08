package com.razstudio.opsapp.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.opsapp.data.ApiResult
import com.razstudio.opsapp.data.api.BrandingDto
import com.razstudio.opsapp.data.api.CafeLocationDto
import com.razstudio.opsapp.data.api.MenuCategoryDto
import com.razstudio.opsapp.data.api.MenuItemDto
import com.razstudio.opsapp.data.api.OperatorApiClient
import com.razstudio.opsapp.data.api.TableDto
import com.razstudio.opsapp.ui.util.QrCodeUtil
import com.razstudio.opsapp.ui.util.TableQrShare
import com.razstudio.opsapp.ui.viewmodels.CafeProfileViewModel
import com.razstudio.opsapp.ui.viewmodels.ShellTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Cafe_Profile_Shell (Requirement 5).
 *
 * Bottom navigation with four destinations: Profile, Menu, Tables, Table QR.
 * Top-bar overflow carries only "Café owner key (view/share)" and "Disconnect this café".
 * Observes [CafeProfileViewModel.revoked] to show an access-revoked blocker (Requirement 6.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CafeProfileShellScreen(
    cafeId: String,
    onBack: () -> Unit,
    viewModel: CafeProfileViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val cafe by viewModel.cafe.collectAsState()
    val apiClient by viewModel.apiClient.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val revoked by viewModel.revoked.collectAsState()

    LaunchedEffect(cafeId) {
        viewModel.loadCafe(cafeId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cafe?.cafeName ?: "Café") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ShellOverflowMenu(
                        hasOwnerKey = !cafe?.ownerKeyUrl.isNullOrBlank(),
                        onShareOwnerKey = {
                            if (!viewModel.shareOwnerKey(context)) {
                                Toast.makeText(context, "Owner key not available", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDisconnect = { viewModel.disconnect(); onBack() },
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                ShellTab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (revoked) {
                RevokedBlocker(
                    cafeName = cafe?.cafeName ?: "this café",
                    onDisconnect = { viewModel.disconnect(); onBack() },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                when (selectedTab) {
                    ShellTab.PROFILE -> ProfileTab(apiClient)
                    ShellTab.MENU -> MenuTab(apiClient)
                    ShellTab.TABLES -> TablesTab(apiClient)
                    ShellTab.TABLE_QR -> TableQrTab(apiClient)
                }
            }
        }
    }
}

@Composable
private fun ShellOverflowMenu(
    hasOwnerKey: Boolean,
    onShareOwnerKey: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "More options")
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        DropdownMenuItem(
            text = { Text("Café owner key (view/share)") },
            enabled = hasOwnerKey,
            onClick = {
                expanded = false
                onShareOwnerKey()
            },
        )
        DropdownMenuItem(
            text = { Text("Disconnect this café") },
            onClick = {
                expanded = false
                showDisconnectDialog = true
            },
        )
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect this café?") },
            text = { Text("This removes the local credential only. The café backend is unaffected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectDialog = false
                        onDisconnect()
                    },
                ) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun RevokedBlocker(
    cafeName: String,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Access revoked for $cafeName",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "An admin revoked this device's access. Remove the stale connection to continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onDisconnect) {
            Text("Disconnect")
        }
    }
}

// ── Profile Tab ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileTab(apiClient: OperatorApiClient?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var branding by remember { mutableStateOf<BrandingDto?>(null) }
    var location by remember { mutableStateOf<CafeLocationDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var cafeName by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }

    fun load() {
        if (apiClient == null) return
        scope.launch {
            loading = true
            error = null
            val b = apiClient.getBranding()
            val l = apiClient.getCafeLocation()
            when {
                b is ApiResult.Success -> {
                    branding = b.data
                    cafeName = b.data.cafeName
                }
                b is ApiResult.Error -> error = b.message
                b is ApiResult.NetworkError -> error = b.message
            }
            when (l) {
                is ApiResult.Success -> {
                    location = l.data
                    lat = l.data.latitude.toString()
                    lng = l.data.longitude.toString()
                    radius = l.data.radiusMeters.toString()
                }
                is ApiResult.Error -> if (l.code != "NOT_CONFIGURED") error = l.message
                is ApiResult.NetworkError -> error = l.message
            }
            loading = false
        }
    }

    LaunchedEffect(apiClient) { load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (loading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            SectionHeader("Branding")
            OutlinedTextField(
                value = cafeName,
                onValueChange = { cafeName = it },
                label = { Text("Café name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Logo changes are not supported in this screen — use the website admin panel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            SectionHeader("Café Location")
            OutlinedTextField(
                value = lat,
                onValueChange = { lat = it },
                label = { Text("Latitude") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = lng,
                onValueChange = { lng = it },
                label = { Text("Longitude") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = radius,
                onValueChange = { radius = it },
                label = { Text("Geofence radius (meters)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val latVal = lat.toDoubleOrNull()
                    val lngVal = lng.toDoubleOrNull()
                    val radiusVal = radius.toIntOrNull()
                    if (cafeName.isBlank() || latVal == null || lngVal == null || radiusVal == null) {
                        error = "Fill all fields with valid values."
                        return@Button
                    }
                    scope.launch {
                        saving = true
                        error = null
                        val bResult = apiClient?.updateBranding(BrandingDto(cafeName = cafeName))
                        val lResult = apiClient?.updateCafeLocation(
                            CafeLocationDto(latitude = latVal, longitude = lngVal, radiusMeters = radiusVal)
                        )
                        saving = false
                        when {
                            bResult is ApiResult.Success && lResult is ApiResult.Success -> {
                                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                            }
                            bResult is ApiResult.Error -> error = bResult.message
                            lResult is ApiResult.Error -> error = lResult.message
                            bResult is ApiResult.NetworkError -> error = bResult.message
                            lResult is ApiResult.NetworkError -> error = lResult.message
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Save Profile")
            }
        }
    }
}

// ── Menu Tab ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun MenuTab(apiClient: OperatorApiClient?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<MenuItemDto>>(emptyList()) }
    var categories by remember { mutableStateOf<List<MenuCategoryDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf<MenuItemDto?>(null) }

    fun load() {
        if (apiClient == null) return
        scope.launch {
            loading = true
            error = null
            when (val result = apiClient.getMenu()) {
                is ApiResult.Success -> {
                    items = result.data.items
                    categories = result.data.categories
                }
                is ApiResult.Error -> error = result.message
                is ApiResult.NetworkError -> error = result.message
            }
            loading = false
        }
    }

    LaunchedEffect(apiClient) { load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader("Menu")
            IconButton(onClick = { load() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            ErrorView(error = error!!, onRetry = { load() })
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    MenuItemCard(
                        item = item,
                        onEdit = { showEditor = item },
                        onDelete = {
                            scope.launch {
                                when (val result = apiClient?.deleteMenuItem(item.id)) {
                                    is ApiResult.Success -> load()
                                    is ApiResult.Error -> error = result.message
                                    is ApiResult.NetworkError -> error = result.message
                                    else -> {}
                                }
                            }
                        },
                    )
                }
            }

            Button(
                onClick = {
                    showEditor = MenuItemDto(
                        id = UUID.randomUUID().toString(),
                        category = categories.firstOrNull()?.name ?: "",
                        price = 0.0,
                        available = true,
                        nameEn = "",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Menu Item")
            }
        }
    }

    showEditor?.let { item ->
        MenuItemEditorDialog(
            item = item,
            categories = categories,
            onDismiss = { showEditor = null },
            onSave = { updated ->
                showEditor = null
                scope.launch {
                    val newItems = if (item.id in items.map { it.id }) {
                        items.map { if (it.id == updated.id) updated else it }
                    } else {
                        items + updated
                    }
                    when (val result = apiClient?.upsertMenuItem(newItems, categories)) {
                        is ApiResult.Success -> load()
                        is ApiResult.Error -> error = result.message
                        is ApiResult.NetworkError -> error = result.message
                        else -> {}
                    }
                }
            },
        )
    }
}

@Composable
private fun MenuItemCard(
    item: MenuItemDto,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.nameEn.takeIf { it.isNotBlank() } ?: "(unnamed)",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${item.category} — RM ${"%.2f".format(item.price)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!item.available) {
                    Text(
                        text = "Unavailable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun MenuItemEditorDialog(
    item: MenuItemDto,
    categories: List<MenuCategoryDto>,
    onDismiss: () -> Unit,
    onSave: (MenuItemDto) -> Unit,
) {
    var nameEn by remember { mutableStateOf(item.nameEn) }
    var category by remember { mutableStateOf(item.category) }
    var price by remember { mutableStateOf(item.price.toString()) }
    var available by remember { mutableStateOf(item.available) }
    var code by remember { mutableStateOf(item.code) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item.nameEn.isBlank()) "New Menu Item" else "Edit Menu Item") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it },
                    label = { Text("Name (English)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (categories.isNotEmpty()) {
                    Text(
                        text = "Known categories: ${categories.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price (RM)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Available")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = available,
                        onCheckedChange = { available = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val priceVal = price.toDoubleOrNull() ?: 0.0
                    onSave(
                        item.copy(
                            nameEn = nameEn,
                            category = category,
                            price = priceVal,
                            available = available,
                            code = code,
                        )
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Tables Tab ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun TablesTab(apiClient: OperatorApiClient?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tables by remember { mutableStateOf<List<TableDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf<TableDto?>(null) }

    fun load() {
        if (apiClient == null) return
        scope.launch {
            loading = true
            error = null
            when (val result = apiClient.getTables()) {
                is ApiResult.Success -> tables = result.data
                is ApiResult.Error -> error = result.message
                is ApiResult.NetworkError -> error = result.message
            }
            loading = false
        }
    }

    LaunchedEffect(apiClient) { load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader("Tables")
            IconButton(onClick = { load() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            ErrorView(error = error!!, onRetry = { load() })
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tables, key = { it.id }) { table ->
                    TableCard(
                        table = table,
                        onEdit = { showEditor = table },
                        onDelete = {
                            scope.launch {
                                when (val result = apiClient?.deleteTable(table.id)) {
                                    is ApiResult.Success -> load()
                                    is ApiResult.Error -> error = result.message
                                    is ApiResult.NetworkError -> error = result.message
                                    else -> {}
                                }
                            }
                        },
                    )
                }
            }

            Button(
                onClick = {
                    showEditor = TableDto(
                        id = UUID.randomUUID().toString(),
                        displayName = "",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Table")
            }
        }
    }

    showEditor?.let { table ->
        TableEditorDialog(
            table = table,
            onDismiss = { showEditor = null },
            onSave = { updated ->
                showEditor = null
                scope.launch {
                    val newTables = if (table.id in tables.map { it.id }) {
                        tables.map { if (it.id == updated.id) updated else it }
                    } else {
                        tables + updated
                    }
                    when (val result = apiClient?.upsertTable(newTables)) {
                        is ApiResult.Success -> load()
                        is ApiResult.Error -> error = result.message
                        is ApiResult.NetworkError -> error = result.message
                        else -> {}
                    }
                }
            },
        )
    }
}

@Composable
private fun TableCard(
    table: TableDto,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = table.displayName.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = table.displayName,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!table.qrToken.isNullOrBlank()) {
                    Text(
                        text = "QR token set",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun TableEditorDialog(
    table: TableDto,
    onDismiss: () -> Unit,
    onSave: (TableDto) -> Unit,
) {
    var name by remember { mutableStateOf(table.displayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (table.displayName.isBlank()) "New Table" else "Edit Table") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(table.copy(displayName = name)) },
                enabled = name.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Table QR Tab ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun TableQrTab(apiClient: OperatorApiClient?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tables by remember { mutableStateOf<List<TableDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTable by remember { mutableStateOf<TableDto?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var generating by remember { mutableStateOf(false) }

    fun load() {
        if (apiClient == null) return
        scope.launch {
            loading = true
            error = null
            when (val result = apiClient.getTables()) {
                is ApiResult.Success -> tables = result.data
                is ApiResult.Error -> error = result.message
                is ApiResult.NetworkError -> error = result.message
            }
            loading = false
        }
    }

    LaunchedEffect(apiClient) { load() }

    fun generateQr(table: TableDto) {
        selectedTable = table
        bitmap = null
        val qrToken = table.qrToken
        if (qrToken.isNullOrBlank()) {
            Toast.makeText(context, "This table has no QR token yet — save it first.", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            generating = true
            bitmap = withContext(Dispatchers.IO) {
                QrCodeUtil.encode(qrToken, sizePx = 1024)
            }
            generating = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        SectionHeader("Table QR")
        Text(
            text = "Select a table to generate its QR code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (loading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            ErrorView(error = error!!, onRetry = { load() })
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tables, key = { it.id }) { table ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { generateQr(table) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = table.displayName,
                                modifier = Modifier.weight(1f),
                            )
                            if (selectedTable?.id == table.id && generating) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }

            selectedTable?.let { table ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = table.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        bitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "QR code for ${table.displayName}",
                                modifier = Modifier.size(220.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = TableQrShare.buildShareIntent(
                                            context,
                                            table.displayName,
                                            table.qrToken ?: "",
                                        )
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Couldn't share QR", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Share")
                            }
                        } ?: run {
                            if (generating) {
                                CircularProgressIndicator()
                            } else {
                                Text("Tap a table to generate its QR code")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ErrorView(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
