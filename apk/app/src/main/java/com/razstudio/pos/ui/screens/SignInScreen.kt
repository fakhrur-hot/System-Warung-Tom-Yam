package com.razstudio.pos.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.R
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.SignInViewModel

/**
 * Task 23.1–23.3, 23.9 — the first screen (Requirement 15).
 *
 * ## The footer is the argument
 *
 * **Skip** and **Demo Mode** sit side by side, equal weight, neither subordinate (task 23.2). That
 * layout *is* the statement that sign-in is optional. A single greyed link under a large sign-in
 * button says the opposite — it reads as the escape hatch for people doing it wrong — and this app
 * is installed by owners who may have no Google account, no Play Services, and no signal on the day
 * they open. They are not doing it wrong.
 *
 * ## Three exits, not one
 *
 * [SignInViewModel.State.SignedInNoCafe] deliberately offers Setup and nothing else: an account with
 * no saved café cannot host, cannot join, and showing mode buttons would invite three taps that all
 * fail (Requirement 15.5).
 */
@Composable
fun SignInScreen(
    onContinueToEntry: () -> Unit,
    onSetup: () -> Unit,
    onTryDemo: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val consentRequest by viewModel.consentRequest.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    // Every exit records that the owner answered, so the next cold start opens on the entry screen.
    // Skip counts: a decision not to sign in is still a decision (Requirement 15.9).
    val leaveToEntry = { viewModel.settle(); onContinueToEntry() }
    val leaveToSetup = { viewModel.settle(); onSetup() }
    val leaveToDemo = { viewModel.settle(); onTryDemo() }

    // Drive consent is a separate activity result. The ViewModel cannot launch it — an intent
    // sender needs a result contract, which belongs here.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        activity?.let { viewModel.onDriveConsentResult(it, result.resultCode == Activity.RESULT_OK) }
    }

    LaunchedEffect(consentRequest) {
        consentRequest?.let {
            consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
            viewModel.consentRequestHandled()
        }
    }

    // A restore is finished the moment it succeeds; there is nothing further to confirm, and making
    // the owner tap "Continue" after their own café came back would be ceremony.
    LaunchedEffect(state) {
        if (state is SignInViewModel.State.Restored) leaveToEntry()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(96.dp))

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = strings.signInSubtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(48.dp))

                when (val s = state) {
                    is SignInViewModel.State.Working -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = when (s.step) {
                                SignInViewModel.Step.SIGNING_IN -> strings.signInStepSigningIn
                                SignInViewModel.Step.LOOKING_UP -> strings.signInStepLookingUp
                                SignInViewModel.Step.WAITING_FOR_DRIVE -> strings.signInStepWaitingDrive
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is SignInViewModel.State.SignedInNoCafe -> {
                        Text(
                            text = strings.signInNoCafeFound.format(s.email),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = leaveToSetup,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(strings.signInSetupThisCafe) }
                    }

                    else -> {
                        Button(
                            onClick = { activity?.let { viewModel.signIn(it) } },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = activity != null,
                        ) { Text(strings.signInWithGoogle) }
                    }
                }
            }

            // ── The footer (task 23.2) ────────────────────────────────────────────────────────
            // Equal width, equal weight, side by side. Not a link under the button.
            //
            // Demo is hidden in SignedInNoCafe for the same reason the mode buttons are: that state
            // has exactly one useful action, and offering a second dilutes it.
            if (state !is SignInViewModel.State.SignedInNoCafe) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = leaveToEntry,
                        modifier = Modifier.weight(1f),
                    ) { Text(strings.signInSkip) }

                    OutlinedButton(
                        onClick = leaveToDemo,
                        modifier = Modifier.weight(1f),
                    ) { Text(strings.signInDemoMode) }
                }
            }
        }
    }

    // ── Task 23.8: the device and the account disagree ───────────────────────────────────────
    (state as? SignInViewModel.State.Conflict)?.let { conflict ->
        AlertDialog(
            onDismissRequest = { viewModel.keepDeviceCafe() },
            title = { Text(strings.signInConflictTitle) },
            text = {
                Text(strings.signInConflictBody.format(conflict.onDevice, conflict.inAccount))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.keepAccountCafe() }) {
                    Text(strings.signInKeepAccountCafe.format(conflict.inAccount))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.keepDeviceCafe() }) {
                    Text(strings.signInKeepDeviceCafe.format(conflict.onDevice))
                }
            },
        )
    }

    // ── The account holds several cafés ──────────────────────────────────────────────────────
    // Listed, not defaulted. A WLAN till and a Kiosk in the same account are different businesses
    // as far as this device is concerned, and picking for the owner would silently load the wrong
    // one — which, because choosing replaces what is on the device, is not a mistake they can undo.
    (state as? SignInViewModel.State.ChooseCafe)?.let { choice ->
        AlertDialog(
            // Not dismissible: there is no sensible "neither". The owner signed in to load a café,
            // and closing this would leave them on a screen with nothing to act on.
            onDismissRequest = {},
            title = { Text(strings.chooseCafeTitle) },
            text = {
                Column {
                    Text(strings.chooseCafeBody, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    choice.bundles.forEach { bundle ->
                        OutlinedButton(
                            onClick = { viewModel.choose(bundle) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // Mode as well as name: an owner running the same café as a Kiosk and a
                            // WLAN till has two bundles whose names are identical, and the mode is
                            // the only thing that tells them apart.
                            Text("${bundle.cafeName}  ·  ${bundle.mode}")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = strings.chooseCafeCannotUndo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = leaveToEntry) { Text(strings.signInContinueWithout) }
            },
        )
    }

    (state as? SignInViewModel.State.Problem)?.let { problem ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissProblem() },
            title = { Text(strings.signInProblemTitle) },
            text = {
                Text(
                    when (problem.reason) {
                        SignInViewModel.Reason.SIGN_IN_UNAVAILABLE -> strings.signInUnavailable
                        SignInViewModel.Reason.BUNDLE_UNREADABLE -> strings.signInBundleUnreadable
                        SignInViewModel.Reason.DRIVE_UNREACHABLE -> strings.signInDriveUnreachable
                        SignInViewModel.Reason.RESTORE_INCOMPLETE -> strings.signInRestoreIncomplete
                    }
                )
            },
            // Both buttons lead somewhere. Nothing here is a dead end (Property 10).
            confirmButton = {
                TextButton(onClick = leaveToEntry) { Text(strings.signInContinueWithout) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissProblem() }) { Text(strings.signInTryAgain) }
            },
        )
    }
}
