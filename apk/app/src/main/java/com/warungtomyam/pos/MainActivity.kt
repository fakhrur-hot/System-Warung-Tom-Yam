package com.warungtomyam.pos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.compose.rememberNavController
import com.warungtomyam.pos.data.SecureStorage
import com.warungtomyam.pos.ui.navigation.AppNavGraph
import com.warungtomyam.pos.ui.navigation.DeepLinkInvite
import com.warungtomyam.pos.ui.navigation.NavRoutes
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var secureStorage: SecureStorage

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

        // Ask for notification permission up front so orders can alert live and the
        // background service's status-bar notification is visible.
        ensureNotificationPermission()

        // Capture an invite token arriving via deep link (https://<host>/join?invite=TOKEN)
        // so a fresh device lands straight in ordering-device onboarding with it pre-filled.
        val deepLinkInvite = intent?.data?.getQueryParameter("invite")?.takeIf { it.isNotBlank() }
        if (deepLinkInvite != null) {
            DeepLinkInvite.pendingToken = deepLinkInvite
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    // Determine start destination based on stored auth state.
                    // If SecureStorage is corrupted (OEM KeyStore issue), isAuthenticated()
                    // returns false and the user lands on role selection for re-auth.
                    // A pending deep-link invite (and no existing session) jumps straight to
                    // ordering-device onboarding.
                    val startDestination = remember {
                        when {
                            deepLinkInvite != null && !secureStorage.isAuthenticated() ->
                                NavRoutes.ORDERING_CONNECT
                            secureStorage.isAuthenticated() -> {
                                when (secureStorage.getRole()) {
                                    // A secondary admin runs the same admin home as the main
                                    // admin (the printer bits are gated inside it).
                                    SecureStorage.Role.ADMIN,
                                    SecureStorage.Role.ADMIN_SECONDARY -> NavRoutes.ADMIN_HOME
                                    SecureStorage.Role.ORDERING -> NavRoutes.ORDERING_HOME
                                    null -> NavRoutes.ROLE_SELECT
                                }
                            }
                            else -> NavRoutes.ROLE_SELECT
                        }
                    }

                    AppNavGraph(
                        navController = navController,
                        startDestination = startDestination
                    )
                }
            }
        }
    }
}
