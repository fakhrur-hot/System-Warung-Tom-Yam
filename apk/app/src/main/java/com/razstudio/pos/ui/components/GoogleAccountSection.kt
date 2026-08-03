package com.razstudio.pos.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.GoogleBackupStatusViewModel

/**
 * The café's Google account and its Drive bundle, on the Settings screen.
 *
 * ## Two states, and the first one is deliberately bare
 *
 * Not linked: a single button. No status rows for an account that does not exist, no folder name for
 * a folder nobody could have created — an owner who has not linked anything should be reading one
 * sentence and one button, not a panel of blanks.
 *
 * Linked: the account, whether Drive has been reached, and whether this café has a bundle in it. If
 * it has none, the offer to create one names the exact folder — `RAZS.POS-FullQR-Kedai Kopi` —
 * because the whole promise here is that a reinstalled phone finds that folder and comes back
 * whole, and the owner should be able to see what they are being promised.
 *
 * ## Why the folder is per-mode
 *
 * A café run as Full QR and the same café run as a Kiosk are different installations with different
 * backends, and restoring one onto the other would be wrong. The folder for the *current* mode is
 * the only one this device offers to create or refresh.
 */
@Composable
fun GoogleAccountSection(
    modifier: Modifier = Modifier,
    viewModel: GoogleBackupStatusViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val activity = LocalContext.current as? Activity

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = strings.googleAccountSection,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (state.account == null) {
            // One sentence, one button. See the class note.
            Text(
                text = strings.googleLinkExplain,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { activity?.let { viewModel.link(it) } },
                enabled = activity != null && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(strings.googleLinkButton) }
            state.message?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            return@Column
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusRow(strings.googleAccountLabel, state.account!!.email)
                StatusRow(
                    strings.googleDriveLabel,
                    when {
                        state.busy -> strings.googleDriveChecking
                        state.driveReachable -> strings.googleDriveConnected
                        else -> strings.googleDriveUnreachable
                    },
                )
                StatusRow(
                    strings.googleBundleLabel,
                    if (state.bundleExists) state.folderName else strings.googleBundleMissing,
                )

                if (state.busy) {
                    Spacer(modifier = Modifier.height(4.dp))
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                }

                Spacer(modifier = Modifier.height(4.dp))

                // "Create" and "Update" are the same write. They are labelled differently because
                // one of them is a promise being made for the first time and the other is a promise
                // being kept — and an owner who has never backed up needs to notice the difference.
                Button(
                    onClick = { activity?.let { viewModel.saveBundle(it) } },
                    enabled = activity != null && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.bundleExists) strings.googleBundleUpdate
                        else strings.googleBundleCreate.format(state.folderName)
                    )
                }

                OutlinedButton(
                    onClick = { activity?.let { viewModel.refresh(it) } },
                    enabled = activity != null && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings.googleBundleRecheck) }

                state.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
