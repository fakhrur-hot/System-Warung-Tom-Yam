package com.razstudio.pos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.ads.MobileAds
import com.razstudio.pos.data.local.LocalPrefs
import com.razstudio.pos.ui.navigation.AppNavGraph
import com.razstudio.pos.ui.screens.PermissionGate
import com.razstudio.pos.ui.navigation.DeepLinkInvite
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.theme.WarungTomYamTheme
import com.razstudio.pos.ui.viewmodels.StartupViewModel
import com.razstudio.pos.ui.util.FullscreenMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val startupViewModel: StartupViewModel by viewModels()

    @Inject lateinit var localPrefs: LocalPrefs

    /**
     * Re-assert immersive mode every time the window regains focus.
     *
     * Android shows the system bars again on its own after dialogs, keyboards, permission
     * prompts and edge swipes. Setting it once in onCreate produces a till that is fullscreen
     * until the first time anything happens, which is worse than not offering the option.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) FullscreenMode.apply(this, localPrefs.fullscreenMode)
    }

    // POST_NOTIFICATIONS runtime prompt (Android 13+). Without it, the persistent
    // foreground notification and live new-order alerts are silently suppressed.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not; the service still runs either way */ }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FullscreenMode.apply(this, localPrefs.fullscreenMode)

        // Initialize Google Mobile Ads SDK (one-time, safe to call multiple times)
        MobileAds.initialize(this)

        ensureNotificationPermission()

        // Capture an invite or recover token arriving via deep link (https://<host>/join?invite=TOKEN or ?recover=TOKEN)
        val deepLinkInvite = intent?.data?.getQueryParameter("invite")?.takeIf { it.isNotBlank() }
        val deepLinkRecover = intent?.data?.getQueryParameter("recover")?.takeIf { it.isNotBlank() }
        if (deepLinkInvite != null) {
            DeepLinkInvite.pendingToken = deepLinkInvite
        }
        if (deepLinkRecover != null) {
            DeepLinkInvite.pendingRecoverToken = deepLinkRecover
        }

        // Resolve start destination on IO thread (EncryptedSharedPreferences / Keystore reads).
        startupViewModel.resolve(deepLinkInvite, deepLinkRecover)

        setContent {
            WarungTomYamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val startupState by startupViewModel.state.collectAsState()

                    when (val s = startupState) {
                        is StartupViewModel.State.Loading -> {
                            // Show a centered spinner while Keystore reads complete
                            // (typically <100ms, avoids any main-thread jank on cold boot).
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        is StartupViewModel.State.Ready -> {
                            // Adopt the café-wide default language for this device's role, but
                            // only if the operator hasn't already picked one on this device.
                            val languageViewModel: LanguageViewModel = hiltViewModel()
                            LaunchedEffect(Unit) { languageViewModel.bootstrapCafeDefault() }
                            val navController = rememberNavController()
                            // Mandatory permission gate on first launch: location, nearby devices
                            // and notifications, on a screen carrying the app's own logo. It wraps
                            // the graph rather than being a route in it, so no destination -- deep
                            // link included -- can slip past it, and it disappears for good once
                            // everything is granted. See PermissionGate.
                            PermissionGate {
                                AppNavGraph(
                                    navController = navController,
                                    startDestination = s.startDestination
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
