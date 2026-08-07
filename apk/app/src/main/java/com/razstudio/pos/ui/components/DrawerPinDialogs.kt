package com.razstudio.pos.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.razstudio.pos.ui.i18n.UiStrings

/** Length bounds for the cash-drawer PIN. Four is the shortest that is not guessable by shoulder-surfing a single tap; eight is as long as a cashier will reliably type under pressure. */
private const val MIN_PIN = 4
private const val MAX_PIN = 8

/**
 * Set or change the cash-drawer PIN, in three deliberate steps.
 *
 * ## Why the current PIN is always required
 *
 * This PIN opens a cash drawer. The admin phone is often unlocked on the counter, and Settings is
 * reachable by anyone holding it — so "knows the old PIN" is the only thing standing between a
 * passer-by and silently re-keying the till to a number they chose. On first setup there is no
 * current PIN to ask for, and the step is skipped rather than faked.
 *
 * ## Why the new PIN is entered twice
 *
 * A mistyped PIN is not discovered the way a mistyped password is. There is no "wrong PIN" message
 * at the drawer — the drawer simply does not open, and it looks exactly like a hardware fault. The
 * café would have no way to tell a typo from a broken cable, and no way to recover the number they
 * thought they set. The confirmation step is what makes that impossible.
 */
@Composable
fun DrawerPinDialog(
    strings: UiStrings,
    /** True when a PIN already exists, so the current-PIN step applies. */
    requiresCurrent: Boolean,
    onVerifyCurrent: (String) -> Boolean,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var stage by remember { mutableStateOf(if (requiresCurrent) Stage.CURRENT else Stage.NEW) }
    var current by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val title = when (stage) {
        Stage.CURRENT -> strings.drawerPinCurrentPrompt
        Stage.NEW -> strings.drawerPinNewPrompt
        Stage.CONFIRM -> strings.drawerPinConfirmTitle
    }
    val value = when (stage) {
        Stage.CURRENT -> current
        Stage.NEW -> newPin
        Stage.CONFIRM -> confirm
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (stage == Stage.CONFIRM) {
                    Text(
                        text = strings.drawerPinConfirmBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(MAX_PIN)
                        error = null
                        when (stage) {
                            Stage.CURRENT -> current = digits
                            Stage.NEW -> newPin = digits
                            Stage.CONFIRM -> confirm = digits
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (stage) {
                    Stage.CURRENT ->
                        if (onVerifyCurrent(current)) { stage = Stage.NEW }
                        else { error = strings.drawerPinWrongCurrent }
                    Stage.NEW ->
                        if (newPin.length in MIN_PIN..MAX_PIN) { stage = Stage.CONFIRM }
                        else { error = strings.drawerPinTooShort }
                    Stage.CONFIRM ->
                        // Compared against the PIN just chosen, not re-validated for length: a
                        // mismatch here means one of the two was mistyped, and the honest thing is
                        // to send them back to choose again rather than guess which.
                        if (confirm == newPin) { onSet(newPin) }
                        else { confirm = ""; newPin = ""; stage = Stage.NEW; error = strings.drawerPinTooShort }
                }
            }) { Text(strings.commonConfirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.commonCancel) }
        },
    )
}

private enum class Stage { CURRENT, NEW, CONFIRM }
