package com.razstudio.opsapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.opsapp.data.promos.AffiliateProductEntity
import com.razstudio.opsapp.ui.viewmodels.AffiliateDashboardViewModel

/**
 * Affiliate Dashboard (Requirement 7.4–7.6) — the entry point under "Affiliate Ads," replacing a
 * direct jump into [AffiliateDebugScreen]. Stats-first: total/source-breakdown counts, staleness,
 * a one-tap Sync Now, and a capped Top Products list — with the debug tools and catalog editor one
 * tap further in rather than being the first thing shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AffiliateDashboardScreen(
    onBack: () -> Unit,
    onOpenDebugTools: () -> Unit,
    onOpenCatalogEditor: () -> Unit,
    viewModel: AffiliateDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Affiliate Catalog") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCard(label = "Total", value = state.totalCount.toString(), modifier = Modifier.weight(1f))
                StatCard(label = "Shopee API", value = state.shopeeApiCount.toString(), modifier = Modifier.weight(1f))
                StatCard(label = "GitHub Fallback", value = state.githubFallbackCount.toString(), modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = state.lastSyncedAt?.let { "Last synced: $it" } ?: "Never synced",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = if (state.isStale) "Stale — due for a sync" else "Fresh",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isStale) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                Button(onClick = { viewModel.syncNow() }, enabled = !state.syncing) {
                    if (state.syncing) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Sync Now")
                    }
                }
            }

            state.syncMessage?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Top Products (by commission)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (state.topProducts.isEmpty()) {
                Text(
                    text = "No cached products yet — tap Sync Now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.topProducts, key = { it.id }) { product ->
                        TopProductRow(product)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpenDebugTools, modifier = Modifier.weight(1f)) {
                    Text("Debug Tools")
                }
                Button(onClick = onOpenCatalogEditor, modifier = Modifier.weight(1f)) {
                    Text("Catalog Editor")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TopProductRow(product: AffiliateProductEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = product.productName.take(40).ifBlank { "(unnamed)" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${(product.commissionRate * 100).let { "%.1f".format(it) }}%",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (product.source == "SHOPEE_API") "API" else "GitHub",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
