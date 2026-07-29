package com.warungtomyam.pos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.warungtomyam.pos.BuildConfig
import com.warungtomyam.pos.data.ApiClient
import com.warungtomyam.pos.data.ApiResult
import com.warungtomyam.pos.data.SecureStorage
import com.warungtomyam.pos.util.CaesarCipher
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.uiStrings
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdminConnectViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val secureStorage: SecureStorage
) : ViewModel() {

    var rotatingKey by mutableStateOf("")
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // Tags a known error so the Composable can show a localized message.
    // Null means either no error, or errorMessage already holds a
    // server-supplied message that can't be localized locally.
    var errorKey by mutableStateOf<String?>(null)
        private set

    /**
     * Debug-only: the CURRENT café's real name, fetched live rather than hardcoded —
     * a hardcoded name (e.g. an early test café's name) silently stops matching the
     * backend's debug-handshake check the moment someone renames the café in Admin
     * Settings, which is exactly what happened once already. Null while loading or if
     * branding isn't configured yet (the debug quick-connect list stays empty then).
     */
    var debugCafeName by mutableStateOf<String?>(null)
        private set

    fun loadDebugCafeName() {
        viewModelScope.launch {
            when (val result = apiClient.getBranding()) {
                is ApiResult.Success -> {
                    debugCafeName = result.data.cafeName.ifBlank { null }
                }
                else -> debugCafeName = null
            }
        }
    }

    fun onKeyChanged(key: String) {
        // Only allow digits, max 6 characters
        if (key.length <= 6 && key.all { it.isDigit() }) {
            rotatingKey = key
            errorMessage = null
            errorKey = null
        }
    }

    suspend fun connect(): Boolean {
        if (rotatingKey.length != 6) {
            errorMessage = "Enter the 6-digit rotating key from the website"
            errorKey = "ENTER_KEY"
            return false
        }

        isLoading = true
        errorMessage = null
        errorKey = null

        val deviceId = secureStorage.getDeviceId()
        val result = apiClient.adminHandshake(deviceId, rotatingKey)

        isLoading = false
        return applyHandshakeResult(result)
    }

    /**
     * Debug-only: claim the admin slot with the café's plaintext name (deciphered from
     * whatever was typed/tapped) instead of the rotating key. Same session-token result
     * shape as [connect] — the caller only needs to distinguish which triggered success.
     */
    suspend fun connectDebug(cafeName: String): Boolean {
        isLoading = true
        errorMessage = null
        errorKey = null

        val deviceId = secureStorage.getDeviceId()
        val result = apiClient.adminHandshakeDebug(deviceId, cafeName)

        isLoading = false
        return applyHandshakeResult(result, invalidKeyErrorKey = "DEBUG_INVALID_NAME")
    }

    /**
     * Restore Main Admin on this device using the permanent owner-recovery token (scanned
     * from the Owner Recovery QR). QR-only: the token alone grants Main Admin.
     */
    suspend fun recover(recoveryToken: String): Boolean {
        isLoading = true
        errorMessage = null
        errorKey = null
        val deviceId = secureStorage.getDeviceId()
        val deviceModel = android.os.Build.MODEL ?: "Phone"
        val result = apiClient.recoverAdmin(recoveryToken, deviceId, deviceModel)
        isLoading = false
        return when (result) {
            is ApiResult.Success -> {
                secureStorage.setSessionToken(result.data)
                secureStorage.setRole(SecureStorage.Role.ADMIN)
                true
            }
            is ApiResult.Error -> {
                errorMessage = if (result.code == "INVALID_RECOVERY") "Invalid recovery key." else result.message
                errorKey = null
                false
            }
            is ApiResult.NetworkError -> {
                errorMessage = "Network error. Check your connection and try again."
                errorKey = "NETWORK"
                false
            }
        }
    }

    private fun applyHandshakeResult(
        result: ApiResult<String>,
        invalidKeyErrorKey: String = "INVALID_KEY"
    ): Boolean = when (result) {
        is ApiResult.Success -> {
            secureStorage.setSessionToken(result.data)
            secureStorage.setRole(SecureStorage.Role.ADMIN)
            true
        }
        is ApiResult.Error -> {
            when (result.code) {
                "ADMIN_EXISTS" -> {
                    errorMessage = "An admin device is already registered. Only one admin device is allowed."
                    errorKey = "ADMIN_EXISTS"
                }
                "INVALID_KEY" -> {
                    errorMessage = "Invalid or expired key. Check the website for the current key."
                    errorKey = invalidKeyErrorKey
                }
                else -> {
                    errorMessage = result.message
                    errorKey = null
                }
            }
            false
        }
        is ApiResult.NetworkError -> {
            errorMessage = "Network error. Check your connection and try again."
            errorKey = "NETWORK"
            false
        }
    }
}

