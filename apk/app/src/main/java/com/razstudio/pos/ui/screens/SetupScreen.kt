package com.razstudio.pos.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.toCapabilities
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.ui.viewmodels.ConnectionTab
import com.razstudio.pos.ui.viewmodels.SetupViewModel

/**
 * In-app deployment setup, reached from the three-dots menu on the login screen.
 *
 * **Connection section** (Cloud mode):
 * The primary input is one "Café website URL" field.  Tapping "Connect" fetches
 * `/app-config.json` from that URL and populates the three connection values atomically.
 * The three manual fields are retained behind an "Enter manually" toggle for cafés whose
 * site is not yet deployed (Requirement 3.2).
 *
 * On fetch failure the error is shown and the manual fields are revealed automatically
 * (Requirement 3.4 / Design Property 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    /**
     * Open the owner-key screen. Not embedded: `AdminConnectScreen` is 747 lines of camera,
     * saved-image picker, manual entry and secondary-admin handling, and a second copy of that
     * would be a second place for the owner-key flow to drift.
     */
    onLoadOwnerQr: () -> Unit = {},
    /** Invoked after a successful save, so the operator lands back on the home screen and
     *  sees the mode button they just unlocked. */
    onSaved: () -> Unit = onBack,
    viewModel: SetupViewModel = hiltViewModel(),
    // NOTE: the rest of this screen is still hardcoded English — a pre-existing gap, not one this
    // change introduces. The new controls below are translated; the older labels around them are
    // not, and that whole screen deserves a pass of its own.
    languageViewModel: com.razstudio.pos.ui.i18n.LanguageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = com.razstudio.pos.ui.i18n.uiStrings(language)

    // The mode this device was ALREADY on when Setup opened. Captured once, so that after a
    // successful switch the button stops warning about a change that has already happened.
    var savedMode by rememberSaveable { mutableStateOf(state.operatingMode) }
    var showModeChangeConfirm by rememberSaveable { mutableStateOf(false) }

    // Return to the home screen the moment a save lands, so the operator sees the mode button
    // they just unlocked rather than having to find their own way back and wonder if it took.
    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Task 9.2: the topology comes first, because it decides what else is worth asking ──
            SectionHeader("How this café runs")
            HelpText("Pick this first — it decides what the rest of this screen asks for.")
            ModeChoice(
                selected = state.operatingMode,
                onSelect = { viewModel.selectMode(it) },
            )

            SectionHeader("Connection")
            if (state.operatingMode == OperatingMode.CLOUD) {
                // ── Two ways in, and the fast one goes first ─────────────────────────────────────
                //
                // The owner key already carries the Supabase URL, the publishable key and the
                // café's website origin, and signing in with it fetches the café name from
                // branding. So it supplies every single thing the manual tab asks for. Before this,
                // an owner holding that QR still could not save — the form demanded a café name it
                // was about to overwrite — which is the whole reason these tabs exist.
                //
                // Manual stays, because a café whose site is not deployed yet has no QR to scan.
                TabRow(
                    selectedTabIndex = if (state.connectionTab == ConnectionTab.OWNER_QR) 0 else 1,
                ) {
                    Tab(
                        selected = state.connectionTab == ConnectionTab.OWNER_QR,
                        onClick = { viewModel.selectConnectionTab(ConnectionTab.OWNER_QR) },
                        text = { Text(strings.setupTabOwnerQr) },
                    )
                    Tab(
                        selected = state.connectionTab == ConnectionTab.MANUAL,
                        onClick = { viewModel.selectConnectionTab(ConnectionTab.MANUAL) },
                        text = { Text(strings.setupTabManual) },
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (state.operatingMode == OperatingMode.CLOUD &&
                state.connectionTab == ConnectionTab.OWNER_QR
            ) {
                HelpText(strings.setupOwnerQrHelp)
                Button(
                    onClick = onLoadOwnerQr,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings.setupLoadOwnerQrButton) }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (state.operatingMode == OperatingMode.CLOUD &&
                state.connectionTab == ConnectionTab.MANUAL
            ) {
                // ── Task 4.2: single website URL field as the primary input ──────────────────────
                HelpText("Enter the café's website address and tap Connect to fill in the backend details automatically.")

                Field(
                    label = "Café website URL",
                    placeholder = "https://your-cafe.pages.dev",
                    value = state.websiteUrl,
                    keyboardType = KeyboardType.Uri,
                    onChange = { v -> viewModel.update { it.copy(websiteUrl = v) } },
                )

                // Fetch button and in-progress indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { viewModel.fetchFromWebsite() },
                        enabled = !state.isFetching,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.isFetching) "Connecting…" else "Connect")
                    }
                    if (state.isFetching) {
                        CircularProgressIndicator(modifier = Modifier.width(24.dp))
                    }
                }

                // Fetch error message (non-null after a failed attempt)
                state.fetchError?.let { errorMsg ->
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // Success banner — shown only when fetch succeeded and manual fields are hidden
                if (state.fetchError == null && !state.isFetching && !state.showManualFields
                    && state.supabaseUrl.isNotBlank()
                ) {
                    Text(
                        text = "✓ Connected — tap Save to apply.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // ── Task 4.2: "Enter manually" toggle ─────────────────────────────────────────
                TextButton(
                    onClick = { viewModel.toggleManualFields() },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        if (state.showManualFields) "▲ Hide manual fields"
                        else "▼ Enter manually"
                    )
                }

                // Three manual fields, hidden by default, animated into view
                AnimatedVisibility(
                    visible = state.showManualFields,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HelpText(
                            "Enter the details from your Supabase project dashboard. " +
                                "Use this if your website isn't deployed yet."
                        )
                        Field(
                            label = "Supabase URL",
                            placeholder = "https://your-project.supabase.co",
                            value = state.supabaseUrl,
                            keyboardType = KeyboardType.Uri,
                            onChange = { v -> viewModel.update { it.copy(supabaseUrl = v) } },
                        )
                        Field(
                            label = "Supabase anon key",
                            placeholder = "eyJhbGci…",
                            value = state.supabaseAnonKey,
                            keyboardType = KeyboardType.Text,
                            secret = true,
                            onChange = { v -> viewModel.update { it.copy(supabaseAnonKey = v) } },
                        )
                    }
                }
            }

            // Explicitly !CLOUD, not "else". Once the Cloud branch above gained a tab condition, an
            // `else` also caught Cloud-on-the-owner-QR-tab and showed a Full QR café the off-cloud
            // copy: "No internet backend. This device holds the café's data." Wrong, and alarming.
            if (state.operatingMode != OperatingMode.CLOUD) {
                // Requirement 2.4: an off-cloud café is never asked for a Supabase URL. Not merely
                // optional — absent. A field that can be filled in and then ignored is how an owner
                // ends up believing their LAN café is syncing somewhere.
                HelpText(
                    "No internet backend. This device holds the café's data, and prints " +
                        "directly to its own printer. Saving will clear any Supabase details " +
                        "previously stored on this device."
                )
            }
            Field("Café name", "Your Café", state.cafeName,
                KeyboardType.Text) { v -> viewModel.update { it.copy(cafeName = v) } }

            if (state.operatingMode == OperatingMode.LAN) {
                // ── Task 21.1: the network the café will actually run on ─────────────────────────
                SectionHeader("Wi-Fi for staff devices")
                HotspotGuidance()
            }

            if (state.operatingMode != OperatingMode.CLOUD) {
                HelpText(
                    "Next: pair the printer from Café Management → Printers. " +
                        if (state.operatingMode == OperatingMode.LAN) {
                            "Then add staff devices from the Devices screen."
                        } else {
                            "Kiosk Mode runs on this device alone — no tables, no staff devices, " +
                                "and orders are identified by a running number instead of a table."
                        }
                )
            }

            // ── Step 2: prove it works, then save (tasks 6.3, 6.6) ───────────────────────────
            //
            // Save alone used to be the whole flow, and it proved only that text reached disk. An
            // operator who mistyped a key learned nothing until a later screen failed for a reason
            // that never mentioned the key.
            if (state.operatingMode == OperatingMode.CLOUD &&
                state.connectionTab == ConnectionTab.MANUAL
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.verifyConnection() },
                    enabled = !state.isVerifying &&
                        state.supabaseUrl.isNotBlank() && state.supabaseAnonKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            state.isVerifying -> "Checking…"
                            state.verified -> "Connection OK ✓"
                            else -> "Check connection"
                        }
                    )
                }
                state.verifyError?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Why Save is refused, named rather than left to guesswork. The home screen enables
            // exactly one mode button — the one whose setup was completed — so a blocked Save is
            // the difference between a café that can start and one that cannot.
            viewModel.blockingReason()?.let { reason ->
                Text(
                    text = reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Button(
                onClick = {
                    // Task 10.1: a mode switch is destructive and irreversible in the ways that
                    // matter, so it is confirmed. A save that changes nothing about the topology is
                    // not — making an operator confirm a café-name edit would train them to dismiss
                    // the dialog that actually matters.
                    if (state.operatingMode != savedMode) showModeChangeConfirm = true else viewModel.save()
                },
                // Every field this mode shows must be filled, and Cloud must have answered.
                enabled = viewModel.canSave(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
            ) {
                Text(if (state.saved) "Saved ✓" else "Save")
            }
        }
    }

    if (showModeChangeConfirm) {
        ModeChangeConfirmDialog(
            from = savedMode,
            to = state.operatingMode,
            onConfirm = {
                showModeChangeConfirm = false
                viewModel.save()
                savedMode = state.operatingMode
            },
            onDismiss = { showModeChangeConfirm = false },
        )
    }
}

