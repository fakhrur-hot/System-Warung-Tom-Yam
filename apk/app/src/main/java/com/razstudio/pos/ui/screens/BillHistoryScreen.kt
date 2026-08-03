package com.razstudio.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.BillHistoryViewModel
import com.razstudio.pos.ui.viewmodels.REPRINT_SENT
import com.razstudio.pos.util.MenuName

/**
 * Bill History — search past bills and reprint one.
 *
 * The single search box covers bill number, table, item name and payment method, because a café
 * asking "which bill was that" remembers different things each time. Searching runs in SQL (see
 * `OrderDao.searchBills`), so this stays fast on a café with years of trade behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillHistoryScreen(
    onBack: () -> Unit,
    viewModel: BillHistoryViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val s = uiStrings(language)
    val snackbar = remember { SnackbarHostState() }

    // The ViewModel never holds English text (UiStringsCompletenessTest enforces it), so it emits
    // a sentinel and the localized string is resolved here.
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showMessage(if (msg == REPRINT_SENT) s.billReprintSent else msg)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.orderHistoryTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.commonBack)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(s.billSearchHint) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = s.commonCancel)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )

            when {
                state.isLoading && state.bills.isEmpty() -> CenteredBox { CircularProgressIndicator() }

                state.bills.isEmpty() && state.hasSearched -> CenteredBox {
                    Text(
                        text = if (state.query.isBlank()) s.billHistoryEmpty
                               else s.billSearchNoResults.format(state.query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.bills, key = { it.order.id }) { row ->
                        BillCard(
                            row = row,
                            paidLabel = s.billStatusPaid,
                            cancelledLabel = s.billStatusCancelled,
                            noTableLabel = s.billNoTable,
                            itemsLabel = s.billItemsCount,
                            onClick = { viewModel.openBill(row) }
                        )
                    }
                    if (state.truncated) {
                        item {
                            OutlinedButton(
                                onClick = viewModel::loadMore,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(s.billLoadMore) }
                        }
                    }
                }
            }
        }
    }

    state.selected?.let { detail ->
        val order = detail.order
        AlertDialog(
            onDismissRequest = viewModel::closeBill,
            title = {
                Text(s.billDetailTitle.format(order.orderNumber?.toString() ?: order.id.take(6)))
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = detail.printedAt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = detail.tableLabel.ifBlank { s.billNoTable },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    detail.items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${item.quantity}x ${MenuName.display(item.nameSnapshot)}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "RM %.2f".format(item.unitPriceSnapshot * item.quantity),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        // A kitchen note is part of what happened on this bill; without it a
                        // reprint and the original can look inexplicably different.
                        item.note?.takeIf { it.isNotBlank() }?.let { note ->
                            Text(
                                text = "  $note",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = order.paymentMethod.orEmpty(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "RM %.2f".format(order.total),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Why a bill was cancelled is the part a reconciliation actually needs.
                    order.cancelReason?.takeIf { it.isNotBlank() }?.let { reason ->
                        Text(
                            text = s.billCancelReason.format(reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::reprint,
                    enabled = !detail.isReprinting
                ) {
                    Icon(
                        Icons.Default.Print,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(s.billReprintAction)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeBill) { Text(s.commonDone) }
            }
        )
    }
}

@Composable
private fun BillCard(
    row: BillHistoryViewModel.BillRow,
    paidLabel: String,
    cancelledLabel: String,
    noTableLabel: String,
    itemsLabel: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${row.order.orderNumber ?: row.order.id.take(6)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "  ${row.tableLabel.ifBlank { noTableLabel }}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "RM %.2f".format(row.order.total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    // A cancelled bill's money never arrived — it must not read like takings.
                    color = if (row.isCancelled) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = row.itemSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusChip(
                    text = if (row.isCancelled) cancelledLabel else paidLabel,
                    isError = row.isCancelled
                )
                Text(
                    text = "  ${itemsLabel.format(row.itemCount)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = row.whenText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, isError: Boolean) {
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer
             else MaterialTheme.colorScheme.primaryContainer
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer
             else MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Small helper so the message effect reads in one line. */
private suspend fun SnackbarHostState.showMessage(text: String) {
    showSnackbar(text)
}
