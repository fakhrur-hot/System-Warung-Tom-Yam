package com.razstudio.opsapp.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.opsapp.data.StepResult
import com.razstudio.opsapp.ui.util.OwnerQrShare
import com.razstudio.opsapp.ui.viewmodels.CloudflareModeSelection
import com.razstudio.opsapp.ui.viewmodels.ProvisionWizardState
import com.razstudio.opsapp.ui.viewmodels.ProvisionWizardViewModel
import com.razstudio.opsapp.ui.viewmodels.SupabaseModeSelection
import com.razstudio.opsapp.ui.viewmodels.WizardStep

/**
 * Multi-step wizard for provisioning a new café from the Operator APK.
 *
 * Steps: Café Info → Supabase → Cloudflare → Run → Done.
 * Each step has Next/Back navigation; validation blocks Next when fields are incomplete.
 * The Run step shows live StepResult ticks (✓/✗/spinner) mirroring apk/app's ProvisionerScreen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun ProvisionWizardScreen(
    onBack: () -> Unit,
    viewModel: ProvisionWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Provision New Cafe") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.isRunning) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Stepper indicator
            StepperIndicator(
                currentStep = state.currentStep,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            HorizontalDivider()

            // Content area — scrollable step content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedContent(
                    targetState = state.currentStep,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "wizard_step",
                ) { step ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (step) {
                            WizardStep.CAFE_INFO -> CafeInfoStep(state, viewModel)
                            WizardStep.SUPABASE -> SupabaseStep(state, viewModel)
                            WizardStep.CLOUDFLARE -> CloudflareStep(state, viewModel)
                            WizardStep.RUN -> RunStep(state, viewModel)
                            WizardStep.DONE -> DoneStep(state)
                        }
                    }
                }
            }

            // Error message
            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider()

            // Bottom navigation buttons
            NavigationButtons(
                state = state,
                onBack = { viewModel.previousStep() },
                onNext = { viewModel.nextStep() },
                onStartProvisioning = { viewModel.startProvisioning() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

// ── Stepper Indicator ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepperIndicator(
    currentStep: WizardStep,
    modifier: Modifier = Modifier,
) {
    val steps = WizardStep.entries
    val currentIndex = currentStep.ordinal
    val stepLabels = listOf("Café", "Supabase", "Cloudflare", "Run", "Done")

    Column(modifier = modifier) {
        // Progress bar
        LinearProgressIndicator(
            progress = { (currentIndex.toFloat()) / (steps.size - 1).coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Step labels row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            stepLabels.forEachIndexed { index, label ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    index < currentIndex -> MaterialTheme.colorScheme.primary
                                    index == currentIndex -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                index <= currentIndex -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            index <= currentIndex -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

// ── Step 1: Café Info ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CafeInfoStep(state: ProvisionWizardState, viewModel: ProvisionWizardViewModel) {
    SectionHeader("Provisioning Wizard")
    Text(
        text = "The RAZStudio Wizard endpoint that performs the setup. Everything you enter below " +
            "is sent to this URL, so check it before filling in any credentials.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Field(
        label = "Wizard URL (https://…/api/provision/run)",
        value = state.provisionerWorkerUrl,
        onChange = { v -> viewModel.update { it.copy(provisionerWorkerUrl = v) } },
        keyboardType = KeyboardType.Uri,
    )

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

// ── Step 2: Supabase ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun SupabaseStep(state: ProvisionWizardState, viewModel: ProvisionWizardViewModel) {
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

// ── Step 3: Cloudflare ────────────────────────────────────────────────────────────────────────────

@Composable
private fun CloudflareStep(state: ProvisionWizardState, viewModel: ProvisionWizardViewModel) {
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

// ── Step 4: Run ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RunStep(state: ProvisionWizardState, viewModel: ProvisionWizardViewModel) {
    SectionHeader("Run Provisioning")

    if (!state.isRunning && state.results.isEmpty()) {
        Text(
            text = "Everything is configured. Press \"Start Provisioning\" below to create the " +
                "café's backend infrastructure.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.isRunning) {
        RunningView(state)
    }

    if (state.results.isNotEmpty()) {
        ResultsList(state.results)
    }
}

// ── Step 5: Done ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun DoneStep(state: ProvisionWizardState) {
    val isSuccess = state.provisionResult?.success == true
    val context = LocalContext.current

    SectionHeader(if (isSuccess) "Provisioning Complete" else "Provisioning Failed")

    if (isSuccess) {
        Text(
            text = "The café backend has been provisioned successfully. All infrastructure steps " +
                "completed without errors.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        // ── "Share Cafe Owner QR" button — the primary action on the Done screen (Req 3.7–3.10) ──
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val ownerKeyUrl = state.provisionResult?.ownerKeyUrl
                if (ownerKeyUrl.isNullOrBlank()) {
                    Toast.makeText(context, "Owner key URL not available", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                try {
                    val intent = OwnerQrShare.buildShareIntent(context, ownerKeyUrl)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Couldn't prepare the QR image", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = isSuccess,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share Cafe Owner QR")
        }

        // Self-registration status
        Spacer(modifier = Modifier.height(12.dp))
        val cafeName = state.provisionResult?.cafeName ?: state.cafeName
        if (state.selfRegistrationComplete) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Text(
                    text = "✓ Connected as OPERATOR to $cafeName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "⚠ Self-registration failed",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "The café was provisioned but this device could not connect " +
                            "automatically. You can reconnect to \"$cafeName\" later via an " +
                            "Operator Invite.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    } else {
        Text(
            text = state.errorMessage ?: "Provisioning did not complete successfully.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        // Button still visible but disabled when provisioning hasn't succeeded (Req 3.7)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { /* unreachable — button is disabled */ },
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share Cafe Owner QR")
        }
    }

    if (state.results.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        ResultsList(state.results)
    }
}

// ── Navigation Buttons ────────────────────────────────────────────────────────────────────────────

@Composable
private fun NavigationButtons(
    state: ProvisionWizardState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onStartProvisioning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Back button — shown on all steps except the first and DONE
        if (state.currentStep != WizardStep.CAFE_INFO && state.currentStep != WizardStep.DONE) {
            OutlinedButton(
                onClick = onBack,
                enabled = !state.isRunning,
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
        }

        when (state.currentStep) {
            WizardStep.CAFE_INFO,
            WizardStep.SUPABASE,
            WizardStep.CLOUDFLARE -> {
                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Next")
                }
            }
            WizardStep.RUN -> {
                Button(
                    onClick = onStartProvisioning,
                    enabled = !state.isRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Provisioning…")
                    } else {
                        Text("Start Provisioning")
                    }
                }
            }
            WizardStep.DONE -> {
                Button(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Finish")
                }
            }
        }
    }
}

// ── Shared composables (mirroring ProvisionerScreen's helpers) ────────────────────────────────────

@Composable
private fun RunningView(state: ProvisionWizardState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.width(24.dp))
        Text(
            text = "Provisioning… ${
                if (state.results.isEmpty()) "starting"
                else "${state.results.count { it.isOk }} done"
            }",
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
