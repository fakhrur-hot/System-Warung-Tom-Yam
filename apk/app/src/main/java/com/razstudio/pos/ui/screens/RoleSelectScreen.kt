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
    /** Wireless AP host: open this café's till. See the Host button for why it is not the QR. */
    onHostCafe: () -> Unit = {},
    onKiosk: () -> Unit = {},
    onTryDemo: () -> Unit = {},
    onSetup: () -> Unit = {},
    /** Avatar → re-authenticate and re-list the account's cafés. */
    onReloadDrive: () -> Unit = {},
    /** Avatar → either logout finished; the caller resets the stack. */
    onAccountSignedOut: () -> Unit = {},
    languageViewModel: LanguageViewModel = hiltViewModel(),
    setupViewModel: SetupViewModel = hiltViewModel()
) {
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val configuredState by setupViewModel.state.collectAsState()

    // Which modes this device may *host*. Read from what was saved, never from what is currently
    // selected in Setup — an unsaved radio choice must not unlock anything.
    //
    // Cloud has no equivalent flag because neither of its actions needs one: the owner QR and the
    // invite QR both carry the café's backend, so they configure the device as a side effect of
    // being used. Kiosk is a single device with nothing to join, so it is gated outright.
    val lanReady = setupViewModel.isModeReady(OperatingMode.LAN)
    val kioskReady = setupViewModel.isModeReady(OperatingMode.KIOSK)
    val cloudReady = setupViewModel.isModeReady(OperatingMode.CLOUD)

    // A blank device offers every joinable mode, because the QR flows are how it gets configured.
    // Once the owner has SAVED a mode, that answer is respected: the modes they did not pick grey
    // out. Leaving them live was a real defect — an owner who chose Wireless AP without QR ordering
    // still saw a fully enabled "QR Ordering Mode" button, which can only lead somewhere wrong.
    val blankDevice = setupViewModel.noModeConfiguredYet()
    var qrExpanded by remember { mutableStateOf(false) }
    var apExpanded by remember { mutableStateOf(false) }
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
                // Home screen: the full account menu, including Google sign-out. This is where an
                // owner is between things and can afford it.
                com.razstudio.pos.ui.components.AccountAvatar(
                    isHomeScreen = true,
                    onReloadDrive = onReloadDrive,
                    onSignedOut = onAccountSignedOut,
                )
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
                    // Open on a blank device, because the owner QR and the invite QR each carry the
                    // café's backend and are how an unconfigured device gets configured — gating
                    // this would lock out precisely the device that needs it.
                    //
                    // Closed once another mode has been saved. That is not the same situation: the
                    // owner answered the question, and a device set up for Wireless AP has no Cloud
                    // backend for either action inside here to reach.
                    enabled = cloudReady || blankDevice,
                    disabledHint = strings.modeConfiguredElsewhereHint,
                    expanded = qrExpanded,
                    onClick = { qrExpanded = !qrExpanded },
                )

                AnimatedVisibility(visible = qrExpanded) {
                    Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp)) {
                        Button(
                            onClick = onOrderingConnect,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(strings.joinAsOrderingMode, style = MaterialTheme.typography.titleSmall)
                        }
                        // The owner-key entry used to sit here as a second button. It moved into
                        // the Setup Wizard's "Owner QR" tab, where it belongs: scanning that key is
                        // how a Full QR café gets *configured*, not just how somebody signs in, and
                        // having it here meant Setup demanded a café name the QR was about to
                        // supply. Setup is one tap away at the bottom of this screen.
                        //
                        // Kept for a device that is ALREADY a configured Cloud café, where this is
                        // a genuine re-login and sending the owner through a setup wizard to do it
                        // would be absurd.
                        if (cloudReady) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onAdminConnect,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) {
                                Text(strings.reloginAsCafeAdmin, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Wireless AP has the same two roles as QR Ordering, so it gets the same shape and
                // the same gate: open while the device is blank (a staff phone joins by scanning the
                // host's pairing QR), closed once the owner has saved a different mode.
                ModeButton(
                    label = strings.wirelessApModeButton,
                    enabled = lanReady || blankDevice,
                    disabledHint = strings.modeConfiguredElsewhereHint,
                    expanded = apExpanded,
                    onClick = { apExpanded = !apExpanded },
                )

                AnimatedVisibility(visible = apExpanded) {
                    Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp)) {
                        Button(
                            // Hosting *is* running the café: this opens the till — an empty table
                            // view on a new café, or the restored one if a bundle came back from the
                            // owner's Google account.
                            //
                            // It used to open the pairing QR, which made it indistinguishable from
                            // "Join this café" right below it — two buttons, two QR screens, and no
                            // way into the café from either. Pairing now lives in Devices, where the
                            // rest of the staff-device management already is.
                            onClick = {
                                setupViewModel.beginHostingLocally()
                                onHostCafe()
                            },
                            // Still needs the café to exist — there is nothing to host otherwise.
                            enabled = lanReady,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(strings.hostThisCafe, style = MaterialTheme.typography.titleSmall)
                        }
                        if (!lanReady) {
                            Text(
                                text = strings.modeNotSetUpHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            // A staff phone scans the host's pairing QR, which carries the server's
                            // address — the same "the QR configures you" rule as the Cloud path.
                            onClick = onOrderingConnect,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(strings.joinThisCafe, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }

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
