package com.razstudio.pos.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import com.razstudio.pos.ui.util.QrCodeUtil
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.StepResult
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.CloudflareModeSelection
import com.razstudio.pos.ui.viewmodels.OwnerKeyLoginViewModel
import com.razstudio.pos.ui.viewmodels.ProvisionerState
import com.razstudio.pos.ui.viewmodels.ProvisionerViewModel
import com.razstudio.pos.ui.viewmodels.SupabaseModeSelection
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState

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
    ownerKeyViewModel: OwnerKeyLoginViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val ownerKeyState by ownerKeyViewModel.state.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val snackbarHostState = remember { SnackbarHostState() }
    var showOwnerQrDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    // Once provisioning succeeds, lock the screen on the owner-key QR so the owner must see it.
    LaunchedEffect(state.success) {
        if (state.success) showOwnerQrDialog = true
    }

    // After the owner acknowledges the QR and the owner-key login completes, land in the admin home.
    LaunchedEffect(ownerKeyState) {
        if (ownerKeyState is OwnerKeyLoginViewModel.State.Done) onDone()
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

            WizardSection(state, viewModel)
            SupabaseSection(state, viewModel)
            CloudflareSection(state, viewModel)
            CafeSection(state, viewModel)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (state.isRunning) {
                RunningView(state)
            } else if (!state.success) {
                ActionView(state, viewModel)
            }

            if (!state.isRunning && state.results.isNotEmpty()) {
                ResultsList(state.results)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Owner-key QR: shown immediately after provisioning succeeds, before any sign-in ────────────
    //
    // The owner key URL on its own does not carry the Supabase URL/key params, because the backend
    // cannot know the public values until after the project is created. We append them here so the
    // QR works on a brand-new device without waiting for the Cloudflare Pages site to finish its
    // first deployment.
    if (showOwnerQrDialog) {
        val ownerKeyUrl = state.resolvedOwnerKeyUrl
        val augmentedUrl = remember(ownerKeyUrl, state.resolvedSupabaseUrl, state.resolvedSupabaseAnonKey) {
            val base = ownerKeyUrl ?: ""
            val api = state.resolvedSupabaseUrl ?: ""
            val key = state.resolvedSupabaseAnonKey ?: ""
            if (base.isBlank() || api.isBlank() || key.isBlank()) base
            else {
                val sep = if (base.contains('?')) '&' else '?'
                val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
                "$base${sep}api=${enc(api)}&key=${enc(key)}"
            }
        }
        OwnerQrDialog(
            ownerKeyUrl = augmentedUrl,
            ownerKeyState = ownerKeyState,
            strings = strings,
            onContinue = {
                viewModel.saveConfiguration()
                ownerKeyViewModel.load(augmentedUrl)
            },
        )
    }
}

@Composable
private fun OwnerQrDialog(
    ownerKeyUrl: String,
    ownerKeyState: OwnerKeyLoginViewModel.State,
    strings: com.razstudio.pos.ui.i18n.UiStrings,
    onContinue: () -> Unit,
) {
    val qrBitmap = remember(ownerKeyUrl) { QrCodeUtil.encode(ownerKeyUrl, sizePx = 512) }
    val isWorking = ownerKeyState is OwnerKeyLoginViewModel.State.Working

    AlertDialog(
        onDismissRequest = { },
        title = { Text(strings.provisionOwnerQrTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = strings.provisionOwnerQrWarning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                qrBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = strings.provisionOwnerQrTitle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: Text(
                    text = "Could not generate QR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (ownerKeyState is OwnerKeyLoginViewModel.State.Working) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (ownerKeyState is OwnerKeyLoginViewModel.State.Failed) {
                    Text(
                        text = when (ownerKeyState.reason) {
                            OwnerKeyLoginViewModel.Reason.NOT_AN_OWNER_KEY -> strings.ownerQrNotAnOwnerKey
                            OwnerKeyLoginViewModel.Reason.NO_QR_IN_IMAGE -> strings.ownerQrNoQrInImage
                            OwnerKeyLoginViewModel.Reason.REJECTED -> strings.ownerQrRejected
                            OwnerKeyLoginViewModel.Reason.UNREACHABLE -> strings.ownerQrUnreachable
                            OwnerKeyLoginViewModel.Reason.NO_BACKEND_IN_QR -> strings.ownerQrNoBackend
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(strings.provisionOwnerQrContinueButton) }
        },
    )
}

/**
 * Where the credentials below are sent.
 *
 * ### Why this is a field and not a build constant
 *
 * It was `BuildConfig.PROVISIONER_WORKER_URL`, read from `local.properties`. That put a live
 * provisioning endpoint into every APK built from this branch — including every café build that will
 * never provision anything — and it could not be changed without a rebuild, so aiming the installer at
 * a disposable Wizard to rehearse the two unverified steps meant recompiling. When the property was
 * unset, which is the template default, the flow failed with "not configured in this build": a dead
 * end for whoever was holding the tablet.
 *
 * It is first on the screen because of what it does. Every token underneath is POSTed here, so this
 * field decides who receives a Supabase personal access token and a Cloudflare API token. Asking for
 * the destination before the credentials is the honest order.
 */
@Composable
private fun WizardSection(state: ProvisionerState, viewModel: ProvisionerViewModel) {
    SectionHeader("Provisioning Wizard")
    Text(
        text = "The RAZStudio Wizard endpoint that performs the setup. Everything you enter below is " +
            "sent to this URL, so check it before filling in any credentials.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Field(
        label = "Wizard URL (https://…/api/provision/run)",
        value = state.provisionerWorkerUrl,
        onChange = { v -> viewModel.update { it.copy(provisionerWorkerUrl = v) } },
        keyboardType = KeyboardType.Uri,
    )
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

        // ── Repair: deploy the Edge Functions only ───────────────────────────────────────────────
        //
        // Offered only in EXISTING mode, because it needs a project that already exists. This is the
        // fix for the one broken state the app cannot talk its way out of: schema applied, functions
        // missing. The REST API answers, so the project looks alive from outside, but every sign-in
        // fails — the APK reaches a café only through its Edge Functions.
        //
        // Separate from "Start provisioning" because that button also wants a Cloudflare token, a café
        // name and a Pages project. None of that is needed to upload 26 functions to a project whose
        // ref is already in the field above, and demanding it is what stopped a half-provisioned café
        // from being repairable from the device standing in it.
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = { viewModel.deployFunctionsOnly() },
            enabled = !state.isRunning &&
                state.supabasePat.isNotBlank() &&
                state.supabaseProjectRef.isNotBlank() &&
                state.provisionerWorkerUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Deploy Edge Functions to this project")
        }
        Text(
            text = "Uploads the café backend's Edge Functions and nothing else. Safe to repeat — each " +
                "function is replaced in place. Use this if sign-in fails on a project whose database " +
                "is already set up.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
