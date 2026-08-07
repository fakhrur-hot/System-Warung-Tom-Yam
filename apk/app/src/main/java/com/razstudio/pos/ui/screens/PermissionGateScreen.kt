package com.razstudio.pos.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.razstudio.pos.R
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings

/**
 * The runtime permissions this app cannot work without, for the SDK it is running on.
 *
 * Each one is load-bearing rather than nice-to-have, which is why the gate below is mandatory:
 *
 * - **Location** — staff clock-in is geofenced to the café (`GpsHelper`/`cafe-location`), and on
 *   every Android version a Bluetooth *scan* returns nothing without it, so printer discovery dies
 *   silently without it too.
 * - **Nearby devices** (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, API 31+) — finding and connecting the
 *   receipt printer. Below API 31 the Bluetooth permissions are install-time and cannot be asked
 *   for, so the list is shorter there by design, not by omission.
 * - **Notifications** (API 33+) — the kitchen-print and new-order alerts. Denied, a print failure
 *   is invisible and an order is missed rather than merely late.
 */
private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * First-run permission gate: the app's logo, what it needs, and no way past until it has it.
 *
 * ### Why it gates rather than asks later
 *
 * Every one of these is discovered missing at the worst possible moment otherwise — a printer that
 * will not pair mid-service, a clock-in that fails with a customer waiting, a kitchen alert that
 * never arrives. Asking on the first launch, once, on a screen that explains each one, costs a
 * cashier ten seconds on the day they install and nothing afterwards.
 *
 * ### There is no "no"
 *
 * By design there is no Skip. The gate re-asks; if Android has hardened into permanent denial
 * (`shouldShowRequestPermissionRationale` false while still ungranted) it stops asking — the system
 * dialog no longer appears at that point — and sends the operator to app settings instead, which is
 * the only route left. It re-checks on resume, so coming back from settings passes the gate without
 * a restart.
 *
 * ### Why it is keyed on actual grant state, not a "seen first run" flag
 *
 * A flag would let a revoked permission through on the second launch, which is exactly the silent
 * breakage this exists to prevent. Gating on the live state means the screen appears whenever
 * something it needs is missing — on a fresh install that is the first launch, and after that only
 * if someone takes a permission away.
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val languageViewModel: LanguageViewModel = hiltViewModel()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    val required = remember { requiredPermissions() }
    // Bumped to force a re-read of the (non-observable) grant state: after a request result, and on
    // every resume so returning from app settings is noticed.
    var recheck by remember { mutableIntStateOf(0) }
    val missing = remember(recheck) {
        required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    // Set once a request round comes back still missing something: the difference between "not asked
    // yet" and "asked and refused", which decides whether asking again can even show a dialog.
    var refused by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refused = result.values.any { !it }
        recheck++
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Ask straight away on the first pass — the explanation stays on screen behind the system
    // dialog, so a cashier who dismisses it still sees what is being asked for and why.
    LaunchedEffect(Unit) {
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }

    if (missing.isEmpty()) {
        content()
        return
    }

    // Permanent denial: Android stops showing the system dialog, so re-asking is a dead button and
    // app settings is the only way through.
    val activity = context as? android.app.Activity
    val blocked = refused && activity != null && missing.none {
        activity.shouldShowRequestPermissionRationale(it)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ic_launcher_FOREGROUND, not ic_launcher. `ic_launcher` resolves to the adaptive-icon XML in
        // mipmap-anydpi-v26 on API 26+, and painterResource cannot load one — it throws
        // "Only VectorDrawables and rasterized asset types are supported", which crashed the app at
        // launch on the D3 MINI (SDK 33). The foreground layer has no XML variant, so it is a real
        // PNG at every density.
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(120.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = strings.permissionsTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strings.permissionsIntro,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Location and Nearby are listed on every SDK even where Nearby is install-time, because the
        // list is telling the operator what the app uses — not mirroring the request array.
        PermissionRow(
            label = strings.permissionLocationLabel,
            why = strings.permissionLocationWhy,
            granted = Manifest.permission.ACCESS_FINE_LOCATION !in missing,
        )
        PermissionRow(
            label = strings.permissionNearbyLabel,
            why = strings.permissionNearbyWhy,
            granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                Manifest.permission.BLUETOOTH_CONNECT !in missing,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(
                label = strings.permissionNotificationsLabel,
                why = strings.permissionNotificationsWhy,
                granted = Manifest.permission.POST_NOTIFICATIONS !in missing,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        if (blocked) {
            Text(
                text = strings.permissionBlockedNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(strings.permissionSettingsAction) }
        } else {
            Button(
                onClick = { launcher.launch(missing.toTypedArray()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(strings.permissionGrantAction) }
            Spacer(modifier = Modifier.height(8.dp))
            // Always available, not only when blocked: some OEM builds (and Sunmi tills in
            // particular) suppress the system dialog entirely, which would otherwise leave the
            // operator with a button that does nothing and no way forward.
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(strings.permissionSettingsAction) }
        }
    }
}

@Composable
private fun PermissionRow(label: String, why: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (granted) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
