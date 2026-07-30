package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.viewmodels.AdminSessionViewModel

/**
 * Dialog for "Sign Out with Closing" flow.
 * Provides a reason text field and shows progress as aggregate is computed and sent.
 */
@Composable
fun SignOutWithClosingDialog(
    closingState: AdminSessionViewModel.ClosingState,
    strings: UiStrings,
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit
) {
    var reason by remember { mutableStateOf("") }
    val isProcessing = closingState != AdminSessionViewModel.ClosingState.Idle

    AlertDialog(
        onDismissRequest = {
            if (!isProcessing) onDismiss()
        },
        title = {
            Text(strings.signOutClosingTitle)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isProcessing) {
                    Text(
                        text = strings.signOutClosingDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text(strings.noteOptionalLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when (closingState) {
                            AdminSessionViewModel.ClosingState.ComputingAggregate -> strings.computingAggregateLabel
                            AdminSessionViewModel.ClosingState.SendingAggregate -> strings.sendingAggregateLabel
                            AdminSessionViewModel.ClosingState.ClosingSession -> strings.closingSessionLabel
                            AdminSessionViewModel.ClosingState.Done -> strings.commonDone
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            if (!isProcessing) {
                TextButton(onClick = { onConfirm(reason) }) {
                    Text(strings.closeCafeButton)
                }
            }
        },
        dismissButton = {
            if (!isProcessing) {
                TextButton(onClick = onDismiss) {
                    Text(strings.commonCancel)
                }
            }
        }
    )
}
