package com.razstudio.pos.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.local.MenuCategory
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.navigation.MenuItemMode
import com.razstudio.pos.ui.viewmodels.MenuViewModel
import com.razstudio.pos.util.ImageUtils
import kotlinx.coroutines.launch
import java.util.UUID
import coil.compose.AsyncImage

/**
 * Add/Edit menu item screen. The [mode] makes the intent explicit:
 * - [MenuItemMode.ADD]: [category] is pre-selected from navigation (the selected tab).
 * - [MenuItemMode.EDIT]: fields are pre-filled from the existing item loaded by [itemId],
 *   including its stored category — no category is passed in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMenuItemScreen(
    mode: MenuItemMode,
    category: String? = null,
    itemId: String? = null,
    viewModel: MenuViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val isEditMode = mode == MenuItemMode.EDIT
    val existingItem = if (isEditMode) uiState.items.find { it.id == itemId } else null

    // A new item's id is generated up-front so a picked image can be uploaded to a
    // stable path before the item itself is saved.
    val effectiveItemId = remember { itemId ?: UUID.randomUUID().toString() }

    // Form state
    var selectedCategory by remember { mutableStateOf(category ?: "") }
    var selectedExtraCategories by remember {
        mutableStateOf(
            existingItem?.extraCategories?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
                ?: emptySet()
        )
    }
    var nameEn by remember { mutableStateOf(existingItem?.nameEn ?: "") }
    var code by remember { mutableStateOf(existingItem?.code ?: "") }
    var marketPrice by remember { mutableStateOf(existingItem?.marketPrice ?: false) }
    var priceText by remember { mutableStateOf(existingItem?.price?.let { "%.2f".format(it) } ?: "") }
    var hasVariablePrice by remember { mutableStateOf(existingItem?.hasVariablePrice ?: false) }
    var variablePriceDailyPrompt by remember { mutableStateOf(existingItem?.variablePriceDailyPrompt ?: false) }
    var priceOption1Text by remember { mutableStateOf(existingItem?.priceOption1?.let { "%.2f".format(it) } ?: "") }
    var priceOption2Text by remember { mutableStateOf(existingItem?.priceOption2?.let { "%.2f".format(it) } ?: "") }
    var priceOption3Text by remember { mutableStateOf(existingItem?.priceOption3?.let { "%.2f".format(it) } ?: "") }
    var activeOptionIndex by remember {
        mutableStateOf(
            when (existingItem?.price) {
                existingItem?.priceOption2 -> 2
                existingItem?.priceOption3 -> 3
                else -> 1
            }
        )
    }
    var askMeDaily by remember { mutableStateOf(existingItem?.askMeDaily ?: false) }
    var doNotTranslate by remember { mutableStateOf(existingItem?.doNotTranslate ?: false) }
    var imageUrl by remember { mutableStateOf(existingItem?.imageUrl ?: "") }
    var imagePath by remember { mutableStateOf(existingItem?.imagePath ?: "") }
    var isUploadingImage by remember { mutableStateOf(false) }
    var nameBm by remember { mutableStateOf(existingItem?.nameBm ?: "") }
    var nameZh by remember { mutableStateOf(existingItem?.nameZh ?: "") }
    var nameTa by remember { mutableStateOf(existingItem?.nameTa ?: "") }
    var nameTh by remember { mutableStateOf(existingItem?.nameTh ?: "") }
    var showCategoryPicker by remember { mutableStateOf(mode == MenuItemMode.ADD && category == null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isUploadingImage = true
        coroutineScope.launch {
            val base64 = ImageUtils.prepareThumbnailBase64(context, uri)
            if (base64 == null) {
                isUploadingImage = false
                snackbarHostState.showSnackbar(strings.networkError)
                return@launch
            }
            when (val result = viewModel.uploadImage(effectiveItemId, base64, imagePath.ifBlank { null })) {
                is ApiResult.Success -> {
                    imageUrl = result.data.imageUrl
                    imagePath = result.data.path
                }
                is ApiResult.Error -> snackbarHostState.showSnackbar(result.message)
                is ApiResult.NetworkError -> snackbarHostState.showSnackbar(result.message)
            }
            isUploadingImage = false
        }
    }

    // Update form when existing item loads
    LaunchedEffect(existingItem) {
        existingItem?.let {
            nameEn = it.nameEn
            code = it.code
            marketPrice = it.marketPrice
            priceText = "%.2f".format(it.price)
            hasVariablePrice = it.hasVariablePrice
            variablePriceDailyPrompt = it.variablePriceDailyPrompt
            priceOption1Text = it.priceOption1?.let { p -> "%.2f".format(p) } ?: ""
            priceOption2Text = it.priceOption2?.let { p -> "%.2f".format(p) } ?: ""
            priceOption3Text = it.priceOption3?.let { p -> "%.2f".format(p) } ?: ""
            activeOptionIndex = when (it.price) {
                it.priceOption2 -> 2
                it.priceOption3 -> 3
                else -> 1
            }
            askMeDaily = it.askMeDaily
            doNotTranslate = it.doNotTranslate
            imageUrl = it.imageUrl
            imagePath = it.imagePath
            nameBm = it.nameBm
            nameZh = it.nameZh
            nameTa = it.nameTa
            nameTh = it.nameTh
            selectedCategory = it.category
            selectedExtraCategories = it.extraCategories.split(",").map { c -> c.trim() }.filter { c -> c.isNotBlank() }.toSet()
        }
    }

    val title = if (isEditMode) strings.editItemTitle else strings.addItemTitle

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.backLabel)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // Step 1: Category picker (only shown if no category pre-selected and not edit mode)
        if (showCategoryPicker) {
            CategoryPickerContent(
                modifier = Modifier.padding(padding),
                strings = strings,
                categories = uiState.categories,
                onCategorySelected = { name ->
                    selectedCategory = name
                    showCategoryPicker = false
                }
            )
        } else {
            // Step 2: Item form. Bahasa Malaysia is the mandatory, authored name — it's
            // what a Malaysian stall owner actually types first, not a translated English
            // description. English/中文/தமிழ்/ไทย are optional manual overrides, tucked
            // behind the "+ Bahasa Lain" expander directly below the BM field.
            ItemFormContent(
                modifier = Modifier.padding(padding),
                strings = strings,
                selectedCategory = selectedCategory,
                allCategories = uiState.categories,
                selectedExtraCategories = selectedExtraCategories,
                onToggleExtraCategory = { cat ->
                    selectedExtraCategories =
                        if (selectedExtraCategories.contains(cat)) selectedExtraCategories - cat
                        else selectedExtraCategories + cat
                },
                code = code,
                onCodeChange = { code = it },
                marketPrice = marketPrice,
                onMarketPriceChange = { marketPrice = it },
                nameBm = nameBm,
                onNameBmChange = { nameBm = it },
                nameEn = nameEn,
                onNameEnChange = { nameEn = it },
                nameZh = nameZh,
                onNameZhChange = { nameZh = it },
                nameTa = nameTa,
                onNameTaChange = { nameTa = it },
                nameTh = nameTh,
                onNameThChange = { nameTh = it },
                priceText = priceText,
                onPriceChange = { priceText = it },
                hasVariablePrice = hasVariablePrice,
                onHasVariablePriceChange = { hasVariablePrice = it },
                variablePriceDailyPrompt = variablePriceDailyPrompt,
                onVariablePriceDailyPromptChange = { variablePriceDailyPrompt = it },
                priceOption1Text = priceOption1Text,
                onPriceOption1Change = { priceOption1Text = it },
                priceOption2Text = priceOption2Text,
                onPriceOption2Change = { priceOption2Text = it },
                priceOption3Text = priceOption3Text,
                onPriceOption3Change = { priceOption3Text = it },
                activeOptionIndex = activeOptionIndex,
                onActiveOptionIndexChange = { activeOptionIndex = it },
                askMeDaily = askMeDaily,
                onAskMeDailyChange = { askMeDaily = it },
                doNotTranslate = doNotTranslate,
                onDoNotTranslateChange = { doNotTranslate = it },
                imageUrl = imageUrl,
                isUploadingImage = isUploadingImage,
                onPickImage = {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                isEditMode = isEditMode,
                onSave = {
                    if (nameBm.isBlank()) return@ItemFormContent

                    val effectivePrice: Double
                    val option1: Double?
                    val option2: Double?
                    val option3: Double?

                    if (marketPrice) {
                        // Market-price item: price decided at the counter, no numeric price required.
                        effectivePrice = 0.0
                        option1 = null
                        option2 = null
                        option3 = null
                    } else if (hasVariablePrice) {
                        option1 = priceOption1Text.toDoubleOrNull()
                        option2 = priceOption2Text.toDoubleOrNull()
                        option3 = priceOption3Text.toDoubleOrNull()
                        if (option1 == null || option1 <= 0) return@ItemFormContent
                        if (option2 == null || option2 <= 0) return@ItemFormContent
                        if (option3 == null || option3 <= 0) return@ItemFormContent
                        // No single "active" price — Small/Medium/Large are all offered when
                        // ordering. Store Small as the item's base price for snapshots/fallback.
                        effectivePrice = option1
                    } else {
                        val price = priceText.toDoubleOrNull() ?: 0.0
                        if (price <= 0) return@ItemFormContent
                        effectivePrice = price
                        option1 = null
                        option2 = null
                        option3 = null
                    }

                    // English is still the field Room/the backend require to be non-blank;
                    // mirror the BM name into it when the admin hasn't typed an override,
                    // so the mandatory field really is BM from the user's perspective.
                    val effectiveNameEn = nameEn.trim().ifBlank { nameBm.trim() }

                    if (isEditMode && itemId != null) {
                        viewModel.updateItem(
                            id = itemId,
                            category = selectedCategory,
                            nameEn = effectiveNameEn,
                            price = effectivePrice,
                            askMeDaily = askMeDaily,
                            doNotTranslate = doNotTranslate,
                            code = code.trim(),
                            marketPrice = marketPrice,
                            imageUrl = imageUrl.trim(),
                            imagePath = imagePath,
                            hasVariablePrice = hasVariablePrice,
                            variablePriceDailyPrompt = false,
                            priceOption1 = option1,
                            priceOption2 = option2,
                            priceOption3 = option3,
                            nameBm = nameBm.trim(),
                            nameZh = nameZh.trim(),
                            nameTa = nameTa.trim(),
                            nameTh = nameTh.trim(),
                            extraCategories = selectedExtraCategories.filter { it != selectedCategory }.joinToString(",")
                        )
                    } else {
                        viewModel.addItem(
                            id = effectiveItemId,
                            category = selectedCategory,
                            nameEn = effectiveNameEn,
                            price = effectivePrice,
                            askMeDaily = askMeDaily,
                            doNotTranslate = doNotTranslate,
                            code = code.trim(),
                            marketPrice = marketPrice,
                            imageUrl = imageUrl.trim(),
                            imagePath = imagePath,
                            hasVariablePrice = hasVariablePrice,
                            variablePriceDailyPrompt = false,
                            priceOption1 = option1,
                            priceOption2 = option2,
                            priceOption3 = option3,
                            nameBm = nameBm.trim(),
                            nameZh = nameZh.trim(),
                            nameTa = nameTa.trim(),
                            nameTh = nameTh.trim(),
                            extraCategories = selectedExtraCategories.filter { it != selectedCategory }.joinToString(",")
                        )
                    }
                    onBack()
                },
                onChangeCategory = { showCategoryPicker = true }
            )
        }
    }
}

@Composable
private fun CategoryPickerContent(
    modifier: Modifier = Modifier,
    strings: UiStrings,
    categories: List<String>,
    onCategorySelected: (String) -> Unit
) {
    var newCategory by remember { mutableStateOf("") }
    val options = categories.ifEmpty { MenuCategory.entries.map { it.name } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = strings.selectCategory,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))

        options.forEach { name ->
            Button(
                onClick = { onCategorySelected(name) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(
                    text = categoryDisplayLabel(name, strings),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Create a brand-new category inline: typing a name and confirming uses it as the
        // item's category (persisted to the ordered category store on save).
        OutlinedTextField(
            value = newCategory,
            onValueChange = { newCategory = it },
            label = { Text(strings.newCategoryLabel) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onCategorySelected(newCategory.trim()) },
            enabled = newCategory.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(strings.newCategoryLabel, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Display label for a category NAME: custom names render verbatim; the 4 legacy enum names
 * keep their localized labels for backward compatibility.
 */
