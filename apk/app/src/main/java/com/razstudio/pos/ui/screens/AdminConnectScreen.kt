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
    private val secureStorage: SecureStorage,
    private val appConfig: com.razstudio.pos.data.AppConfigStore,
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

    /** Localised variant — the screen maps the key, so the ViewModel needs no UiStrings. */
    fun reportErrorKey(key: String) {
        errorKey = key
        errorMessage = null
    }

    /**
     * Take the café's backend from a scanned Owner Recovery QR, if it carries one.
     *
     * Both params must be present and decode cleanly, or nothing is written — a half-applied
     * connection (a URL with no anon key) would leave the device in a state Setup cannot detect as
     * unconfigured, and every call would fail 401 with no way back except clearing app data.
     */
    private fun adoptBackendFrom(scanned: String) {
        val api = queryParam(scanned, ApiClient.QR_PARAM_API) ?: return
        val key = queryParam(scanned, ApiClient.QR_PARAM_KEY) ?: return
        appConfig.adoptBackendFromRecoveryQr(api, key, websiteUrl = originOf(scanned))
    }

    /**
     * Fill in the café name once the connection is live.
     *
     * Adoption brings across the two things only the QR can carry — the backend and the café's site.
     * The name is not one of them: it is already in the café's own `settings`, so it is fetched
     * rather than transported, which keeps a renamed café from being stamped with an old name by a
     * QR printed months ago. Best-effort: a failure here leaves the device connected and working,
     * with the name blank until the next branding fetch.
     */
    private suspend fun adoptCafeNameFromBranding() {
        if (appConfig.cafeName().isNotBlank()) return
        val result = apiClient.getBranding()
        if (result is ApiResult.Success) {
            result.data.cafeName.takeIf { it.isNotBlank() }?.let { appConfig.setCafeName(it) }
        }
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
    suspend fun recover(recoveryToken: String, scanned: String = ""): Boolean {
        errorMessage = null
        errorKey = null

        // An owner who already holds their QR should never be forced through the Setup Wizard. The
        // QR carries the café's backend alongside the token precisely so an unconfigured device can
        // adopt it here and sign straight in. Ignored when this device already serves a café — see
        // AppConfigStore.adoptBackendFromRecoveryQr for why that refusal matters.
        if (!apiClient.isBackendConfigured() && scanned.isNotBlank()) {
            adoptBackendFrom(scanned)
        }

        // Checked before the round trip, not after. With no backend the call cannot succeed for any
        // key, and reporting the generic network failure would send the owner hunting for a bad QR —
        // the key is fine; the app simply has nowhere to send it.
        if (!apiClient.isBackendConfigured()) {
            errorKey = "NO_BACKEND"
            return false
        }

        isLoading = true
        val deviceId = secureStorage.getDeviceId()
        val deviceModel = android.os.Build.MODEL ?: "Phone"
        val result = apiClient.recoverAdmin(recoveryToken, deviceId, deviceModel)
        isLoading = false
        return when (result) {
            is ApiResult.Success -> {
                secureStorage.setSessionToken(result.data)
                secureStorage.setRole(SecureStorage.Role.ADMIN)
                // The session exists now, so branding is reachable — this is the first moment the
                // café's name can be filled in on a device that arrived with nothing but a QR.
                adoptCafeNameFromBranding()
                true
            }
            is ApiResult.Error -> {
                if (result.code == "INVALID_RECOVERY") {
                    errorKey = "INVALID_OWNER_KEY"
                    errorMessage = null
                } else {
                    errorMessage = result.message
                    errorKey = null
                }
                false
            }
            is ApiResult.NetworkError -> {
                errorMessage = null
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
            // Was `errorKey = "NOT_INVITE"` immediately followed by `errorKey = null`, which cleared
            // the key it had just set and left the screen showing nothing at all — the scan looked
            // like it had silently succeeded. The second line was meant to clear errorMessage.
            errorKey = "NOT_INVITE"
            errorMessage = null
            return false
        }
        errorMessage = null
        errorKey = null
        if (!apiClient.isBackendConfigured()) {
            errorKey = "NO_BACKEND"
            return false
        }

        isLoading = true
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
                // See OrderingConnectScreen: the returned id is the server's row key, not ours.
                secureStorage.setServerDeviceId(result.data.deviceId)
                val statusResult = apiClient.pollDeviceStatus(result.data.deviceId)
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
                if (result.code == "INVALID_INVITE") {
                    errorKey = "INVALID_INVITE"
                    errorMessage = null
                } else {
                    errorMessage = result.message
                    errorKey = null
                }
                false
            }
            is ApiResult.NetworkError -> {
                errorMessage = null
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
                    errorKey = "ADMIN_EXISTS"
                }
                "INVALID_KEY" -> {
                    errorMessage = null
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
            errorMessage = null
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
/**
 * Read one URL-encoded query parameter out of a scanned string.
 *
 * Hand-rolled rather than `Uri.parse`, because the input is whatever a camera decoded: it may be a
 * bare token, a truncated link, or not a URL at all, and `Uri.parse` answers those with nulls and
 * empty strings instead of an error. Returns null unless the parameter is genuinely present and
 * non-blank after decoding.
 */
/**
 * The `scheme://host[:port]` of a scanned link, or "" if it isn't one.
 *
 * The Owner Recovery QR is built by the backend as `${WEBSITE_ORIGIN}/join?recover=…`, so the café's
 * Cloudflare Pages site is already present in the link — this reads it back out instead of adding a
 * third query parameter for something the URL states by construction.
 */
internal fun originOf(input: String): String {
    val m = Regex("^(https?://[^/?#\\s]+)").find(input.trim()) ?: return ""
    return m.groupValues[1]
}

internal fun queryParam(input: String, name: String): String? {
    val raw = Regex("[?&]${Regex.escape(name)}=([^&\\s]+)").find(input.trim())
        ?.groupValues?.get(1) ?: return null
    return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }
        .getOrNull()?.takeIf { it.isNotBlank() }
}

internal fun extractRecoverToken(input: String): String? {
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
                viewModel.reportErrorKey("NOT_KEY_OR_INVITE")
            looksLikeInvite(text) ->
                scope.launch { if (viewModel.registerViaInvite(androidId, text)) onSecondaryRegistered() }
            else -> {
                val tok = extractRecoverToken(text) ?: text
                // The full scanned string is passed alongside the token: it may carry the café's
                // backend, which is what lets an unconfigured device skip the Setup Wizard.
                scope.launch { if (viewModel.recover(tok, scanned = text)) onConnected() }
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
                    viewModel.reportErrorKey("QR_UNREADABLE")
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
            promptText = strings.scanOwnerKeyPrompt,
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
        "INVALID_OWNER_KEY" -> strings.invalidOwnerKeyError
        "INVALID_INVITE" -> strings.invalidOrExpiredInvite
        "NOT_KEY_OR_INVITE" -> strings.notAValidKeyOrInvite
        "NOT_INVITE" -> strings.notAValidInviteQr
        "QR_UNREADABLE" -> strings.couldNotReadQrFromImage
        "NO_BACKEND" -> strings.noBackendConfiguredError
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
                text = strings.signInWithOwnerKeyTitle,
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
                    Text(strings.scanOwnerKeyQrAction)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2) Pick a saved QR photo (jpg/png) and decode it.
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(strings.chooseSavedQrImageAction)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = strings.orEnterKeyManually,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 3) Type / paste the long owner key.
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(strings.ownerKeyLabel) },
                    placeholder = { Text(strings.ownerKeyPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { handleCredential(keyInput) },
                    enabled = keyInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(strings.signInAction)
                }

                // ── Secondary Admin ──────────────────────────────────────────────
                // A Secondary Admin joins with an invite QR from the Main Admin (not the owner
                // key) and gains admin access once the Main Admin approves the request.
                Spacer(modifier = Modifier.height(24.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = strings.secondaryAdminHeading,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.secondaryAdminJoinHelp,
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
                    Text(strings.scanInviteQrAction)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = inviteInput,
                    onValueChange = { inviteInput = it },
                    label = { Text(strings.inviteLinkOrCodeLabel) },
                    placeholder = { Text(strings.inviteLinkOrCodePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { handleCredential(inviteInput) },
                    enabled = inviteInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(strings.registerAsSecondaryAdminAction)
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
