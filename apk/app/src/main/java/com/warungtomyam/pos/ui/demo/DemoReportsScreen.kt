package com.warungtomyam.pos.ui.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.warungtomyam.pos.data.local.Order
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.uiStrings

/**
 * Reports screen for Demo Mode.
 * Displays computed metrics from the demo database (total orders, revenue, avg order value)
 * and a list of all orders (seed data + session-created).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoReportsScreen(
    viewModel: DemoViewModel,
    onBack: () -> Unit,
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val reportState by viewModel.reportState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadReports()
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
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Metric cards row
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        title = strings.totalOrdersLabel,
                        value = reportState.totalCompletedOrders.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = strings.grossTotalLabel,
                        value = "RM %.2f".format(reportState.totalRevenue),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Average order value card
            item {
                MetricCard(
                    title = strings.avgOrderValueLabel,
                    value = "RM %.2f".format(reportState.averageOrderValue),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Order history header
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.orderHistoryTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Order list
            if (reportState.allOrders.isEmpty()) {
                item {
                    Text(
                        text = strings.noOrdersYet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(reportState.allOrders) { order ->
                    OrderHistoryRow(order = order)
                    HorizontalDivider()
                }
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun OrderHistoryRow(order: Order) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Table ID
        Text(
            text = order.tableId,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        // Status
        Text(
            text = order.status.name.replace("_", " "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Total
        Text(
            text = "RM %.2f".format(order.total),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
