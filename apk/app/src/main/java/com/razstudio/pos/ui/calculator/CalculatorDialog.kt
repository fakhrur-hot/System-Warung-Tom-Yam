package com.razstudio.pos.ui.calculator

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.data.local.CalculatorStore
import com.razstudio.pos.ui.i18n.UiStrings
import java.math.BigDecimal

/**
 * The counter calculator.
 *
 * Two display lines, like the Android calculator and like the desk calculator it replaces: the
 * expression above, the number below. Both survive the dialog closing, the app being backgrounded
 * and the shift ending — see [CalculatorStore] for why that is deliberate.
 *
 * The keypad is deliberately plain. Everything unusual about this feature is invisible: there is no
 * hint anywhere on screen that typing the right number and pressing `=` opens the cash drawer, and
 * there must not be.
 */
@Composable
fun CalculatorDialog(
    strings: UiStrings,
    onDismiss: () -> Unit,
    viewModel: CalculatorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val event by viewModel.event.collectAsState()

    // The drawer opening is acknowledged only by the drawer opening. No toast, no banner: a message
    // saying "cash drawer opened" on a screen a customer can see would announce the very thing the
    // PIN exists to keep quiet. The state is consumed so it cannot re-fire on recomposition.
    //
    // The ONE exception is the factory-default PIN. Stealth protects a secret; 666666 is printed
    // in the manual and is not one. Until the café sets its own PIN, every successful open nags —
    // the drawer springing open has already announced itself anyway, and an owner who sees the
    // warning daily will eventually change the PIN, which restores the silence.
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(event) {
        if (event == CalculatorViewModel.Event.DRAWER_OPENED_DEFAULT_PIN) {
            android.widget.Toast.makeText(
                context,
                "Drawer opened with the DEFAULT PIN (666666). Anyone can do this — change it in Settings.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        if (event != null) viewModel.consumeEvent()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(400.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = strings.calculatorTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))

                // ── Display ──────────────────────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = state.history,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.heightIn(min = 20.dp),
                    )
                    Text(
                        text = state.display,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── Memories ─────────────────────────────────────────────────────────────
                // Tap recalls, long-press stores. There is no clear: a memory is a standing figure
                // the café relies on, and an adjacent clear key is one mis-tap from losing it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CalculatorStore.SLOTS.forEach { slot ->
                        val stored = state.memories[slot]
                        MemoryKey(
                            label = "M$slot",
                            value = stored?.stripTrailingZeros()?.toPlainString(),
                            onRecall = { viewModel.memoryRecall(slot) },
                            onStore = { viewModel.memoryStore(slot) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    text = strings.calculatorMemoryHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.height(12.dp))

                // ── Percent row (the café-specific keys) ─────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FunctionKey("%", { viewModel.percent() }, Modifier.weight(1f))
                    FunctionKey("+6%", { viewModel.addPercent(BigDecimal(6)) }, Modifier.weight(1f))
                    FunctionKey("+10%", { viewModel.addPercent(BigDecimal(10)) }, Modifier.weight(1f))
                    FunctionKey("−10%", { viewModel.subtractPercent(BigDecimal(10)) }, Modifier.weight(1f))
                }

                Spacer(Modifier.height(8.dp))

                // ── Keypad ───────────────────────────────────────────────────────────────
                val rows = listOf(
                    listOf("AC", "C", "⌫", "÷"),
                    listOf("7", "8", "9", "×"),
                    listOf("4", "5", "6", "−"),
                    listOf("1", "2", "3", "+"),
                    listOf("±", "0", ".", "="),
                )
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { key ->
                            KeypadKey(
                                label = key,
                                onClick = { viewModel.press(key) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(strings.commonClose)
                }
            }
        }
    }
}

/** Routes a key label to the engine, so the layout table above stays declarative. */
private fun CalculatorViewModel.press(key: String) {
    when (key) {
        "AC" -> clearAll()
        "C" -> clearEntry()
        "⌫" -> backspace()
        "÷" -> operation(CalculatorEngine.Op.DIVIDE)
        "×" -> operation(CalculatorEngine.Op.MULTIPLY)
        "−" -> operation(CalculatorEngine.Op.SUBTRACT)
        "+" -> operation(CalculatorEngine.Op.ADD)
        "=" -> equals()
        "." -> decimalPoint()
        "±" -> negate()
        else -> key.firstOrNull()?.takeIf { it.isDigit() }?.let { digit(it) }
    }
}

@Composable
private fun KeypadKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isOperator = label in setOf("÷", "×", "−", "+", "=")
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = if (isOperator) ButtonDefaults.buttonColors()
                 else ButtonDefaults.filledTonalButtonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FunctionKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** Tap to recall, long-press to store. Shows what it holds, so a café can read its figures back. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryKey(
    label: String,
    value: String?,
    onRecall: () -> Unit,
    onStore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .combinedClickable(onClick = onRecall, onLongClick = onStore),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = value ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
