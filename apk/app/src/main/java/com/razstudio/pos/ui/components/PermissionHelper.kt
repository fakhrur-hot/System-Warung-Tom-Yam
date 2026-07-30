package com.razstudio.pos.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Self-contained runtime permission helper.
 * Tracks denial count per permission. After denied twice, shows an "Open Settings"
 * dialog instead of requesting again (Android won't show the system dialog after
 * permanent denial anyway).
 *
 * Usage:
 * ```
 * val permHelper = rememberPermissionHelper("android.permission.POST_NOTIFICATIONS")
 * Button(onClick = { permHelper.request() }) { Text("Grant") }
 * permHelper.SettingsDialog()
 * ```
 */
class PermissionHelperState(
    private val permission: String,
    private val context: Context,
    private val launcher: (String) -> Unit
) {
    var denialCount by mutableIntStateOf(0)
        private set

    var showSettingsDialog by mutableStateOf(false)
        private set

    var isGranted by mutableStateOf(false)
        private set

    fun request() {
        if (denialCount >= 2) {
            showSettingsDialog = true
        } else {
            launcher(permission)
        }
    }

    fun onResult(granted: Boolean) {
        isGranted = granted
        if (!granted) {
            denialCount++
            if (denialCount >= 2) {
                showSettingsDialog = true
            }
        }
    }

    fun dismissDialog() {
        showSettingsDialog = false
    }

    fun openAppSettings() {
        showSettingsDialog = false
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Composable
fun rememberPermissionHelper(permission: String): PermissionHelperState {
    val context = LocalContext.current

    var state by remember { mutableStateOf<PermissionHelperState?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        state?.onResult(granted)
    }

    if (state == null) {
        state = PermissionHelperState(
            permission = permission,
            context = context,
            launcher = { perm -> launcher.launch(perm) }
        )
    }

    return state!!
}

/**
 * Shows the "Open App Settings" dialog when the permission has been denied twice.
 * Call this composable in your screen alongside the permission helper.
 */
@Composable
fun PermissionSettingsDialog(
    state: PermissionHelperState,
    title: String = "Permission Required",
    message: String = "This permission has been denied. Please enable it in App Settings to continue."
) {
    if (state.showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { state.dismissDialog() },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { state.openAppSettings() }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { state.dismissDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }
}
