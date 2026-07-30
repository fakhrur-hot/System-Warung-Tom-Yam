package com.razstudio.pos.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.razstudio.pos.BuildConfig
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.util.CaesarCipher
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import javax.inject.Inject

@HiltViewModel
class AdminConnectViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val secureStorage: SecureStorage
) : ViewModel() {

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

    /** Surface a client-side error (e.g. a chosen image that held no readable QR). */
    fun reportError(message: String) {
        errorMessage = message
        errorKey = null
    }

    /**
     * Debug-only: claim the admin slot with the café's plaintext name (deciphered from
     * whatever was typed/tapped) instead of a key. Same session-token result shape as
     * [recover] — the caller only needs to distinguish which triggered success.
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
     * Sign in as Main Admin on this device using the permanent café owner key (the
     * owner-recovery token). This is the sole production admin login: the key alone grants
     * Main Admin, whether it arrives by camera scan, saved QR image, or manual entry.
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
                errorMessage = if (result.code == "INVALID_RECOVERY") "Invalid owner key." else result.message
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

    /**
     * Onboard this device as a **Secondary Admin** using an invite token/URL from the Main Admin
     * (camera scan, saved QR image, or manual entry). Unlike the owner key — which grants Main
     * Admin immediately — an invite must be approved by the Main Admin, so a success here routes to
     * the pending-approval screen. The role baked into the invite is resolved server-side and read
     * back via [ApiClient.pollDeviceStatus]; a staff invite scanned here still registers correctly
     * (it just resolves to ORDERING and the pending screen routes it to the staff home on approval).
     */
    suspend fun registerViaInvite(androidId: String, raw: String): Boolean {
        val token = extractInviteToken(raw)
        if (token == null) {
            errorMessage = "That doesn't look like a valid invite QR or code."
            errorKey = null
            return false
        }
        isLoading = true
        errorMessage = null
        errorKey = null
        val deviceId = secureStorage.getDeviceId()
        val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val result = apiClient.register(
            inviteToken = token,
            deviceId = deviceId,
            deviceModel = deviceModel,
            androidId = androidId,
            appVersion = BuildConfig.VERSION_NAME
        )
        isLoading = false
        return when (result) {
            is ApiResult.Success -> {
                val statusResult = apiClient.pollDeviceStatus(deviceId)
                val resolvedRole = if (statusResult is ApiResult.Success &&
                    statusResult.data.role == "ADMIN_SECONDARY"
                ) {
                    SecureStorage.Role.ADMIN_SECONDARY
                } else {
                    SecureStorage.Role.ORDERING
                }
                secureStorage.setRole(resolvedRole)
                true
            }
            is ApiResult.Error -> {
                errorMessage = if (result.code == "INVALID_INVITE") "Invalid or expired invite." else result.message
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
 * Admin connection screen: sign in with the café owner key (camera scan, saved QR image,
 * or manual entry) → mint Main Admin session → store token. Debug builds also expose a
 * quick-connect shortcut.
 */
/** Pull the owner key from a pasted "…/join?recover=<token>" link, or accept a raw
 *  32-hex token; null if neither. */
private fun extractRecoverToken(input: String): String? {
    val t = input.trim()
    Regex("[?&]recover=([a-fA-F0-9]+)").find(t)?.let { return it.groupValues[1] }
    if (Regex("^[a-fA-F0-9]{32}$").matches(t)) return t
    return null
}

/** A secondary-admin (or staff) invite: a "…/join?invite=<token>" link, or a raw ≥8-char token
 *  that isn't an owner key. null if it doesn't look like an invite. */
private fun extractInviteToken(input: String): String? {
    val t = input.trim()
    Regex("[?&]invite=([^&\\s]+)").find(t)?.let { return it.groupValues[1] }
    if (t.startsWith("http")) return null // a URL but with no invite param → not an invite
    return t.takeIf { it.length >= 8 }
}

/** True when [input] carries an explicit "invite=" param — the reliable signal that a scanned QR
 *  is a Secondary-Admin invite rather than the owner key. */
private fun looksLikeInvite(input: String): Boolean =
    Regex("[?&]invite=").containsMatchIn(input.trim())

/**
 * Decode a QR code from a saved image (jpg/png) the user picked from storage. Downsamples
 * large photos first so a full-resolution camera shot doesn't OOM. Returns the decoded text,
 * or null if the image couldn't be read or held no QR. Safe to call off the main thread.
 */
private fun decodeQrFromImage(context: Context, uri: Uri): String? {
    return try {
        val resolver = context.contentResolver
        // First pass: bounds only, to pick a downsample that keeps the image manageable.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxDim = 1600
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val result = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }.decode(binary)
        result.text.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

@Composable
fun AdminConnectScreen(
    onConnected: () -> Unit,
    onBack: () -> Unit,
    onSecondaryRegistered: () -> Unit,
    viewModel: AdminConnectViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    @Suppress("DEPRECATION")
    val androidId = android.provider.Settings.Secure.getString(
        context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
    ) ?: "unknown"

    var debugCipherText by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var inviteInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        com.razstudio.pos.ui.navigation.DeepLinkInvite.consumeRecover()?.let {
            keyInput = it
        }
    }

    // Admin login is the café owner key, accepted three ways: camera scan, a saved QR
    // image (jpg/png), or manual entry. All three funnel into the same sign-in.
    var showScanner by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // Handle a credential from any source (scan / image / manual). Auto-detects which admin type:
    //  • an owner key  → sign in as Main Admin immediately (recover)
    //  • a "?invite="  → register as Secondary Admin, then wait for Main-Admin approval
    val handleCredential: (String?) -> Unit = { raw ->
        val text = raw?.trim().orEmpty()
        when {
            text.isBlank() ->
                viewModel.reportError("That doesn't look like a valid owner key or invite.")
            looksLikeInvite(text) ->
                scope.launch { if (viewModel.registerViaInvite(androidId, text)) onSecondaryRegistered() }
            else -> {
                val tok = extractRecoverToken(text) ?: text
                scope.launch { if (viewModel.recover(tok)) onConnected() }
            }
        }
    }

    // Pick a saved QR image (jpg/png) from storage and decode the owner key off the main thread.
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val decoded = withContext(Dispatchers.IO) { decodeQrFromImage(context, uri) }
                if (decoded == null) {
                    viewModel.reportError("Couldn't read a QR code from that image.")
                } else {
                    handleCredential(decoded)
                }
            }
        }
    }

    if (BuildConfig.DEBUG) {
        LaunchedEffect(Unit) { viewModel.loadDebugCafeName() }
    }

    // Full-screen scanner: on decode, auto-detect owner key vs Secondary-Admin invite and route.
    if (showScanner) {
        QrScannerScreen(
            hasCameraPermission = hasCameraPermission,
            onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            onQrDecoded = { text ->
                showScanner = false
                handleCredential(text)
            },
            onCancel = { showScanner = false },
            promptText = "Scan the owner key QR, or a Secondary Admin invite QR",
            cancelText = strings.commonBack
        )
        return
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
                text = "Sign in with the café owner key",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (viewModel.isLoading) {
                CircularProgressIndicator()
            } else {
                // 1) Scan the owner key QR with the camera.
                Button(
                    onClick = {
                        if (!hasCameraPermission) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        showScanner = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Scan owner key QR")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2) Pick a saved QR photo (jpg/png) and decode it.
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose saved QR image")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "or enter the key manually",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 3) Type / paste the long owner key.
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Owner key") },
                    placeholder = { Text("Paste or type the owner key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { handleCredential(keyInput) },
                    enabled = keyInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign in")
                }

                // ── Secondary Admin ──────────────────────────────────────────────
                // A Secondary Admin joins with an invite QR from the Main Admin (not the owner
                // key) and gains admin access once the Main Admin approves the request.
                Spacer(modifier = Modifier.height(24.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Secondary Admin",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Joining as a second admin? Scan the invite QR from the main admin — " +
                        "you'll get access once they approve this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (!hasCameraPermission) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        showScanner = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Scan invite QR")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = inviteInput,
                    onValueChange = { inviteInput = it },
                    label = { Text("Invite link or code") },
                    placeholder = { Text("Paste the invite link or code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { handleCredential(inviteInput) },
                    enabled = inviteInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Register as Secondary Admin")
                }
            }

            if (localizedError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = localizedError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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
