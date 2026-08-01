package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.OperatingMode
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.ui.viewmodels.SetupViewModel

/**
 * In-app deployment setup, reached from the three-dots menu on the login screen. Lets an operator
 * point this template build at their own backend (Supabase + website) and store their Cloudflare /
 * GitHub credentials. Connection fields take effect immediately on save (read at runtime by the API
 * client and realtime services); Cloudflare/GitHub are stored encrypted for reference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Task 9.2: the topology comes first, because it decides what else is worth asking ──
            SectionHeader("How this café runs")
            HelpText("Pick this first — it decides what the rest of this screen asks for.")
            ModeChoice(
                selected = state.operatingMode,
                onSelect = { viewModel.selectMode(it) },
            )

            SectionHeader("Connection")
            if (state.operatingMode == OperatingMode.CLOUD) {
                HelpText("Point this app at your café's backend. Applied as soon as you save.")
                Field("Supabase URL", "https://your-project.supabase.co", state.supabaseUrl,
                    KeyboardType.Uri) { v -> viewModel.update { it.copy(supabaseUrl = v) } }
                Field("Supabase anon key", "eyJhbGci…", state.supabaseAnonKey,
                    KeyboardType.Text, secret = true) { v -> viewModel.update { it.copy(supabaseAnonKey = v) } }
                Field("Website URL", "https://your-site.pages.dev", state.websiteUrl,
                    KeyboardType.Uri) { v -> viewModel.update { it.copy(websiteUrl = v) } }
            } else {
                // Requirement 2.4: an off-cloud café is never asked for a Supabase URL. Not merely
                // optional — absent. A field that can be filled in and then ignored is how an owner
                // ends up believing their LAN café is syncing somewhere.
                HelpText(
                    "No internet backend. This device holds the café's data, and prints " +
                        "directly to its own printer. Saving will clear any Supabase details " +
                        "previously stored on this device."
                )
            }
            Field("Café name", "Your Café", state.cafeName,
                KeyboardType.Text) { v -> viewModel.update { it.copy(cafeName = v) } }

            if (state.operatingMode != OperatingMode.CLOUD) {
                HelpText(
                    "Next: pair the printer from Café Management → Printers. " +
                        if (state.operatingMode == OperatingMode.LAN) {
                            "Staff devices join over your Wi-Fi from the Devices screen."
                        } else {
                            "Kiosk Mode runs on this device alone — no tables, no staff devices, " +
                                "and orders are identified by a running number instead of a table."
                        }
                )
            }

            SectionHeader("Cloudflare")
            HelpText("Stored securely for your reference / deploy tooling. Not used by the app itself.")
            Field("Account ID", "", state.cloudflareAccountId,
                KeyboardType.Text) { v -> viewModel.update { it.copy(cloudflareAccountId = v) } }
            Field("DNS zone", "yourdomain.com", state.cloudflareDnsZone,
                KeyboardType.Text) { v -> viewModel.update { it.copy(cloudflareDnsZone = v) } }
            Field("Pages / Workers project", "your-cafe-project", state.cloudflarePagesProject,
                KeyboardType.Text) { v -> viewModel.update { it.copy(cloudflarePagesProject = v) } }
            Field("API token", "", state.cloudflareApiToken,
                KeyboardType.Text, secret = true) { v -> viewModel.update { it.copy(cloudflareApiToken = v) } }

            SectionHeader("GitHub")
            HelpText("Stored securely for your reference / deploy tooling. Not used by the app itself.")
            Field("Repository", "owner/repo", state.githubRepo,
                KeyboardType.Text) { v -> viewModel.update { it.copy(githubRepo = v) } }
            Field("Access token", "", state.githubToken,
                KeyboardType.Text, secret = true) { v -> viewModel.update { it.copy(githubToken = v) } }

            Button(
                onClick = { viewModel.save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
            ) {
                Text(if (state.saved) "Saved ✓" else "Save")
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/**
 * The three topologies, as radio options (task 9.2, Requirements 2.2, 2.3).
 *
 * Radio buttons rather than a dropdown on purpose: this is a decision an owner makes once, it changes
 * what the rest of the app can do, and all three options plus their consequences should be readable
 * without a tap. Each carries a one-line consequence, because "LAN" and "Kiosk" mean nothing to
 * someone who runs a café.
 */
@Composable
private fun ModeChoice(
    selected: OperatingMode,
    onSelect: (OperatingMode) -> Unit,
) {
    val options = listOf(
        Triple(
            OperatingMode.CLOUD,
            "Full Online with QR ordering",
            "Customers scan a table QR and order from their own phone. Needs internet and a Supabase project.",
        ),
        Triple(
            OperatingMode.LAN,
            "(W)LAN AP without QR ordering",
            "Staff phones order over your own Wi-Fi. No internet needed. This device holds the data and the printer.",
        ),
        Triple(
            OperatingMode.KIOSK,
            "Kiosk Mode",
            "This one device only. No tables, no staff phones, no internet — orders get a running number.",
        ),
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        options.forEach { (mode, title, blurb) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == mode,
                        role = Role.RadioButton,
                        onClick = { onSelect(mode) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = selected == mode,
                    // null: the whole row is the target via selectable() above, so the button must
                    // not also be one or TalkBack announces two controls for one choice.
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Field(
    label: String,
    placeholder: String,
    value: String,
    keyboardType: KeyboardType,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
