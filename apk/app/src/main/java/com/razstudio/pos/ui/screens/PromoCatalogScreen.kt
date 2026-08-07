package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.promos.PromoProduct
import com.razstudio.pos.ui.viewmodels.PromoCatalogViewModel

/**
 * Debug-only editor for the central affiliate catalog (`promos/partners.json` on `main`).
 *
 * ### Why this exists as a page rather than a text field
 *
 * That file is the single source every café's web app reads at runtime, so the entries have to be
 * right — and editing raw JSON on a phone keyboard is how a stray comma takes every café's ads down
 * at once. A form cannot produce malformed JSON.
 *
 * ### Paste a link, get an entry
 *
 * The useful part: paste a Shopee shortlink and **Resolve** fills the creative and the title from the
 * link's own page. Typing an image URL from a phone is the step nobody would ever do, which is why the
 * six live entries sat imageless for a day. The shortlink itself is never modified — it stays the
 * clickable destination so the commission tracks.
 *
 * ### Debug builds only
 *
 * It holds a GitHub token and commits to `main`. That belongs in the owner's hands, not on a staff
 * phone in a café, so the caller gates it on `BuildConfig.DEBUG`.
 *
 * Strings are English literals rather than [com.razstudio.pos.ui.i18n.UiStrings] entries: this is an
 * internal tool for one person, and 20 keys × 5 languages of translation noise would make the real
 * localisation harder to read for no reader who benefits.
 */
@Composable
fun PromoCatalogScreen(
    onBack: () -> Unit,
    viewModel: PromoCatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadIfTokenPresent() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Affiliate catalog",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Edits publish to promos/partners.json on main. Every café reads that file at " +
                "runtime, so a save reaches all of them within about five minutes — no rebuild.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // ── Token ────────────────────────────────────────────────────────────────────
        OutlinedTextField(
            value = state.token,
            onValueChange = viewModel::setToken,
            label = { Text("GitHub token (repo contents: write)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row {
            OutlinedButton(onClick = { viewModel.saveTokenAndLoad() }, enabled = state.token.isNotBlank()) {
                Text("Save token & load")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.load() }, enabled = !state.busy) { Text("Reload") }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        // ── Add by pasting a shortlink ────────────────────────────────────────────────
        OutlinedTextField(
            value = state.pastedLink,
            onValueChange = viewModel::setPastedLink,
            label = { Text("Paste a Shopee shortlink") },
            placeholder = { Text("https://s.shopee.com.my/…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row {
            Button(
                onClick = { viewModel.resolveAndAdd() },
                enabled = state.pastedLink.isNotBlank() && !state.busy,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Resolve & add")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.addBlank() }, enabled = !state.busy) {
                Text("Add empty")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = state.subId,
            onValueChange = viewModel::setSubId,
            label = { Text("Default sub_id (Shopee reporting)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        state.message?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "${state.products.size} placement(s)",
            style = MaterialTheme.typography.labelLarge,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.products) { index, product ->
                EntryCard(
                    index = index,
                    product = product,
                    onChange = { viewModel.update(index, it) },
                    onDelete = { viewModel.remove(index) },
                    onMoveUp = { viewModel.move(index, index - 1) },
                    onMoveDown = { viewModel.move(index, index + 1) },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { viewModel.publish() },
                enabled = !state.busy && state.loaded,
                modifier = Modifier.weight(1f),
            ) { Text(if (state.busy) "Working…" else "Publish to main") }
            Spacer(modifier = Modifier.width(8.dp))
            if (state.busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun EntryCard(
    index: Int,
    product: PromoProduct,
    onChange: (PromoProduct) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onMoveUp) { Text("↑") }
                TextButton(onClick = onMoveDown) { Text("↓") }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete placement",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            OutlinedTextField(
                value = product.alt,
                onValueChange = { onChange(product.copy(alt = it)) },
                label = { Text("Label a customer reads") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = product.href,
                onValueChange = { onChange(product.copy(href = it)) },
                label = { Text("Affiliate shortlink (never edit by hand)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = product.img,
                onValueChange = { onChange(product.copy(img = it)) },
                label = { Text("Image URL (blank = text card)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
