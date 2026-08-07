package com.razstudio.pos.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.razstudio.pos.ui.viewmodels.OwnerKeyLoginViewModel
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
     * The owner key was accepted: the café is configured and this device is its admin, so Setup is
     * over. The caller decides where that lands.
     */
    onOwnerKeyAccepted: () -> Unit = {},
    /** Invoked after a successful save, so the operator lands back on the home screen and
     *  sees the mode button they just unlocked. */
    onSaved: () -> Unit = onBack,
    /**
     * Open the affiliate catalog editor. Wired only in debug builds — the caller does not register
     * the route in release, so the button below stays hidden there.
     */
    onOpenPromoCatalog: (() -> Unit)? = null,
    /** Open the in-app provisioner to create a new café backend from this tablet. */
    onProvision: () -> Unit = {},
    viewModel: SetupViewModel = hiltViewModel(),
    // NOTE: the rest of this screen is still hardcoded English — a pre-existing gap, not one this
    // change introduces. The new controls below are translated; the older labels around them are
    // not, and that whole screen deserves a pass of its own.
    languageViewModel: com.razstudio.pos.ui.i18n.LanguageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = com.razstudio.pos.ui.i18n.uiStrings(language)

    // ── Owner key: two ways in, both handled here ────────────────────────────────────────────────
    //
    // This used to hand off to AdminConnectScreen, which is a *login* screen — manual entry,
    // secondary-admin invites, a debug path — and knows nothing about the topology being set up.
    // A wizard that jumps to a login screen and hopes control comes back is not a wizard.
    val ownerKeyViewModel: OwnerKeyLoginViewModel = hiltViewModel()
    val ownerKeyState by ownerKeyViewModel.state.collectAsState()
    var showOwnerQrChoice by remember { mutableStateOf(false) }
    var showOwnerQrScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // Decoding happens off the main thread: a full-resolution photo of a QR is a few megapixels,
    // and ZXing on the UI thread would drop frames on the café's oldest phone.
    val scope = rememberCoroutineScope()
    val savedImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val decoded = withContext(Dispatchers.IO) { decodeQrFromImage(context, uri) }
                if (decoded.isNullOrBlank()) ownerKeyViewModel.imageHeldNoQr()
                else ownerKeyViewModel.load(decoded)
            }
        }
    }

    // A successful key is the end of Setup: the café is configured and this device is its admin.
    LaunchedEffect(ownerKeyState) {
        if (ownerKeyState is OwnerKeyLoginViewModel.State.Done) onOwnerKeyAccepted()
    }

    // The mode this device was ALREADY on when Setup opened. Captured once, so that after a
    // successful switch the button stops warning about a change that has already happened.
    var savedMode by rememberSaveable { mutableStateOf(state.operatingMode) }
    var showModeChangeConfirm by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }

    // Return to the home screen the moment a save lands, so the operator sees the mode button
    // they just unlocked rather than having to find their own way back and wonder if it took.
    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.setupTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.setupBack)
                    }
                },
                actions = {
                    IconButton(onClick = { showGuide = true }) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = strings.setupGuideTitle)
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
            SectionHeader(strings.setupHowCafeRuns)
            HelpText(strings.setupModeDecidesHint)
            ModeChoice(
                selected = state.operatingMode,
                onSelect = { viewModel.selectMode(it) },
                strings = strings,
            )

            SectionHeader(strings.setupConnection)
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
                // Three tabs, so ScrollableTabRow: the labels are translated and "Sediakan kafe
                // baharu" alone is wide, which a fixed TabRow would have to squeeze into a third of
                // the screen. Scrollable keeps every label whole in every language.
                ScrollableTabRow(
                    selectedTabIndex = when (state.connectionTab) {
                        ConnectionTab.OWNER_QR -> 0
                        ConnectionTab.EXISTING_CAFE -> 1
                        ConnectionTab.PROVISION_NEW_CAFE -> 2
                    },
                    edgePadding = 0.dp,
                ) {
                    Tab(
                        selected = state.connectionTab == ConnectionTab.OWNER_QR,
                        onClick = { viewModel.selectConnectionTab(ConnectionTab.OWNER_QR) },
                        text = { Text(strings.setupTabOwnerQr) },
                    )
                    // Middle, matching how often each is used: almost every device joins by QR, a few
                    // join a running café by hand, and provisioning a new one happens once per café.
                    Tab(
                        selected = state.connectionTab == ConnectionTab.EXISTING_CAFE,
                        onClick = { viewModel.selectConnectionTab(ConnectionTab.EXISTING_CAFE) },
                        text = { Text(strings.setupTabExistingCafe) },
                    )
                    Tab(
                        selected = state.connectionTab == ConnectionTab.PROVISION_NEW_CAFE,
                        onClick = { viewModel.selectConnectionTab(ConnectionTab.PROVISION_NEW_CAFE) },
                        text = { Text(strings.setupTabProvision) },
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (state.operatingMode == OperatingMode.CLOUD &&
                state.connectionTab == ConnectionTab.OWNER_QR
            ) {
                HelpText(strings.setupOwnerQrHelp)
                Button(
                    onClick = { showOwnerQrChoice = true },
                    enabled = ownerKeyState !is OwnerKeyLoginViewModel.State.Working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings.setupLoadOwnerQrButton) }

                when (val ok = ownerKeyState) {
                    is OwnerKeyLoginViewModel.State.Working -> HelpText(strings.ownerQrWorking)
                    is OwnerKeyLoginViewModel.State.Failed -> Text(
                        text = when (ok.reason) {
                            OwnerKeyLoginViewModel.Reason.NOT_AN_OWNER_KEY -> strings.ownerQrNotAnOwnerKey
                            OwnerKeyLoginViewModel.Reason.NO_QR_IN_IMAGE -> strings.ownerQrNoQrInImage
                            OwnerKeyLoginViewModel.Reason.REJECTED -> strings.ownerQrRejected
                            OwnerKeyLoginViewModel.Reason.UNREACHABLE -> strings.ownerQrUnreachable
                            OwnerKeyLoginViewModel.Reason.NO_BACKEND_IN_QR -> strings.ownerQrNoBackend
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Existing, already-running café ───────────────────────────────────────────────────
            //
            // The values asked for here are the same four a café's own Cloudflare Pages project holds
            // as VITE_SUPABASE_URL / VITE_SUPABASE_PUBLISHABLE_KEY plus its site URL, so an owner can
            // copy them straight out of the dashboard they already have open. The website field is
            // offered first because /app-config.json serves all of them: one field beats three, and
            // typing a publishable key by hand on a tablet is where mistakes happen.
            //
            // The manual fields stay reachable underneath, because the case this tab exists for
            // includes a café whose site is up but whose app-config is not being served correctly —
            // if the fetch is the only way in, that café is stuck.
            if (state.operatingMode == OperatingMode.CLOUD &&
                state.connectionTab == ConnectionTab.EXISTING_CAFE
            ) {
                HelpText(strings.setupExistingCafeHelp)

                Field(
                    strings.setupCafeWebsiteUrl,
                    strings.setupCafeWebsitePlaceholder,
                    state.websiteUrl,
                    KeyboardType.Uri,
                ) { v -> viewModel.update { it.copy(websiteUrl = v) } }
                HelpText(strings.setupCafeWebsiteUrlHint)

                Button(
                    onClick = { viewModel.fetchFromWebsite() },
                    enabled = !state.isFetching && state.websiteUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isFetching) strings.setupConnecting else strings.setupConnect)
                }
                state.fetchError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // No "✓ Connected" line here, deliberately. The obvious version of it — show it when
                // both Supabase fields are non-blank — is a lie on this tab: it fires the moment the
                // values are TYPED, claiming a connection nobody made, and it also says "tap Save to
                // apply" while Save is still disabled pending the check below. Populated fields are
                // their own evidence that the fetch worked; the only success worth announcing is the
                // one the Check button earns.

                TextButton(onClick = { viewModel.toggleManualFields() }) {
                    Text(
                        if (state.showManualFields) strings.setupHideManualFields
                        else strings.setupEnterManually
                    )
                }

                if (state.showManualFields) {
                    HelpText(strings.setupManualHint)
                    Field(
                        strings.setupSupabaseUrl,
                        strings.setupSupabaseUrlPlaceholder,
                        state.supabaseUrl,
                        KeyboardType.Uri,
                    ) { v -> viewModel.update { it.copy(supabaseUrl = v) } }
                    Field(
                        strings.setupSupabaseAnonKey,
                        strings.setupSupabaseAnonKeyPlaceholder,
                        state.supabaseAnonKey,
                        KeyboardType.Text,
                    ) { v -> viewModel.update { it.copy(supabaseAnonKey = v) } }
                }

                // Optional, and placed ABOVE the check deliberately. `update()` clears `verified` on
                // every keystroke — correct, since editing a field invalidates a check of the old
                // value — but that makes any field below the check button a trap: check, get a green
                // result, type one more thing, and Save silently disables again while the message
                // still says to check the connection. Nothing on this tab needs the Wizard URL, so it
                // goes before the gate rather than earning an exemption from it.
                Field(
                    strings.setupExistingWizardUrl,
                    "https://…/api/provision/run",
                    state.provisionerWorkerUrl,
                    KeyboardType.Uri,
                ) { v -> viewModel.update { it.copy(provisionerWorkerUrl = v) } }
                HelpText(strings.setupExistingWizardHint)

                // Rendered here rather than by the shared block below, for the same reason as the
                // Wizard URL: everything editable has to sit above the check, or verifying and then
                // naming the café silently undoes the verification.
                Field(strings.setupCafeName, strings.setupCafeNamePlaceholder, state.cafeName,
                    KeyboardType.Text) { v -> viewModel.update { it.copy(cafeName = v) } }

                // ── Check the connection ─────────────────────────────────────────────────────────
                //
                // Not optional polish: `blockingReason()` refuses to save an unverified Cloud pair, so
                // without this control the tab is a dead end — every field filled, Save permanently
                // disabled, and the message underneath asking for a check the screen never offered.
                // (That is exactly what this tab did on its first run on a device.)
                //
                // It is also the only thing that separates a URL and key that are well-formed from
                // ones that are correct. Saving unverified values lights up the café's mode button on
                // the home screen, and the failure then surfaces somewhere far less explicable.
                Button(
                    onClick = { viewModel.verifyConnection() },
                    enabled = !state.isVerifying &&
                        state.supabaseUrl.isNotBlank() && state.supabaseAnonKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isVerifying) strings.setupChecking else strings.setupCheckConnection)
                }
                state.verifyError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.verified) HelpText(strings.setupConnectionOk)

                // ── Whole-café preflight ─────────────────────────────────────────────────────────
                //
                // Check connection above answers "can this device reach this backend" and gates Save.
                // This answers the broader question the operator cannot see from here: is the café
                // fully stood up? The parts it covers fail silently — a Wizard that serves its UI and
                // no API, a website deployed without its Supabase variables — and both look fine in a
                // browser, which is exactly why they get missed.
                //
                // Read-only, so it sits outside the Save gate and can be run at any point.
                OutlinedButton(
                    onClick = { viewModel.runPreflight() },
                    enabled = !state.isPreflighting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isPreflighting) "Checking…" else "Check café setup")
                }
                state.preflight.forEach { item ->
                    Text(
                        text = (if (item.ok) "✓ " else "✗ ") + item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    if (item.detail.isNotBlank()) HelpText(item.detail)
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            if (state.operatingMode == OperatingMode.CLOUD &&
                state.connectionTab == ConnectionTab.PROVISION_NEW_CAFE
            ) {
                HelpText(strings.provisionTabHelp)
                Button(
                    onClick = onProvision,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings.provisionStartButton) }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Explicitly !CLOUD, not "else". Once the Cloud branch above gained a tab condition, an
            // `else` also caught Cloud-on-the-owner-QR-tab and showed a Full QR café the off-cloud
            // copy: "No internet backend. This device holds the café's data." Wrong, and alarming.
            if (state.operatingMode != OperatingMode.CLOUD) {
                HelpText(strings.setupOffCloudHint)
            }
            // Hidden on the owner-QR and provision-new-café tabs. The name arrives from `branding`
            // the moment the key is accepted, so a field here would be overwritten seconds after it
            // was filled in — and a field whose value silently changes teaches an owner not to trust
            // the screen.
            // Also excluded for EXISTING_CAFE, which draws its own copy above its Check button — see
            // there for why every editable field on that tab has to sit above the check.
            if (!(state.operatingMode == OperatingMode.CLOUD &&
                    (state.connectionTab == ConnectionTab.OWNER_QR ||
                        state.connectionTab == ConnectionTab.PROVISION_NEW_CAFE ||
                        state.connectionTab == ConnectionTab.EXISTING_CAFE))
            ) {
                Field(strings.setupCafeName, strings.setupCafeNamePlaceholder, state.cafeName,
                    KeyboardType.Text) { v -> viewModel.update { it.copy(cafeName = v) } }
            }

            if (state.operatingMode == OperatingMode.LAN) {
                // ── Task 21.1: the network the café will actually run on ─────────────────────────
                SectionHeader(strings.setupWifiForStaff)
                HotspotGuidance(strings)
            }

            if (state.operatingMode != OperatingMode.CLOUD) {
                HelpText(
                    strings.setupNextPrinterHint +
                        if (state.operatingMode == OperatingMode.LAN) {
                            strings.setupNextPrinterDevicesHint
                        } else {
                            ""
                        }
                )
            }

            // The provision-new-café tab launches a separate wizard that saves and signs in on its
            // own, so the wizard's own Save button is not shown there.
            if (!(state.operatingMode == OperatingMode.CLOUD &&
                    state.connectionTab == ConnectionTab.PROVISION_NEW_CAFE)
            ) {
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
                    Text(if (state.saved) strings.setupSaved else strings.setupSave)
                }
            }

            // ── Affiliate catalog (debug builds only) ────────────────────────────────────
            // Last in the wizard on purpose: it configures what CUSTOMERS see on the ordering
            // website, not how this device runs, so it must not sit between the operator and Save.
            if (com.razstudio.pos.BuildConfig.DEBUG && onOpenPromoCatalog != null) {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Affiliate catalog (debug)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Edit the Shopee placements every café's ordering page shows. Publishes " +
                        "to promos/partners.json on main; live everywhere in about five minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenPromoCatalog,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                ) { Text("Open catalog editor") }
            }
        }
    }

    if (showGuide) {
        SetupGuideDialog(
            strings = strings,
            onDismiss = { showGuide = false },
        )
    }

    if (showModeChangeConfirm) {
        ModeChangeConfirmDialog(
            from = savedMode,
            to = state.operatingMode,
            strings = strings,
            onConfirm = {
                showModeChangeConfirm = false
                viewModel.save()
                savedMode = state.operatingMode
            },
            onDismiss = { showModeChangeConfirm = false },
        )
    }

    // ── Owner-key dialogs ────────────────────────────────────────────────────────────────────────

    // ── The choice, as a modal ───────────────────────────────────────────────────────────────────
    // Two buttons rather than a screen, because this is one decision — camera or file — and the
    // owner already knows which they have.
    if (showOwnerQrChoice) {
        AlertDialog(
            onDismissRequest = { showOwnerQrChoice = false },
            title = { Text(strings.setupLoadOwnerQrButton) },
            // The two choices live in the TEXT slot, not in confirmButton.
            //
            // Material3 lays confirmButton and dismissButton out together in one horizontal row, so
            // a full-width Column in confirmButton takes the whole row and Cancel gets drawn on top
            // of it. The body slot is a plain vertical column, which is what two stacked full-width
            // choices actually want. Cancel stays the single real button, where a dialog's cancel
            // belongs.
            text = {
                Column {
                    Text(strings.ownerQrChoiceBody)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            showOwnerQrChoice = false
                            ownerKeyViewModel.reset()
                            if (!hasCameraPermission) {
                                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            }
                            showOwnerQrScanner = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(strings.ownerQrScanWithCamera) }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showOwnerQrChoice = false
                            ownerKeyViewModel.reset()
                            savedImageLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(strings.ownerQrChooseSavedImage) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOwnerQrChoice = false }) { Text(strings.commonCancel) }
            },
        )
    }

    if (showOwnerQrScanner) {
        QrScannerScreen(
            hasCameraPermission = hasCameraPermission,
            onRequestPermission = {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            },
            onQrDecoded = { text ->
                showOwnerQrScanner = false
                ownerKeyViewModel.load(text)
            },
            onCancel = { showOwnerQrScanner = false },
            promptText = strings.ownerQrScanPrompt,
            cancelText = strings.commonCancel,
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
    strings: com.razstudio.pos.ui.i18n.UiStrings,
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
        title = { Text(strings.setupSwitchTitleFormat.format(from.name, to.name)) },
        text = {
            Column {
                Text(
                    strings.setupSwitchIntro,
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
                Text(strings.setupSwitchToFormat.format(to.name), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.setupCancel) } },
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
private fun HotspotGuidance(strings: com.razstudio.pos.ui.i18n.UiStrings) {
    val context = LocalContext.current

    HelpText(strings.setupHotspotHint)

    OutlinedButton(
        onClick = { context.openHotspotSettings() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(strings.setupOpenHotspotSettings)
    }

    HelpText(strings.setupWifiRouterHint)
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
    strings: com.razstudio.pos.ui.i18n.UiStrings,
) {
    val options = listOf(
        Triple(
            OperatingMode.CLOUD,
            strings.setupModeCloudTitle,
            strings.setupModeCloudBlurb,
        ),
        Triple(
            OperatingMode.LAN,
            strings.setupModeLanTitle,
            strings.setupModeLanBlurb,
        ),
        Triple(
            OperatingMode.KIOSK,
            strings.setupModeKioskTitle,
            strings.setupModeKioskBlurb,
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
