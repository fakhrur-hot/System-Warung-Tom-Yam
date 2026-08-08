package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.promos.LinkValidationResult
import com.razstudio.pos.ui.viewmodels.AffiliateDebugViewModel

/**
 * Developer-only debug screen for testing the Shopee Affiliate integration.
 *
 * Sections:
 * - **API Query Tester**: enter a keyword, call searchProducts(), view raw results.
 * - **Link Generator**: enter a URL, generate affiliate link, show validation status.
 * - **Cache Inspector**: show Room DB product count, last sync time, stale status.
 * - **Sync Trigger**: one-tap button to call syncNow(), display result.
 *
 * This screen is only registered in the nav graph for debug builds
 * (`if (BuildConfig.DEBUG)` guard). It never ships in release APKs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AffiliateDebugScreen(
    onBack: () -> Unit,
    viewModel: AffiliateDebugViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Affiliate Debug") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ApiQuerySection(viewModel)
            LinkGeneratorSection(viewModel)
            CacheInspectorSection(viewModel)
            SyncTriggerSection(viewModel)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ── API Query Tester ─────────────────────────────────────────────────────────────

@Composable
private fun ApiQuerySection(viewModel: AffiliateDebugViewModel) {
    var keyword by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsState()
    val status by viewModel.searchStatus.collectAsState()

    DebugCard(title = "API Query Tester") {
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            label = { Text("Keyword") },
            placeholder = { Text("e.g. milo, vacuum") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { viewModel.searchProducts(keyword) },
            enabled = keyword.isNotBlank(),
        ) {
            Text("Search")
        }

        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (results.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            results.take(10).forEach { product ->
                Text(
                    text = buildString {
                        append("• ${product.productName.take(50)}")
                        append(" — RM${product.price / 100.0}")
                        append(" (${product.commissionRate}%)")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (results.size > 10) {
                Text(
                    text = "… and ${results.size - 10} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Link Generator ───────────────────────────────────────────────────────────────

@Composable
private fun LinkGeneratorSection(viewModel: AffiliateDebugViewModel) {
    var url by remember { mutableStateOf("") }
    val generatedLink by viewModel.generatedLink.collectAsState()
    val validationResult by viewModel.validationResult.collectAsState()

    DebugCard(title = "Link Generator") {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Shopee URL") },
            placeholder = { Text("https://shopee.com.my/…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { viewModel.generateLink(url) },
            enabled = url.isNotBlank(),
        ) {
            Text("Generate")
        }

        if (generatedLink.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = generatedLink,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(4.dp))
            val (statusText, statusColor) = when (validationResult) {
                is LinkValidationResult.Valid -> "✓ Valid" to MaterialTheme.colorScheme.primary
                is LinkValidationResult.Invalid -> {
                    val reason = (validationResult as LinkValidationResult.Invalid).reason
                    "✗ Invalid: $reason" to MaterialTheme.colorScheme.error
                }
                null -> "" to MaterialTheme.colorScheme.onSurface
            }
            if (statusText.isNotBlank()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }
        }
    }
}

// ── Cache Inspector ──────────────────────────────────────────────────────────────

@Composable
private fun CacheInspectorSection(viewModel: AffiliateDebugViewModel) {
    val cacheInfo by viewModel.cacheInfo.collectAsState()

    DebugCard(title = "Cache Inspector") {
        Text(
            text = cacheInfo,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { viewModel.refreshCacheInfo() }) {
            Text("Refresh")
        }
    }
}

// ── Sync Trigger ─────────────────────────────────────────────────────────────────

@Composable
private fun SyncTriggerSection(viewModel: AffiliateDebugViewModel) {
    val syncStatus by viewModel.syncStatus.collectAsState()

    DebugCard(title = "Sync Trigger") {
        Row {
            Button(onClick = { viewModel.triggerSync() }) {
                Text("Sync Now")
            }
            if (syncStatus.isNotBlank()) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = syncStatus,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

// ── Shared debug card layout ─────────────────────────────────────────────────────

@Composable
private fun DebugCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
