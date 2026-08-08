package com.razstudio.opsapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.opsapp.ui.viewmodels.ConnectExistingCafeViewModel
import com.razstudio.opsapp.ui.viewmodels.ConnectExistingPhase

/**
 * Every field here holds a **case-sensitive** credential, so the keyboard must not touch what is
 * typed. A phone IME auto-capitalises the first character of a field by default, which silently
 * turned `sb_secret_…` into `Sb_secret_…` and came back from Supabase as a flat
 * "Invalid API key" — a failure the operator has no way to see, because the capital letter looks
 * like ordinary sentence case. Autocorrect is off for the same reason.
 */
private val CREDENTIAL_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrect = false,
    keyboardType = KeyboardType.Ascii,
)

/**
 * Connect to an existing café by its Supabase credentials.
 *
 * Deliberately a plain form rather than a QR scan: the café's **service-role key** is what authorises
 * this device, and no QR the system produces carries it (the owner QR carries the *anon* key). A
 * scanner here could only ever fill two of the three fields, so it would look like the whole job was
 * done while leaving the one that matters empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectExistingCafeScreen(
    onConnected: () -> Unit,
    onBack: () -> Unit,
    viewModel: ConnectExistingCafeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    var supabaseUrl by remember { mutableStateOf("") }
    var anonKey by remember { mutableStateOf("") }
    var serviceRoleKey by remember { mutableStateOf("") }

    LaunchedEffect(state.phase) {
        if (state.phase == ConnectExistingPhase.DONE) onConnected()
    }

    val busy = state.phase == ConnectExistingPhase.REGISTERING ||
        state.phase == ConnectExistingPhase.VERIFYING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to Café") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            if (busy) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = when (state.phase) {
                            ConnectExistingPhase.REGISTERING -> "Registering this device…"
                            else -> "Verifying operator access…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                return@Column
            }

            Text(
                text = "Connect to an existing café",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Enter the café's Supabase details. This device registers itself as an " +
                    "operator immediately — no invite and no admin approval needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = supabaseUrl,
                onValueChange = { supabaseUrl = it },
                label = { Text("Supabase URL") },
                placeholder = { Text("https://abcdefgh.supabase.co") },
                singleLine = true,
                keyboardOptions = CREDENTIAL_KEYBOARD.copy(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = anonKey,
                onValueChange = { anonKey = it },
                label = { Text("Anon / publishable key") },
                placeholder = { Text("sb_publishable_… or eyJ…") },
                keyboardOptions = CREDENTIAL_KEYBOARD,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = serviceRoleKey,
                onValueChange = { serviceRoleKey = it },
                label = { Text("Service-role key") },
                placeholder = { Text("sb_secret_… or eyJ…") },
                keyboardOptions = CREDENTIAL_KEYBOARD,
                supportingText = {
                    Text("Used once to register this device. Never stored on the phone.")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = state.errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.connect(supabaseUrl, anonKey, serviceRoleKey) },
                enabled = supabaseUrl.isNotBlank() && anonKey.isNotBlank() && serviceRoleKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect")
            }
        }
    }
}
