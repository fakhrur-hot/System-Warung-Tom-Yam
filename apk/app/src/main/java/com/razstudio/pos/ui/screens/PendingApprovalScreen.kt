package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.ui.components.AdBannerFooter
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.i18n.uiStrings
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

    val isSecondaryAdmin: Boolean
        get() = secureStorage.getRole() == SecureStorage.Role.ADMIN_SECONDARY

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var pollingJob: Job? = null

    fun startPolling(strings: UiStrings, onApproved: (isSecondaryAdmin: Boolean) -> Unit, onRevoked: () -> Unit) {
        if (pollingJob?.isActive == true) return

        pollingJob = viewModelScope.launch {
            // The server knows this device by the id `register` returned, not by our local UUID.
            val deviceId = secureStorage.getServerDeviceId()
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
        // The most policy-safe surface in the app: there is not a single interactive element here,
        // and the device waits on this screen indefinitely while it polls for approval every 10s.
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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

                val isSecAdmin = viewModel.isSecondaryAdmin
                val descText = if (isSecAdmin) {
                    when (language) {
                        com.razstudio.pos.ui.i18n.AppLanguage.MY -> "Peranti admin pembantu anda telah didaftarkan. Admin utama akan meluluskan sambungan anda tidak lama lagi."
                        com.razstudio.pos.ui.i18n.AppLanguage.ZH -> "您的副管理员设备已注册。主管理员将很快批准您的连接。"
                        com.razstudio.pos.ui.i18n.AppLanguage.TA -> "உங்கள் துணை நிர்வாகி சாதனம் பதிவு செய்யப்பட்டுள்ளது. முதன்மை நிர்வாகி விரைவில் உங்கள் இணைப்பை அங்கீகரிப்பார்."
                        com.razstudio.pos.ui.i18n.AppLanguage.TH -> "อุปกรณ์ผู้ดูแลระบบสำรองของคุณได้รับการลงทะเบียนแล้ว ผู้ดูแลระบบหลักจะอนุมัติการเชื่อมต่อของคุณในไม่ช้า"
                        else -> "Your secondary admin device has been registered. The main admin will approve your connection shortly."
                    }
                } else {
                    strings.waitingApprovalDesc
                }

                Text(
                    text = descText,
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

            AdBannerFooter()
        }
    }
}
