package com.warungtomyam.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.data.ApiClient
import com.warungtomyam.pos.data.ApiResult
import com.warungtomyam.pos.data.SecureStorage
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.UiStrings
import com.warungtomyam.pos.ui.i18n.uiStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PendingApprovalViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val secureStorage: SecureStorage
) : ViewModel() {

    var status by mutableStateOf("PENDING")
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var pollingJob: Job? = null

    fun startPolling(strings: UiStrings, onApproved: (isSecondaryAdmin: Boolean) -> Unit, onRevoked: () -> Unit) {
        if (pollingJob?.isActive == true) return

        pollingJob = viewModelScope.launch {
            val deviceId = secureStorage.getDeviceId()
            while (true) {
                val result = apiClient.pollDeviceStatus(deviceId)
                when (result) {
                    is ApiResult.Success -> {
                        status = result.data.status
                        errorMessage = null
                        when (result.data.status) {
                            "APPROVED" -> {
                                // The invite the device scanned decides the role. A secondary
                                // admin gets a session token (and admin role); ordering staff
                                // gets an api_key. Correct the role now that we know which.
                                val sessionToken = result.data.sessionToken
                                val apiKey = result.data.apiKey
                                val isSecondaryAdmin = sessionToken != null ||
                                    result.data.role == "ADMIN_SECONDARY"
                                if (sessionToken != null) {
                                    secureStorage.setSessionToken(sessionToken)
                                    secureStorage.setRole(SecureStorage.Role.ADMIN_SECONDARY)
                                } else if (apiKey != null) {
                                    secureStorage.setApiKey(apiKey)
                                }
                                onApproved(isSecondaryAdmin)
                                return@launch
                            }
                            "REVOKED" -> {
                                secureStorage.clearAll()
                                onRevoked()
                                return@launch
                            }
                            // PENDING — continue polling
                        }
                    }
                    is ApiResult.NetworkError -> {
                        errorMessage = strings.connectionRetryingMsg
                    }
                    is ApiResult.Error -> {
                        errorMessage = result.message
                    }
                }
                delay(10_000L) // Poll every 10 seconds
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
}

/**
 * Screen shown while waiting for admin approval. Polls every 10s.
 */
@Composable
fun PendingApprovalScreen(
    onApproved: (isSecondaryAdmin: Boolean) -> Unit,
    onRevoked: () -> Unit,
    viewModel: PendingApprovalViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    DisposableEffect(Unit) {
        viewModel.startPolling(strings, onApproved, onRevoked)
        onDispose { viewModel.stopPolling() }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = strings.waitingApprovalTitle,
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = strings.waitingApprovalDesc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (viewModel.errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = viewModel.errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
