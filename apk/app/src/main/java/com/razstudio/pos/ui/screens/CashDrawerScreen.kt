package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.local.CashDrawerEvent
import com.razstudio.pos.ui.components.CashNumpad
import com.razstudio.pos.ui.components.cashEntryAppend
import com.razstudio.pos.ui.components.cashEntryBackspace
import com.razstudio.pos.ui.components.formatRm
import com.razstudio.pos.ui.viewmodels.CashDrawerViewModel

/**
 * The Drawer page: what the till drawer should hold right now, and every event that got it there.
 *
 * Body copy is deliberately English-only for now, following the Owner Recovery Key precedent on
 * the Devices screen — the menu entry is short enough to read in any language, and the audit rows
 * are numbers and timestamps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashDrawerScreen(
    viewModel: CashDrawerViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val balanceSen by viewModel.balanceSen.collectAsState()
    val events by viewModel.events.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Opening float entry: null = nothing keyed, grey shows the current balance.
    var floatEntry by remember { mutableStateOf<Long?>(null) }
    var showCashOut by remember { mutableStateOf(false) }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.clearNotice()
            floatEntry = null // committed — grey resumes showing the (new) balance
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Drawer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ── Expected balance ────────────────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Expected cash in drawer",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatRm(balanceSen),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // ── Opening float (saved on demand) ─────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Opening float", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Key in what you placed in the drawer this morning, then press Save. " +
                                "Nothing is recorded until you do.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = formatRm(floatEntry ?: balanceSen),
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (floatEntry == null)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Spacer(Modifier.height(8.dp))
                        // Keystrokes only move local entry state now — see
                        // CashDrawerViewModel: a typing pause used to commit a half-keyed figure
                        // as the expected balance.
                        CashNumpad(
                            onDigit = { d -> floatEntry = cashEntryAppend(floatEntry, d) },
                            onBackspace = { floatEntry = cashEntryBackspace(floatEntry) },
                            onClear = { floatEntry = null },
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { floatEntry?.let { viewModel.saveOpeningFloat(it) } },
                            // Nothing keyed means there is nothing to save — the grey figure on
                            // display is the balance already stored.
                            enabled = floatEntry != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Save opening float")
                        }
                    }
                }
            }

            // ── Cash out ────────────────────────────────────────────────────────────────
            item {
                OutlinedButton(
                    onClick = { showCashOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cash out — requires drawer PIN") }
            }

            // ── Audit trail ─────────────────────────────────────────────────────────────
            item {
                Text(
                    "Audit trail",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (events.isEmpty()) {
                item {
                    Text(
                        "No cash movements yet. Set the opening float to start the day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(events, key = { it.id }) { event ->
                DrawerEventRow(event)
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showCashOut) {
        CashOutDialog(
            onConfirm = { amountSen, pin -> viewModel.cashOut(amountSen, pin) },
            onDismiss = { showCashOut = false },
        )
    }
}

@Composable
private fun DrawerEventRow(event: CashDrawerEvent) {
    val (label, sign) = when (event.type) {
        CashDrawerEvent.TYPE_FLOAT_SET -> "Opening float set" to ""
        CashDrawerEvent.TYPE_CASH_SALE -> "Cash sale" to "+"
        CashDrawerEvent.TYPE_CASH_OUT -> "Cash out" to ""
        else -> event.type to ""
    }
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                // The raw ISO timestamp is UTC; trim to a readable local-enough marker. Reports
                // do proper timezone rendering — this row is for "was that the lunch rush?".
                Text(
                    event.timestamp.take(16).replace('T', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (event.type == CashDrawerEvent.TYPE_CASH_SALE) {
                    Text(
                        "Tendered ${formatRm(event.tenderedSen ?: 0)} · " +
                            "change ${formatRm(event.changeSen ?: 0)}" +
                            (event.orderId?.let { " · order ${it.take(8)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (event.usedDefaultPin) {
                    Text(
                        "⚠ authorised with the default PIN",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$sign${formatRm(event.amountSen)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (event.amountSen < 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "→ ${formatRm(event.balanceAfterSen)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CashOutDialog(
    onConfirm: (amountSen: Long, pin: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var amount by remember { mutableStateOf<Long?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cash out") },
        text = {
            Column {
                Text(
                    text = formatRm(amount ?: 0),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (amount == null)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(8.dp))
                CashNumpad(
                    onDigit = { amount = cashEntryAppend(amount, it) },
                    onBackspace = { amount = cashEntryBackspace(amount) },
                    onClear = { amount = null },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit); pinError = false },
                    label = { Text("Drawer PIN") },
                    isError = pinError,
                    supportingText = if (pinError) {
                        { Text("Wrong PIN") }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = (amount ?: 0) > 0 && pin.isNotBlank(),
                onClick = {
                    val ok = onConfirm(amount ?: 0, pin)
                    if (ok) onDismiss() else pinError = true
                },
            ) { Text("Take out") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
