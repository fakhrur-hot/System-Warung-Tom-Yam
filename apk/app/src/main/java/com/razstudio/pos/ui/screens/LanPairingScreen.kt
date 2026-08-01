package com.razstudio.pos.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.ui.util.QrCodeUtil
import com.razstudio.pos.ui.viewmodels.LanPairingViewModel

/**
 * The Server Device's pairing screen (task 7.1, Requirement 5.1).
 *
 * Shows one QR for a staff phone to scan, and the address in plain text beneath it so the operator
 * can type it manually if the camera will not cooperate — the Client offers exactly that fallback
 * (task 7.2), and an address that is only ever encoded in a QR makes it unusable.
 *
 * When there is no usable network the QR is **replaced** by the reason, not shown greyed out or
 * alongside a warning. A code carrying an unreachable host is scannable, looks successful, and fails
 * minutes later at the counter (task 21.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanPairingScreen(
    onBack: () -> Unit,
    viewModel: LanPairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair a staff device") },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            when {
                state.loading -> CircularProgressIndicator()

                state.error != null -> {
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(onClick = { viewModel.refresh() }) { Text("Try again") }
                }

                state.payload != null -> {
                    val qr = remember(state.payload) { QrCodeUtil.encode(state.payload!!, 640) }
                    if (qr != null) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "Pairing QR code",
                            modifier = Modifier.size(280.dp),
                        )
                    }

                    Text(
                        text = "On the staff phone: Connect as Ordering Staff, then scan this code.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )

                    // The same address in text. The Client's manual-entry fallback needs it, and a
                    // camera that will not focus is a common enough failure to plan for.
                    Text(
                        text = "${state.host} : ${state.port}",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )

                    if (!state.serverRunning) {
                        // Address good, listener not up. Said explicitly because the QR looks
                        // perfectly valid and the phone would simply time out against it.
                        Text(
                            text = "The server is not running yet. Make sure this device is signed " +
                                "in as admin and stays on this screen's app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Text(
                        text = "This code works once, and expires after 15 minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    OutlinedButton(
                        onClick = { viewModel.regenerate() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("New code")
                    }
                    Text(
                        text = "Devices already paired keep working.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
