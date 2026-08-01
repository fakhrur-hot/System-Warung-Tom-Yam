package com.razstudio.pos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.razstudio.pos.BuildConfig
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.i18n.uiStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OrderingConnectViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val secureStorage: SecureStorage,
    private val appConfig: com.razstudio.pos.data.AppConfigStore,
) : ViewModel() {

    /**
     * Point this device at a LAN Server before registering (task 7.2, Requirement 5.1).
     *
     * Writing the address first is what makes the subsequent `apiClient.register` reach the phone on
     * the counter instead of a Supabase project — `ApiClient.baseUrl()` prefers this value whenever
     * it is set. Persisted rather than passed along, because every later call needs it too, and
     * because `LanServerLocator` starts its recovery from exactly this stored value (task 7.3).
     */
    fun useLanServer(host: String, port: Int) {
        appConfig.setLanServerUrl("http://$host:$port")
    }

    /**
     * Accept a hand-typed address (task 7.2).
     *
     * The fallback exists because the camera is the single most likely thing to fail at the counter
     * — a cracked lens, a dim corner, a screen too bright to focus on — and without it a café whose
     * scanner will not lock has no route to pairing at all. Accepts `192.168.43.1`,
     * `192.168.43.1:8765` or a full `http://…`, because an operator reading the pairing screen aloud
     * will type whichever they saw.
     */
    fun useManualAddress(raw: String): Boolean {
        val cleaned = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (cleaned.isBlank()) return false

        val host = cleaned.substringBefore(':').trim()
        val port = cleaned.substringAfter(':', "").trim().toIntOrNull() ?: DEFAULT_LAN_PORT
        if (host.isBlank() || port !in 1..65535) return false

        useLanServer(host, port)
        return true
    }

    private companion object {
        /** Matches LanServer.PORT; used when the operator types a bare address. */
        const val DEFAULT_LAN_PORT = 8765
    }

    var inviteInput by mutableStateOf("")
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        // Pre-fill from a deep-link invite (consumed once) so an arriving device can register
        // without typing. The token is the raw value; extractToken accepts it directly.
        com.razstudio.pos.ui.navigation.DeepLinkInvite.consume()?.let { inviteInput = it }
    }

    fun onInputChanged(value: String) {
        inviteInput = value
        errorMessage = null
    }

    /**
     * Extract the invite token from input. Accepts either:
     * - Full URL: https://host/join?invite=TOKEN
     * - Raw token string
     */
    fun extractToken(strings: UiStrings): String? {
        val input = inviteInput.trim()
        if (input.isBlank()) {
            errorMessage = strings.emptyInviteError
            return null
        }

        // Try to extract from URL pattern
        val urlPattern = Regex("""[?&]invite=([^&\s]+)""")
        val match = urlPattern.find(input)
        if (match != null) {
            return match.groupValues[1]
        }

        // If it looks like a URL but has no invite param, it's invalid
        if (input.startsWith("http")) {
            errorMessage = strings.invalidInviteUrlError
            return null
        }

        // Assume raw token (alphanumeric, at least 8 chars)
        if (input.length < 8) {
            errorMessage = strings.tokenTooShortError
            return null
        }

        return input
    }

    @Suppress("DEPRECATION")
    suspend fun register(androidId: String, strings: UiStrings): Boolean {
        val token = extractToken(strings) ?: return false

        isLoading = true
        errorMessage = null

        val deviceId = secureStorage.getDeviceId()
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val appVersion = BuildConfig.VERSION_NAME

        val result = apiClient.register(
            inviteToken = token,
            deviceId = deviceId,
            deviceModel = deviceModel,
            androidId = androidId,
            appVersion = appVersion
        )

        isLoading = false

        return when (result) {
            is ApiResult.Success -> {
                val statusResult = apiClient.pollDeviceStatus(deviceId)
                val resolvedRole = if (statusResult is ApiResult.Success && statusResult.data.role == "ADMIN_SECONDARY") {
                    SecureStorage.Role.ADMIN_SECONDARY
                } else {
                    SecureStorage.Role.ORDERING
                }
                secureStorage.setRole(resolvedRole)
                true
            }
            is ApiResult.Error -> {
                errorMessage = when (result.code) {
                    "INVALID_INVITE" -> strings.invalidInviteError
                    else -> result.message
                }
                false
            }
            is ApiResult.NetworkError -> {
                errorMessage = strings.networkError
                false
            }
        }
    }
}

/**
 * Ordering device connection screen: enter invitation URL/token → register → pending approval.
 */
@Composable
fun OrderingConnectScreen(
    onRegistered: () -> Unit,
    onBack: () -> Unit,
    viewModel: OrderingConnectViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    @Suppress("DEPRECATION")
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    var showScanner by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var showManualAddress by remember { mutableStateOf(false) }
    var manualAddress by remember { mutableStateOf("") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // Full-screen scanner: on decode, fill the invite field and auto-register.
    if (showScanner) {
        QrScannerScreen(
            hasCameraPermission = hasCameraPermission,
            onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            onQrDecoded = { text ->
                showScanner = false
                // A LAN pairing code carries where the server IS, not just a token, so the address
                // has to be stored before registering. Anything else falls through to the cloud
                // invite path unchanged — the scanner is shared, and a café QR or a payment code
                // landing in frame must not be mistaken for a pairing.
                val lan = com.razstudio.pos.data.lan.PairingQrPayload.decode(text)
                if (lan != null) {
                    viewModel.useLanServer(lan.host, lan.port)
                    viewModel.onInputChanged(lan.pairingToken)
                } else {
                    viewModel.onInputChanged(text)
                }
                scope.launch {
                    // A typed address must be stored before register() picks a base URL.
                    if (showManualAddress && manualAddress.isNotBlank()) {
                        viewModel.useManualAddress(manualAddress)
                    }
                    if (viewModel.register(androidId, strings)) {
                        onRegistered()
                    }
                }
            },
            onCancel = { showScanner = false }
        )
        return
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
                text = strings.staffConnectionTitle,
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = strings.staffConnectionSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Primary path: scan the admin's invite QR.
            Button(
                onClick = {
                    if (hasCameraPermission) {
                        showScanner = true
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        showScanner = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.isLoading
            ) {
                Text("Scan QR to connect")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "or enter the code manually",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = viewModel.inviteInput,
                onValueChange = { viewModel.onInputChanged(it) },
                label = { Text(strings.invitationLabel) },
                placeholder = { Text("https://...pages.dev/join?invite=...") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.isLoading
            )

            // ── Task 7.2: manual address, for when the camera will not cooperate ─────────────
            // Collapsed by default: a Cloud café never needs it, and an always-visible host/port
            // field on the first screen a staff device sees invites someone to fill it in and break
            // a working cloud pairing.
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { showManualAddress = !showManualAddress }) {
                Text(if (showManualAddress) "Hide server address" else "Enter address manually")
            }
            if (showManualAddress) {
                OutlinedTextField(
                    value = manualAddress,
                    onValueChange = { manualAddress = it },
                    label = { Text("Server address") },
                    placeholder = { Text("192.168.43.1:8765") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !viewModel.isLoading,
                )
                Text(
                    text = "Shown on the admin device under Pair a staff device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (viewModel.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = viewModel.errorMessage!!,
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
                            if (viewModel.register(androidId, strings)) {
                                onRegistered()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = viewModel.inviteInput.isNotBlank()
                ) {
                    Text(strings.registerDeviceButton)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onBack) {
                Text(strings.commonBack)
            }
        }
    }
}
