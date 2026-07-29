package com.warungtomyam.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.warungtomyam.pos.data.local.MenuItem
import com.warungtomyam.pos.ui.i18n.AppLanguage
import com.warungtomyam.pos.ui.i18n.UiStrings

/**
 * Daily Availability popup dialog, shown once on the admin's first login of the day.
 * Covers two independent triggers per item: [MenuItem.askMeDaily] (availability toggle
 * + optional plain price override) and variable-price items with daily prompting on
 * (radio pick of that day's active preset out of the 3 admin-defined prices). Dismissing
 * via the X in the title bar (or the Skip button) leaves everything unchanged.
 */
@Composable
fun DailyAvailabilityDialog(
    items: List<MenuItem>,
    strings: UiStrings,
    language: AppLanguage,
    onConfirm: (updates: List<DailyItemUpdate>) -> Unit,
    onDismiss: () -> Unit
) {
    // Track availability state per item
    val availabilityState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            items.forEach { put(it.id, it.available) }
        }
    }
    // Track optional plain-price overrides per item (non-variable-price items only)
    val priceState = remember {
        mutableStateMapOf<String, String>().apply {
            items.forEach { put(it.id, it.price.toString()) }
        }
    }
    // Track today's active preset (1/2/3) per variable-price item
    val activeOptionState = remember {
        mutableStateMapOf<String, Int>().apply {
            items.forEach { item ->
                if (item.hasVariablePrice) {
                    put(
                        item.id,
                        when (item.price) {
                            item.priceOption2 -> 2
                            item.priceOption3 -> 3
                            else -> 1
                        }
                    )
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.dailyAvailabilityTitle,
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = strings.skipButton)
                }
            }
        },
        text = {
            Column {
                Text(
                    text = strings.dailyAvailabilityDesc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(items) { item ->
                        DailyItemRow(
                            item = item,
                            strings = strings,
                            language = language,
                            isAvailable = availabilityState[item.id] ?: item.available,
                            priceText = priceState[item.id] ?: item.price.toString(),
                            activeOption = activeOptionState[item.id] ?: 1,
                            onAvailabilityChanged = { available ->
                                availabilityState[item.id] = available
                            },
                            onPriceChanged = { price ->
                                priceState[item.id] = price
                            },
                            onActiveOptionChanged = { option ->
                                activeOptionState[item.id] = option
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updates = items.map { item ->
                    val price = if (item.hasVariablePrice) {
                        when (activeOptionState[item.id] ?: 1) {
                            2 -> item.priceOption2
                            3 -> item.priceOption3
                            else -> item.priceOption1
                        }
                    } else {
                        priceState[item.id]?.toDoubleOrNull()
                    }
                    DailyItemUpdate(
                        itemId = item.id,
                        available = availabilityState[item.id] ?: item.available,
                        price = price
                    )
                }
                onConfirm(updates)
            }) {
                Text(strings.commonConfirm)
            }
        }
    )
}

@Composable
private fun DailyItemRow(
    item: MenuItem,
    strings: UiStrings,
    language: AppLanguage,
    isAvailable: Boolean,
    priceText: String,
    activeOption: Int,
    onAvailabilityChanged: (Boolean) -> Unit,
    onPriceChanged: (String) -> Unit,
    onActiveOptionChanged: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.menuName(item),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = isAvailable,
                onCheckedChange = onAvailabilityChanged
            )
        }
        if (isAvailable) {
            if (item.hasVariablePrice) {
                Text(
                    text = strings.todaysPriceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                PriceOptionChoice(1, item.priceOption1, activeOption, onActiveOptionChanged)
                PriceOptionChoice(2, item.priceOption2, activeOption, onActiveOptionChanged)
                PriceOptionChoice(3, item.priceOption3, activeOption, onActiveOptionChanged)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = "RM",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = onPriceChanged,
                        modifier = Modifier.width(100.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceOptionChoice(
    index: Int,
    price: Double?,
    activeOption: Int,
    onActiveOptionChanged: (Int) -> Unit
) {
    if (price == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = activeOption == index,
            onClick = { onActiveOptionChanged(index) }
        )
        Text(
            text = "RM %.2f".format(price),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

data class DailyItemUpdate(
    val itemId: String,
    val available: Boolean,
    val price: Double?
)