private fun categoryDisplayLabel(name: String, strings: UiStrings): String = when (name) {
    MenuCategory.FOOD.name -> strings.catFood
    MenuCategory.BEVERAGES.name -> strings.catBeverages
    MenuCategory.SIDE_DISHES.name -> strings.catSideDishes
    MenuCategory.OTHERS.name -> strings.catOthers
    else -> name
}

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
@Composable
private fun ItemFormContent(
    modifier: Modifier = Modifier,
    strings: UiStrings,
    selectedCategory: String,
    code: String,
    onCodeChange: (String) -> Unit,
    marketPrice: Boolean,
    onMarketPriceChange: (Boolean) -> Unit,
    nameBm: String,
    onNameBmChange: (String) -> Unit,
    nameEn: String,
    onNameEnChange: (String) -> Unit,
    nameZh: String,
    onNameZhChange: (String) -> Unit,
    nameTa: String,
    onNameTaChange: (String) -> Unit,
    nameTh: String,
    onNameThChange: (String) -> Unit,
    priceText: String,
    onPriceChange: (String) -> Unit,
    hasVariablePrice: Boolean,
    onHasVariablePriceChange: (Boolean) -> Unit,
    variablePriceDailyPrompt: Boolean,
    onVariablePriceDailyPromptChange: (Boolean) -> Unit,
    priceOption1Text: String,
    onPriceOption1Change: (String) -> Unit,
    priceOption2Text: String,
    onPriceOption2Change: (String) -> Unit,
    priceOption3Text: String,
    onPriceOption3Change: (String) -> Unit,
    activeOptionIndex: Int,
    onActiveOptionIndexChange: (Int) -> Unit,
    askMeDaily: Boolean,
    onAskMeDailyChange: (Boolean) -> Unit,
    doNotTranslate: Boolean,
    onDoNotTranslateChange: (Boolean) -> Unit,
    imageUrl: String,
    isUploadingImage: Boolean,
    onPickImage: () -> Unit,
    isEditMode: Boolean,
    onSave: () -> Unit,
    onChangeCategory: () -> Unit,
    allCategories: List<String> = emptyList(),
    selectedExtraCategories: Set<String> = emptySet(),
    onToggleExtraCategory: (String) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    var showOtherLanguages by remember { mutableStateOf(false) }
    val categoryDisplay = categoryDisplayLabel(selectedCategory, strings)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Category display with change option
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = strings.categoryLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = categoryDisplay,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                // Category is editable in BOTH add and edit modes.
                Button(onClick = onChangeCategory) {
                    Text(strings.changeCategory)
                }
            }
        }

        // Additional categories — the item also appears on each selected category's page.
        if (allCategories.isNotEmpty()) {
            Text(
                text = "Also show in (optional)",
                style = MaterialTheme.typography.labelLarge
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                allCategories.filter { it != selectedCategory }.forEach { cat ->
                    androidx.compose.material3.FilterChip(
                        selected = selectedExtraCategories.contains(cat),
                        onClick = { onToggleExtraCategory(cat) },
                        label = { Text(categoryDisplayLabel(cat, strings)) }
                    )
                }
            }
        }

        // Optional short item code (e.g. "S01", "TY3") shown on slips/receipts.
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text(strings.codeFieldLabel) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Name in Bahasa Malaysia (required) — the authored source name, matching how
        // a Malaysian stall owner actually names a dish, not a translated description.
        OutlinedTextField(
            value = nameBm,
            onValueChange = onNameBmChange,
            label = { Text("${strings.menuNameFieldLabel} *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // "+ Bahasa Lain" — nested expander directly below the mandatory name field.
        // Only relevant when translation is wanted at all (doNotTranslate off).
        if (!doNotTranslate) {
            Text(
                text = strings.otherLanguagesToggle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { showOtherLanguages = !showOtherLanguages }
                    .padding(vertical = 4.dp)
            )

            if (showOtherLanguages) {
                Text(
                    text = strings.otherLanguagesHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = nameEn,
                    onValueChange = onNameEnChange,
                    label = { Text(strings.nameEnglishLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = nameZh,
                    onValueChange = onNameZhChange,
                    label = { Text(strings.nameChineseLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = nameTa,
                    onValueChange = onNameTaChange,
                    label = { Text(strings.nameTamilLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = nameTh,
                    onValueChange = onNameThChange,
                    label = { Text(strings.nameThaiLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        // Pricing type: Single Price (plain, default), Multiple Price (3 admin-editable
        // presets), or Market Price (price decided at the counter — no fixed value).
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = strings.pricingModeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = !hasVariablePrice && !marketPrice,
                    onClick = { onMarketPriceChange(false); onHasVariablePriceChange(false) }
                )
                Text(
                    text = strings.singlePriceLabel,
                    modifier = Modifier
                        .clickable { onMarketPriceChange(false); onHasVariablePriceChange(false) }
                        .padding(end = 16.dp)
                )
                RadioButton(
                    selected = hasVariablePrice && !marketPrice,
                    onClick = { onMarketPriceChange(false); onHasVariablePriceChange(true) }
                )
                Text(
                    text = strings.multiplePriceLabel,
                    modifier = Modifier.clickable { onMarketPriceChange(false); onHasVariablePriceChange(true) }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Market price is decided daily, so selecting it auto-enables "Ask me daily"
                // (the daily popup then prompts for today's price). The admin can still turn
                // the switch back off if they prefer to set the price manually in Menu Mgmt.
                val selectMarket = {
                    onMarketPriceChange(true)
                    onHasVariablePriceChange(false)
                    onAskMeDailyChange(true)
                }
                RadioButton(
                    selected = marketPrice,
                    onClick = selectMarket
                )
                Text(
                    text = strings.marketPriceMode,
                    modifier = Modifier.clickable(onClick = selectMarket)
                )
            }
        }

        if (marketPrice) {
            // No numeric price for market-price items.
            Text(
                text = strings.marketPriceMode,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!hasVariablePrice) {
            // Single Price
            OutlinedTextField(
                value = priceText,
                onValueChange = { value ->
                    // Allow only valid decimal input
                    if (value.isEmpty() || value.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                        onPriceChange(value)
                    }
                },
                label = { Text("${strings.priceLabel} *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                prefix = { Text("RM ") }
            )
        } else {
            // Multiple Price: three fixed sizes — Small / Medium / Large. All three are shown
            // as separate options when ordering; there's no single "active" price.
            Text(
                text = "Small / Medium / Large — all three appear when ordering",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SizePriceField("Small (S)", priceOption1Text, onPriceOption1Change)
            SizePriceField("Medium (M)", priceOption2Text, onPriceOption2Change)
            SizePriceField("Large (L)", priceOption3Text, onPriceOption3Change)
        }

        // Toggles
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(strings.askMeDailyLabel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    strings.askMeDailyDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = askMeDaily, onCheckedChange = onAskMeDailyChange)
        }

        // Photo: pick from gallery, client-resized (5:4, 320px) and uploaded automatically.
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = strings.addItemTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Box {
            OutlinedButton(
                onClick = onPickImage,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isUploadingImage
            ) {
                if (isUploadingImage) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (imageUrl.isBlank()) strings.pickPhotoButton else strings.changePhotoButton)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(strings.doNotTranslateLabel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    strings.doNotTranslateDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = doNotTranslate, onCheckedChange = onDoNotTranslateChange)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Save button. Market-price items need no numeric price.
        val priceValid = when {
            marketPrice -> true
            hasVariablePrice ->
                (priceOption1Text.toDoubleOrNull() ?: 0.0) > 0 &&
                    (priceOption2Text.toDoubleOrNull() ?: 0.0) > 0 &&
                    (priceOption3Text.toDoubleOrNull() ?: 0.0) > 0
            else -> (priceText.toDoubleOrNull() ?: 0.0) > 0
        }
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = nameBm.isNotBlank() && priceValid
        ) {
            Text(
                text = if (isEditMode) strings.updateItemButton else strings.addItemButton,
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** One size's price field (Small/Medium/Large) — no "active" selection; all sizes are offered. */
@Composable
private fun SizePriceField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            if (v.isEmpty() || v.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                onValueChange(v)
            }
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        prefix = { Text("RM ") }
    )
}
