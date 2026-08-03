package com.razstudio.pos.ui.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.local.PaymentMethod
import com.razstudio.pos.ui.components.AdBannerFooter
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.tableview.paymentMethodLabel
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.ReportsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device reports screen (Task 25).
 * Displays aggregated sales data with period selection and export options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    onNavigateToBillHistory: () -> Unit = {},
    viewModel: ReportsViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var customStart by remember { mutableStateOf("") }
    var customEnd by remember { mutableStateOf("") }

    // Handle share intent when export URI is set
    LaunchedEffect(uiState.exportUri) {
        uiState.exportUri?.let { uri ->
            val mimeType = if (uri.toString().endsWith(".csv")) "text/csv" else "application/pdf"
            val shareIntent = viewModel.createShareIntent(uri, mimeType)
            context.startActivity(shareIntent)
            viewModel.clearMessages()
        }
    }

    // Show error snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearMessages()
        }
    }

    // Show success snackbar
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.reportsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.commonBack
                        )
                    }
                },
                actions = {
                    // Bill History lives here rather than on the home menu: reports are where
                    // someone already is when they need to find one specific bill.
                    IconButton(onClick = onNavigateToBillHistory) {
                        Icon(
                            imageVector = Icons.Default.ReceiptLong,
                            contentDescription = strings.orderHistoryTitle
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // The report body scrolls inside the weighted region; the banner owns a fixed row below
        // it. Export PDF is the last child of the scroll, so it must not be the outer Column's
        // final child — that would put a navigational-weight button flush against the ad.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Period selection chips
                PeriodSelector(
                    selectedPeriod = uiState.selectedPeriod,
                    strings = strings,
                    onPeriodSelected = { period ->
                        if (period == ReportsViewModel.ReportPeriod.CUSTOM) {
                            showStartDatePicker = true
                        } else {
                            viewModel.selectPeriod(period)
                        }
                    }
                )

                // Custom date range display
                if (uiState.selectedPeriod == ReportsViewModel.ReportPeriod.CUSTOM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showStartDatePicker = true }) {
                            Text(uiState.customStartDate ?: strings.startDateLabel)
                        }
                        Text(strings.toLabel)
                        OutlinedButton(onClick = { showEndDatePicker = true }) {
                            Text(uiState.customEndDate ?: strings.endDateLabel)
                        }
                    }
                }

                // Loading state
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                // Report data display
                uiState.reportData?.let { report ->
                    // Summary card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = strings.summaryTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${report.startDate} ${strings.toLabel} ${report.endDate}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatItem(label = strings.totalOrdersLabel, value = "${report.totalOrders}")
                                StatItem(label = strings.grossTotalLabel, value = "RM %.2f".format(report.totalRevenue))
                                StatItem(label = strings.avgOrderLabel, value = "RM %.2f".format(report.avgOrderValue))
                            }
                        }
                    }

                    // Payment split card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = strings.paymentSplitTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = strings.cashLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${report.paymentSplit.cashCount} ${strings.ordersSuffix}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "RM %.2f".format(report.paymentSplit.cashTotal),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column {
                                    Text(
                                        text = strings.qrLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${report.paymentSplit.qrCount} ${strings.ordersSuffix}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "RM %.2f".format(report.paymentSplit.qrTotal),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Every other method that took money — gateway channels once they are
                            // live. Cash and static QR already have their own columns above and are
                            // excluded here rather than repeated. Absent entirely when there are
                            // none, so a café taking only cash sees exactly what it saw before.
                            val otherMethods = report.paymentSplit.byMethod.filter {
                                !it.method.equals("CASH", ignoreCase = true) &&
                                    !it.method.equals("QR", ignoreCase = true)
                            }
                            if (otherMethods.isNotEmpty()) {
                                HorizontalDivider()
                                otherMethods.forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = PaymentMethod.fromCode(row.method)
                                                    ?.let { paymentMethodLabel(it, strings) }
                                                    // An unrecognised code still shows its raw
                                                    // value: a bill paid by a method this build
                                                    // does not know must not vanish from the day's
                                                    // takings just because it cannot be labelled.
                                                    ?: row.method,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "${row.orderCount} ${strings.ordersSuffix}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Text(
                                            text = "RM %.2f".format(row.revenue),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Per-table breakdown card
                    if (report.perTableBreakdown.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = strings.perTableBreakdownTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                HorizontalDivider()
                                for (tb in report.perTableBreakdown) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = tb.tableLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${tb.orderCount} ${strings.ordersSuffix} • RM %.2f".format(tb.revenue),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Best sellers across the whole menu. This sits above the per-category cards
                    // because it answers the question an owner actually asks — "what sells" — which
                    // a per-category list cannot: it can only rank drinks against other drinks.
                    if (report.topOverall.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = strings.bestSellersTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                HorizontalDivider()
                                report.topOverall.forEachIndexed { index, item ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${index + 1}. ${item.name}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${item.quantity} • RM %.2f".format(item.revenue),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Top items per category
                    for ((category, items) in report.topNPerCategory) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${strings.topItemsPrefix} $category",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                HorizontalDivider()
                                for (item in items) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "${item.quantity} ${strings.soldSuffix} • RM %.2f".format(item.revenue),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Cancelled summary card
                    if (report.cancelledSummary.totalCount > 0) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = strings.cancelledOrdersTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                HorizontalDivider()
                                Text(
                                    text = "${strings.totalPrefix} ${report.cancelledSummary.totalCount} ${strings.ordersSuffix} (RM %.2f)".format(
                                        report.cancelledSummary.totalValue
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    StatItem(label = strings.adminLabel, value = "${report.cancelledSummary.byAdmin}")
                                    StatItem(label = strings.customerLabel, value = "${report.cancelledSummary.byCustomer}")
                                    StatItem(label = strings.staffLabel, value = "${report.cancelledSummary.byStaff}")
                                }
                            }
                        }
                    }

                    // Cash drawer opening count — only shown when a Sunmi AIDL printer with
                    // a drawer kick is configured; null otherwise (non-Sunmi devices never see it).
                    // (HW-REQ-3, Task 2.4)
                    report.drawerOpeningCount?.let { count ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = strings.cashDrawerTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                HorizontalDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = strings.drawerOpeningsLabel,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "$count",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Export button (PDF only — CSV export removed)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.exportPdf(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(strings.exportPdfButton)
                    }
                }
            }

            AdBannerFooter()
        }
    }

    // Start date picker dialog
    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        customStart = formatDateMillis(millis)
                    }
                    showStartDatePicker = false
                    showEndDatePicker = true
                }) {
                    Text(strings.commonNext)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) {
                    Text(strings.commonCancel)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // End date picker dialog
    if (showEndDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        customEnd = formatDateMillis(millis)
                    }
                    showEndDatePicker = false
                    if (customStart.isNotEmpty() && customEnd.isNotEmpty()) {
                        viewModel.setCustomRange(customStart, customEnd)
                    }
                }) {
                    Text(strings.commonConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) {
                    Text(strings.commonCancel)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun PeriodSelector(
    selectedPeriod: ReportsViewModel.ReportPeriod,
    strings: UiStrings,
    onPeriodSelected: (ReportsViewModel.ReportPeriod) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val periods = listOf(
            ReportsViewModel.ReportPeriod.TODAY to strings.periodToday,
            ReportsViewModel.ReportPeriod.THIS_WEEK to strings.periodThisWeek,
            ReportsViewModel.ReportPeriod.THIS_MONTH to strings.periodThisMonth,
            ReportsViewModel.ReportPeriod.CUSTOM to strings.periodCustom
        )
        for ((period, label) in periods) {
            FilterChip(
                selected = selectedPeriod == period,
                onClick = { onPeriodSelected(period) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDateMillis(millis: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return format.format(Date(millis))
}
