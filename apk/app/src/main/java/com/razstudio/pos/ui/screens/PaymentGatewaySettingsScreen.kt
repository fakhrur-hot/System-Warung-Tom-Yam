package com.razstudio.pos.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.tableview.paymentMethodLabel
import com.razstudio.pos.ui.viewmodels.PaymentGatewayViewModel

/**
 * Admin gateway settings, per provider. (PG-REQ-2, PG-REQ-8, task 7.1)
 *
 * The credential form is rendered from the **field spec the server declares** for the selected
 * provider, not hardcoded. That is the whole point of the rework: Touch 'n Go issues merchant id +
 * verify/secret key, a bank's DuitNow rail issues OAuth client id + secret, and the real field
 * names for both are only known once merchant onboarding completes. A hardcoded form would need an
 * app release to learn them.
 *
 * A secret already stored shows a masked "already set" placeholder and an empty box: typing
 * nothing leaves it untouched, because no credential value is ever sent back to the client to
 * round-trip. Standalone screen rather than a section of [AdminSettingsScreen] for the same reason
 * as before — that screen stages every field and commits on one Save, which does not fit
 * credentials that must reach the server the moment they are entered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentGatewaySettingsScreen(
    viewModel: PaymentGatewayViewModel = hiltViewModel(),
    onBack: () -> Unit,
    languageViewModel: LanguageViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val snackbarHostState = remember { SnackbarHostState() }
    var showSandboxOffConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.paymentGatewaySettingsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.commonBack)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        val provider = uiState.selected

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (!uiState.keystoreHealthy) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        strings.paymentGatewayKeystoreUnhealthy,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // Provider picker. Scrollable rather than wrapped: the list grows as providers are
            // added, and a café should never lose one off the edge of the screen.
            Text(strings.paymentGatewayProviderLabel, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.providers.forEach { p ->
                    FilterChip(
                        selected = p.provider == uiState.selectedProvider,
                        onClick = { viewModel.selectProvider(p.provider) },
                        label = { Text(p.displayName) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            if (provider == null) {
                Text(
                    strings.paymentGatewayNoProviders,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // A provider whose adapter is still a fail-closed placeholder says so plainly, rather
            // than presenting a form that looks like it will start taking payments.
            if (provider.status != "AVAILABLE") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            strings.paymentGatewayAwaitingOnboarding,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        provider.unavailableReason?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            provider.credentialFields.forEach { field ->
                val alreadySet = provider.fieldsSet[field.key] == true
                OutlinedTextField(
                    value = uiState.credentialInputs[field.key] ?: "",
                    onValueChange = { viewModel.updateCredential(field.key, it) },
                    label = { Text(field.label) },
                    placeholder = {
                        if (alreadySet) Text(strings.paymentGatewayKeySetPlaceholder)
                    },
                    supportingText = field.hint?.let { { Text(it) } },
                    visualTransformation = if (field.secret) {
                        PasswordVisualTransformation()
                    } else {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }

            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings.paymentGatewaySandboxLabel, style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = uiState.isSandbox,
                    onCheckedChange = { newValue ->
                        // Only the OFF direction (going live) is gated. Turning sandbox back on is
                        // the safe direction and needs no confirmation.
                        if (!newValue) showSandboxOffConfirm = true else viewModel.updateSandbox(true)
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings.paymentGatewayEnabledLabel, style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = uiState.isEnabled,
                    // Disabled outright for a provider with no working adapter — the server would
                    // force it false anyway, and a toggle that silently undoes itself is worse
                    // than one that plainly cannot be moved.
                    enabled = uiState.selectedIsAvailable,
                    onCheckedChange = viewModel::updateEnabled,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(strings.paymentGatewayChannelsHeader, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            viewModel.configurableMethods.forEach { method ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(paymentMethodLabel(method, strings))
                    Switch(
                        checked = method in uiState.enabledMethods,
                        onCheckedChange = { checked -> viewModel.toggleMethod(method, checked) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = viewModel::save,
                enabled = uiState.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                } else {
                    Text(strings.commonSave)
                }
            }
        }
    }

    if (showSandboxOffConfirm) {
        AlertDialog(
            onDismissRequest = { showSandboxOffConfirm = false },
            title = { Text(strings.paymentGatewaySandboxOffConfirmTitle) },
            text = {
                Text(
                    strings.paymentGatewaySandboxOffConfirmBody,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateSandbox(false)
                    showSandboxOffConfirm = false
                }) { Text(strings.commonConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { showSandboxOffConfirm = false }) { Text(strings.commonCancel) }
            },
        )
    }
}
