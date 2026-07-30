package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
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
            SectionHeader("Connection")
            HelpText("Point this app at your café's backend. Applied as soon as you save.")
            Field("Supabase URL", "https://your-project.supabase.co", state.supabaseUrl,
                KeyboardType.Uri) { v -> viewModel.update { it.copy(supabaseUrl = v) } }
            Field("Supabase anon key", "eyJhbGci…", state.supabaseAnonKey,
                KeyboardType.Text, secret = true) { v -> viewModel.update { it.copy(supabaseAnonKey = v) } }
            Field("Website URL", "https://your-site.pages.dev", state.websiteUrl,
                KeyboardType.Uri) { v -> viewModel.update { it.copy(websiteUrl = v) } }
            Field("Café name", "Your Café", state.cafeName,
                KeyboardType.Text) { v -> viewModel.update { it.copy(cafeName = v) } }

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
