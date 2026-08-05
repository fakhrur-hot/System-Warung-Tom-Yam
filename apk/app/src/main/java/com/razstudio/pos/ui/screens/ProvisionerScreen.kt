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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.StepResult
import com.razstudio.pos.ui.viewmodels.CloudflareModeSelection
import com.razstudio.pos.ui.viewmodels.ProvisionerState
import com.razstudio.pos.ui.viewmodels.ProvisionerViewModel
import com.razstudio.pos.ui.viewmodels.SupabaseModeSelection
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * In-app installer flow that provisions a new café backend from the tablet.
 *
 * NOTE: strings are currently hardcoded English, matching the existing SetupScreen's temporary state.
 * A later pass should move them into UiStrings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvisionerScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ProvisionerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(state.configurationSaved) {
        if (state.configurationSaved) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Provision new café") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.isRunning) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Create or connect a Supabase project and a Cloudflare Pages site for this café. " +
                    "Credentials are only used for this setup request and are not stored on the tablet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SupabaseSection(state, viewModel)
            CloudflareSection(state, viewModel)
            CafeSection(state, viewModel)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (state.isRunning) {
                RunningView(state)
            } else if (state.success) {
                SuccessView(
                    state = state,
                    onSave = { viewModel.saveConfiguration() },
                    onCopyOwnerKey = { state.resolvedOwnerKeyUrl?.let { clipboard.setText(AnnotatedString(it)) } },
                )
            } else {
                ActionView(state, viewModel)
            }

            if (!state.isRunning && state.results.isNotEmpty()) {
                ResultsList(state.results)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SupabaseSection(state: ProvisionerState, viewModel: ProvisionerViewModel) {
    SectionHeader("Supabase")
    ModeChoice(
        label = "Create a new Supabase project",
        selected = state.supabaseMode == SupabaseModeSelection.NEW,
        onSelect = { viewModel.update { it.copy(supabaseMode = SupabaseModeSelection.NEW) } },
    )
    ModeChoice(
        label = "Use an existing Supabase project",
        selected = state.supabaseMode == SupabaseModeSelection.EXISTING,
        onSelect = { viewModel.update { it.copy(supabaseMode = SupabaseModeSelection.EXISTING) } },
    )

    Spacer(modifier = Modifier.height(4.dp))

    SecretField(
        label = "Personal Access Token",
        value = state.supabasePat,
        onChange = { v -> viewModel.update { it.copy(supabasePat = v) } },
    )

    if (state.supabaseMode == SupabaseModeSelection.NEW) {
        Field(
            label = "Organization ID",
            value = state.supabaseOrgId,
            onChange = { v -> viewModel.update { it.copy(supabaseOrgId = v) } },
        )
        Field(
            label = "Region",
            value = state.supabaseRegion,
            onChange = { v -> viewModel.update { it.copy(supabaseRegion = v) } },
        )
        Field(
            label = "Project name",
            value = state.supabaseProjectName,
            onChange = { v -> viewModel.update { it.copy(supabaseProjectName = v) } },
        )
    } else {
        Field(
            label = "Project reference",
            value = state.supabaseProjectRef,
            onChange = { v -> viewModel.update { it.copy(supabaseProjectRef = v) } },
        )
        SecretField(
            label = "Anon (public) key",
            value = state.supabaseAnonKey,
            onChange = { v -> viewModel.update { it.copy(supabaseAnonKey = v) } },
        )
        SecretField(
            label = "Service role key",
            value = state.supabaseServiceRoleKey,
            onChange = { v -> viewModel.update { it.copy(supabaseServiceRoleKey = v) } },
        )
    }
}

@Composable
private fun CloudflareSection(state: ProvisionerState, viewModel: ProvisionerViewModel) {
    SectionHeader("Cloudflare Pages")
    ModeChoice(
        label = "Create a new Cloudflare Pages project",
        selected = state.cloudflareMode == CloudflareModeSelection.NEW,
        onSelect = { viewModel.update { it.copy(cloudflareMode = CloudflareModeSelection.NEW) } },
    )
    ModeChoice(
        label = "Use an existing Cloudflare Pages project",
        selected = state.cloudflareMode == CloudflareModeSelection.EXISTING,
        onSelect = { viewModel.update { it.copy(cloudflareMode = CloudflareModeSelection.EXISTING) } },
    )

    Spacer(modifier = Modifier.height(4.dp))

    Field(
        label = "Account ID",
        value = state.cloudflareAccountId,
        onChange = { v -> viewModel.update { it.copy(cloudflareAccountId = v) } },
    )
    SecretField(
        label = "API token",
        value = state.cloudflareApiToken,
        onChange = { v -> viewModel.update { it.copy(cloudflareApiToken = v) } },
    )

    if (state.cloudflareMode == CloudflareModeSelection.NEW) {
        Field(
            label = "Café slug (Pages project name)",
            value = state.cloudflareCafeSlug,
            onChange = { v -> viewModel.update { it.copy(cloudflareCafeSlug = v) } },
        )
    } else {
        Field(
            label = "Existing Pages project name",
            value = state.cloudflareProjectName,
            onChange = { v -> viewModel.update { it.copy(cloudflareProjectName = v) } },
        )
    }

    Field(
        label = "Custom domain (optional)",
        value = state.cloudflareCustomDomain,
        onChange = { v -> viewModel.update { it.copy(cloudflareCustomDomain = v) } },
    )
    Field(
        label = "Zone ID (only needed for custom domain)",
        value = state.cloudflareZoneId,
        onChange = { v -> viewModel.update { it.copy(cloudflareZoneId = v) } },
    )
}

@Composable
private fun CafeSection(state: ProvisionerState, viewModel: ProvisionerViewModel) {
    SectionHeader("Café")
    Field(
        label = "Café name",
        value = state.cafeName,
        onChange = { v -> viewModel.update { it.copy(cafeName = v) } },
    )
    SecretField(
        label = "Brevo API key (optional, for email)",
        value = state.brevoApiKey,
        onChange = { v -> viewModel.update { it.copy(brevoApiKey = v) } },
    )
}

@Composable
private fun ActionView(state: ProvisionerState, viewModel: ProvisionerViewModel) {
    val reason = viewModel.blockingReason()
    Button(
        onClick = { viewModel.startProvisioning() },
        enabled = !state.isRunning && reason == null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Start provisioning")
    }
    reason?.let { r ->
        Text(
            text = r,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RunningView(state: ProvisionerState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.width(24.dp))
        Text(
            text = "Provisioning… ${if (state.results.isEmpty()) "starting" else "${state.results.count { it.isOk }} done"}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SuccessView(
    state: ProvisionerState,
    onSave: () -> Unit,
    onCopyOwnerKey: () -> Unit,
) {
    Text(
        text = "Provisioning complete. The tablet can now save the connection details and use the owner key to sign in.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )

    state.resolvedSupabaseUrl?.let { url ->
        ReadOnlyRow(label = "Supabase URL", value = url)
    }
    state.resolvedWebsiteUrl?.let { url ->
        ReadOnlyRow(label = "Website URL", value = url)
    }
    state.resolvedOwnerKeyUrl?.let { url ->
        ReadOnlyRow(label = "Owner key URL", value = url)
        TextButton(
            onClick = onCopyOwnerKey,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Copy owner key URL") }
    }

    Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save and continue") }
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ResultsList(results: List<StepResult>) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = "Steps",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        results.forEach { r ->
            val color = when (r.status) {
                "ok" -> MaterialTheme.colorScheme.primary
                "error" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when (r.status) {
                        "ok" -> "✓"
                        "error" -> "✗"
                        else -> "•"
                    },
                    color = color,
                )
                Column {
                    Text(
                        text = r.step,
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                    r.detail?.let { d ->
                        Text(
                            text = d,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ModeChoice(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
    )
}