/**
 * Admin connection screen: enter rotating key from website → handshake → store token.
 */
/** Pull the recover token from a pasted "…/join?recover=<token>" link, or accept a raw
 *  32-hex token; null if neither. */
private fun extractRecoverToken(input: String): String? {
    val t = input.trim()
    Regex("[?&]recover=([a-fA-F0-9]+)").find(t)?.let { return it.groupValues[1] }
    if (Regex("^[a-fA-F0-9]{32}$").matches(t)) return t
    return null
}

@Composable
fun AdminConnectScreen(
    onConnected: () -> Unit,
    onBack: () -> Unit,
    viewModel: AdminConnectViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    var recoverInput by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    var debugCipherText by remember { mutableStateOf("") }

    if (BuildConfig.DEBUG) {
        LaunchedEffect(Unit) { viewModel.loadDebugCafeName() }
    }

    val localizedError = when (viewModel.errorKey) {
        "ENTER_KEY" -> strings.enterRotatingKeyError
        "ADMIN_EXISTS" -> strings.adminExistsError
        "INVALID_KEY" -> strings.invalidKeyError
        "DEBUG_INVALID_NAME" -> strings.debugAdminInvalidNameError
        "NETWORK" -> strings.networkError
        else -> viewModel.errorMessage
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = strings.adminConnectTitle,
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = strings.adminConnectSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = viewModel.rotatingKey,
                onValueChange = { viewModel.onKeyChanged(it) },
                label = { Text(strings.rotatingKeyLabel) },
                placeholder = { Text("000000") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.isLoading
            )

            if (localizedError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = localizedError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (viewModel.isLoading) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            if (viewModel.connect()) {
                                onConnected()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = viewModel.rotatingKey.length == 6
                ) {
                    Text(strings.connectButton)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Owner recovery — restore Main Admin on this fresh device using the permanent
            // Owner Recovery key/link (from the old phone's Devices screen). Scanning the QR
            // opens this same link; it can also be pasted here.
            Text(
                text = "Lost or broke the main admin phone?",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(6.dp))
            androidx.compose.material3.OutlinedTextField(
                value = recoverInput,
                onValueChange = { recoverInput = it },
                label = { Text("Owner recovery key or link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                onClick = {
                    val tok = extractRecoverToken(recoverInput) ?: recoverInput.trim()
                    scope.launch { if (viewModel.recover(tok)) onConnected() }
                },
                enabled = recoverInput.isNotBlank() && !viewModel.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Recover Main Admin (owner)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onBack) {
                Text(strings.commonBack)
            }

            // Debug-build-only shortcut — compiled away in release (BuildConfig.DEBUG is a
            // compile-time constant, so R8/dead-code elimination strips this whole block).
            // The backend independently requires its own ALLOW_DEBUG_ADMIN secret, so this
            // stays inert even if accidentally left in a debug build pointed at a real café.
            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(24.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = strings.debugAdminSectionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.debugAdminCafeListHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Fetched live from the current branding — never a hardcoded name that
                // silently goes stale the moment the café is renamed in Admin Settings.
                viewModel.debugCafeName?.let { cafeName ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable(enabled = !viewModel.isLoading) {
                                scope.launch {
                                    if (viewModel.connectDebug(cafeName)) {
                                        onConnected()
                                    }
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = cafeName,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = debugCipherText,
                    onValueChange = { debugCipherText = it },
                    label = { Text(strings.debugAdminCipherFieldLabel) },
                    placeholder = { Text(strings.debugAdminCipherHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !viewModel.isLoading
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val decoded = CaesarCipher.decode(debugCipherText.trim())
                        scope.launch {
                            if (viewModel.connectDebug(decoded)) {
                                onConnected()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = debugCipherText.isNotBlank() && !viewModel.isLoading
                ) {
                    Text(strings.connectButton)
                }
            }
        }
    }
}
