package com.warungtomyam.pos.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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

private fun sanitizePin(input: String): String = input.filter { it.isDigit() }.take(4)

/**
 * Gate dialog: challenge for the PIN before Settings opens. [onVerify] returns true when the
 * entered PIN is correct. [onForgot], when non-null, offers a "Forgot PIN" reset.
 */
@Composable
fun PinEntryDialog(
    strings: com.warungtomyam.pos.ui.i18n.UiStrings,
    title: String,
    onVerify: (String) -> Boolean,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    onForgot: (() -> Unit)? = null
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = sanitizePin(it); error = false },
                    label = { Text(strings.pinFieldLabel) },
                    singleLine = true,
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Text(strings.pinIncorrect, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onVerify(pin)) onSuccess() else error = true
            }) { Text(strings.pinUnlock) }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onCancel) { Text(strings.commonCancel) }
                if (onForgot != null) {
                    TextButton(onClick = onForgot) { Text(strings.pinForgot) }
                }
            }
        }
    )
}

/**
 * Set-a-new-PIN dialog (initial enable). [onSet] receives the 4-digit PIN once both entries
 * match; the dialog surfaces mismatch/length errors itself.
 */
@Composable
fun SetPinDialog(
    strings: com.warungtomyam.pos.ui.i18n.UiStrings,
    title: String,
    onSet: (String) -> Unit,
    onCancel: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = sanitizePin(it); error = null },
                    label = { Text(strings.newPinLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = sanitizePin(it); error = null },
                    label = { Text(strings.confirmPinLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length != 4 -> error = strings.pinMust4Digits
                    pin != confirm -> error = strings.pinsDontMatch
                    else -> onSet(pin)
                }
            }) { Text(strings.commonSave) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(strings.commonCancel) } }
    )
}

/**
 * Change-PIN dialog: requires the current PIN plus a new one. [onChange] returns true on
 * success (current PIN correct + new PIN valid); false shows an error.
 */
@Composable
fun ChangePinDialog(
    strings: com.warungtomyam.pos.ui.i18n.UiStrings,
    onChange: (currentPin: String, newPin: String) -> Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(strings.changePinTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { currentPin = sanitizePin(it); error = null },
                    label = { Text(strings.currentPinLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = sanitizePin(it); error = null },
                    label = { Text(strings.newPinLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onChange(currentPin, newPin)) onDone() else error = strings.pinChangeError
            }) { Text(strings.commonSave) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(strings.commonCancel) } }
    )
}
