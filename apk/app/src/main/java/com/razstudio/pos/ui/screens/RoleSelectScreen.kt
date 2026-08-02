package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.razstudio.pos.data.OperatingMode
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.R
import com.razstudio.pos.ui.i18n.LanguageButton
import com.razstudio.pos.ui.theme.ThemeButton
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.SetupViewModel

/**
 * Entry point screen with three explicit, ordered actions (Requirements 2.1, 2.5).
 *
 * Layout:
 *   Top    — "Join as Ordering mode"  (filled/primary Button)
 *   Middle — "Relogin as Café Admin"  (outlined/secondary Button)
 *   Gap    — Spacer(weight(1f)) inside fillMaxSize Column — a real layout separation that
 *            survives rotation and small screens, not just visual padding (Requirement 2.5)
 *   Bottom — "Setup Wizard" + "Try Demo" (TextButtons, visually subordinate)
 *
 * Navigation destinations are preserved from the previous implementation:
 *   onOrderingConnect → ORDERING_CONNECT
 *   onAdminConnect    → ADMIN_CONNECT
 *   onSetup           → SETUP
 *   onTryDemo         → Demo Mode (bottom row, alongside Setup Wizard)
 */
@Composable
fun RoleSelectScreen(
    onAdminConnect: () -> Unit,
    onOrderingConnect: () -> Unit,
    onWirelessAp: () -> Unit = {},
    onKiosk: () -> Unit = {},
    onTryDemo: () -> Unit = {},
    onSetup: () -> Unit = {},
    languageViewModel: LanguageViewModel = hiltViewModel(),
    setupViewModel: SetupViewModel = hiltViewModel()
) {
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val configuredState by setupViewModel.state.collectAsState()

    // Which modes this device may actually enter. Read from what was *saved*, never from what is
    // currently selected in Setup — an unsaved radio choice must not unlock anything.
    val cloudReady = setupViewModel.isModeReady(OperatingMode.CLOUD)
    val lanReady = setupViewModel.isModeReady(OperatingMode.LAN)
    val kioskReady = setupViewModel.isModeReady(OperatingMode.KIOSK)
    var qrExpanded by remember { mutableStateOf(false) }
    val title = configuredState.cafeName.ifBlank { stringResource(R.string.app_name) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Language + theme selectors — top-end corner, visible from first open
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanguageButton()
                ThemeButton()
            }

            // Main content column — fills the screen so weight(1f) can push Setup Wizard down
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Reserve space so content clears the language selector row
                Spacer(modifier = Modifier.height(64.dp))

                // App / café name title
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = strings.appSubtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(48.dp))

                // ── One button per operating mode ─────────────────────────────────────
                //
                // Exactly one is enabled: the mode whose Setup was completed and saved. The other
                // two are greyed with the reason rather than hidden, so a café owner can see the
                // modes exist and knows what to do about them — a hidden option reads as a missing
                // feature, and a mis-set device reads as a broken one.
                //
                // QR Ordering expands in place rather than navigating, because it is the only mode
                // with two device roles to choose between. The other two go straight to work.

                ModeButton(
                    label = strings.qrOrderingModeButton,
                    enabled = cloudReady,
                    disabledHint = strings.modeNotSetUpHint,
                    expanded = qrExpanded,
                    onClick = { qrExpanded = !qrExpanded },
                )

                // Nested roles, revealed only once the café can actually run in Cloud mode.
                AnimatedVisibility(visible = qrExpanded && cloudReady) {
                    Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp)) {
                        Button(
                            onClick = onOrderingConnect,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(strings.joinAsOrderingMode, style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onAdminConnect,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(strings.reloginAsCafeAdmin, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                ModeButton(
                    label = strings.wirelessApModeButton,
                    enabled = lanReady,
                    disabledHint = strings.modeNotSetUpHint,
                    onClick = onWirelessAp,
                )

                Spacer(modifier = Modifier.height(12.dp))

                ModeButton(
                    label = strings.kioskModeButton,
                    enabled = kioskReady,
                    disabledHint = strings.modeNotSetUpHint,
                    onClick = onKiosk,
                )

                // ── Real layout gap (Requirement 2.5) ─────────────────────────────────
                // weight(1f) distributes the remaining screen height here, pushing the
                // Setup Wizard button to the bottom. This is a layout element — not padding —
                // so the gap scales with screen size and survives rotation.
                Spacer(modifier = Modifier.weight(1f))

                // ── Bottom row: the two subordinate actions ───────────────────────────
                // Setup Wizard is the third action the layout calls for. Try Demo shares its tier
                // rather than being dropped: the three-action hierarchy is about which actions are
                // PROMINENT, and Demo Mode is a working feature (DemoSession/DemoBackend, reachable
                // only from here) that a silent removal would strand — including for the café owner
                // showing the app to someone. Both are TextButtons, so the two buttons above remain
                // the only visually primary choices.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = onSetup) {
                        Text(
                            text = strings.setupWizard,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onTryDemo) {
                        Text(
                            text = strings.tryDemo,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * One operating mode, enabled only when that mode has been set up and saved.
 *
 * Disabled modes stay visible with their reason underneath rather than being hidden. Hiding would
 * make a café owner think the feature does not exist; greying tells them it does and what to do.
 */
@Composable
private fun ModeButton(
    label: String,
    enabled: Boolean,
    disabledHint: String,
    onClick: () -> Unit,
    expanded: Boolean? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(
            text = if (expanded == true) "$label  ▲" else if (expanded == false) "$label  ▼" else label,
            style = MaterialTheme.typography.titleMedium,
        )
    }
    if (!enabled) {
        Text(
            text = disabledHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 8.dp, end = 8.dp),
        )
    }
}