/**
 * Confirm a topology change before anything is written (task 10.1, Requirements 10.1, 10.2).
 *
 * The requirement is that the operator is told *exactly* what will be lost and what will stop
 * working, before the write — not a generic "are you sure". So the copy is generated from the actual
 * transition rather than being one fixed paragraph: leaving Cloud loses different things from
 * entering it, and Kiosk removes tables while LAN keeps them.
 *
 * Orders are called out first because they are the part an owner cannot reconstruct. Nothing here
 * deletes the local database — but the café's data lives in whichever backend the mode selects, so
 * after the switch the orders on the other side are no longer the ones the app reads or reports on,
 * which for the person running the till is indistinguishable from losing them.
 */
@Composable
private fun ModeChangeConfirmDialog(
    from: OperatingMode,
    to: OperatingMode,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Task 10.2 / Requirement 10.3 — derived from ModeCapabilities, not hardcoded per mode.
    //
    // The point is that a fourth mode gets correct messaging by adding one row to toCapabilities()
    // and nothing else. Hardcoding a `when (to)` block, as the first version did, means the day
    // someone adds a mode the dialog confidently tells the operator the wrong things — and this is
    // the one dialog whose whole job is to be accurate about what is about to be lost.
    val fromCaps = from.toCapabilities()
    val toCaps = to.toCapabilities()

    // Only capabilities being LOST are listed. Gaining one needs no warning.
    val losing = buildList {
        fun lost(before: Boolean, after: Boolean, text: String) {
            if (before && !after) add(text)
        }
        lost(fromCaps.customerQrOrdering, toCaps.customerQrOrdering,
            "Customer QR ordering stops working — customers can no longer order from their own phones.")
        lost(fromCaps.printableQrSheets, toCaps.printableQrSheets,
            "Printable table QR sheets are no longer available.")
        lost(fromCaps.tables, toCaps.tables,
            "Tables are removed entirely — orders will be identified by a running number instead.")
        lost(fromCaps.staffDevices, toCaps.staffDevices,
            "Staff devices are no longer supported; this becomes a single-device café.")
        lost(fromCaps.secondaryAdmin, toCaps.secondaryAdmin,
            "Secondary admin devices are no longer supported.")
        lost(fromCaps.websiteInvites, toCaps.websiteInvites,
            "Invite links stop working — staff devices pair by scanning a code instead.")
        lost(fromCaps.cloudImageHosting, toCaps.cloudImageHosting,
            "Menu photos move to this device's own storage rather than the cloud.")
    }

    // Data consequences are not capability-shaped, so they stay explicit — but they are keyed on the
    // direction of travel rather than on the destination mode, which keeps them correct for any
    // future mode too.
    val dataWarnings = buildList {
        if (from == OperatingMode.CLOUD) {
            add("This device will stop reading the café's online orders and reports. Anything recorded online stays there, but this app will no longer show it.")
            add("The saved Supabase details and this device's sign-in are cleared. You will need them again to switch back.")
        } else {
            add("Orders taken on this device while offline are not uploaded. Switching now leaves them here only.")
        }
        if (to == OperatingMode.CLOUD) {
            add("You will need a Supabase project and a website before this café can take orders again.")
        }
        if (toCaps.staffDevices && !fromCaps.staffDevices) {
            add("Staff devices will need to pair with this device before they can take orders.")
        }
    }

    val losses = dataWarnings + losing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch from ${from.name} to ${to.name}?") },
        text = {
            Column {
                Text(
                    "This changes how the whole café runs. Before you continue:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.padding(top = 8.dp))
                losses.forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Switch to ${to.name}", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Task 21.1 — get the café's Wi-Fi up, by telling the operator how (Requirements 4.3, 4.3.1).
 *
 * ### Why this is instructions and a shortcut rather than a switch
 *
 * There is deliberately **no** "turn on hotspot" button, because Android offers no way to build one
 * that works here:
 *
 *  - Programmatic tethering has no public API. The methods that exist
 *    (`ConnectivityManager.startTethering`, the old `WifiManager.setWifiApEnabled`) are hidden or
 *    system-signature only, so calling them means reflection that breaks between OEM builds — on the
 *    device a café is relying on to take orders.
 *  - `WifiManager.startLocalOnlyHotspot` is public, and is the trap. It brings up a **system-named**
 *    AP with a generated SSID and password the operator cannot choose or write down, carries no
 *    internet path, and is tied to the lifetime of the requesting app — it dies when the app is
 *    backgrounded or the process is killed. A café would find its staff devices dropped off the
 *    network every time the tablet slept.
 *
 * So the honest implementation is the one that keeps working: send the operator to the real system
 * screen, where the AP they create is theirs, named, persistent, and survives the app.
 *
 * The action falls back through three intents because `TETHER_SETTINGS` is not present on every
 * build, and a dead button is worse than a plain instruction.
 */
@Composable
private fun HotspotGuidance() {
    val context = LocalContext.current

    HelpText(
        "Staff devices need to be on the same network as this one. Turn on this device's " +
            "hotspot in Android settings, then connect each staff phone to it. Give it a name " +
            "and password you can share — the café will use this network every day."
    )

    OutlinedButton(
        onClick = { context.openHotspotSettings() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Open hotspot settings")
    }

    HelpText(
        "An existing Wi-Fi router works just as well — if the café already has one, connect this " +
            "device and the staff phones to it and skip the hotspot."
    )
}

/**
 * Open the OEM's hotspot/tethering screen, degrading to progressively broader targets.
 *
 * `TETHER_SETTINGS` is undocumented-but-widespread rather than guaranteed, and several OEMs move or
 * remove it, so an unguarded start would throw [ActivityNotFoundException] and crash the wizard.
 * Wireless settings, then the top-level settings app, are always present.
 */
private fun Context.openHotspotSettings() {
    val candidates = listOf(
        Intent("com.android.settings.TETHER_SETTINGS"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next, broader target.
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/**
 * The three topologies, as radio options (task 9.2, Requirements 2.2, 2.3).
 *
 * Radio buttons rather than a dropdown on purpose: this is a decision an owner makes once, it changes
 * what the rest of the app can do, and all three options plus their consequences should be readable
 * without a tap. Each carries a one-line consequence, because "LAN" and "Kiosk" mean nothing to
 * someone who runs a café.
 */
@Composable
private fun ModeChoice(
    selected: OperatingMode,
    onSelect: (OperatingMode) -> Unit,
) {
    val options = listOf(
        Triple(
            OperatingMode.CLOUD,
            "Full Online with QR ordering",
            "Customers scan a table QR and order from their own phone. Needs internet and a Supabase project.",
        ),
        Triple(
            OperatingMode.LAN,
            "(W)LAN AP without QR ordering",
            "Staff phones order over your own Wi-Fi. No internet needed. This device holds the data and the printer.",
        ),
        Triple(
            OperatingMode.KIOSK,
            "Kiosk Mode",
            "This one device only. No tables, no staff phones, no internet — orders get a running number.",
        ),
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        options.forEach { (mode, title, blurb) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == mode,
                        role = Role.RadioButton,
                        onClick = { onSelect(mode) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = selected == mode,
                    // null: the whole row is the target via selectable() above, so the button must
                    // not also be one or TalkBack announces two controls for one choice.
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Field(
    label: String,
    placeholder: String,
    value: String,
    keyboardType: KeyboardType,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
