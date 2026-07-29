package com.warungtomyam.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.warungtomyam.pos.realtime.OemKeepAliveHelper
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.UiStrings
import com.warungtomyam.pos.ui.i18n.uiStrings

/**
 * Setup guide screen that walks users through OEM-specific battery
 * whitelisting steps so the foreground services survive all day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepAliveSetupScreen(
    onBack: () -> Unit,
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    val context = LocalContext.current
    val oemType = remember { OemKeepAliveHelper.getOemType() }
    val steps = remember { OemKeepAliveHelper.getWhitelistSteps(context) }

    var isExempt by remember {
        mutableStateOf(OemKeepAliveHelper.isIgnoringBatteryOptimizations(context))
    }

    // Refresh battery optimization status when returning from settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isExempt = OemKeepAliveHelper.isIgnoringBatteryOptimizations(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.backgroundSetupTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.commonBack
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Explanation header
            Text(
                text = strings.keepAliveExplanation,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Detected OEM
            Text(
                text = "${strings.detectedLabel} ${oemDisplayName(oemType)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Battery optimization status
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isExempt) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Exempt",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.batteryOptDisabled,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF4CAF50)
                    )
                } else {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Not exempt",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.batteryOptActive,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Whitelist steps
            if (steps.isEmpty()) {
                Text(
                    text = strings.allStepsComplete,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50)
                )
            } else {
                steps.forEachIndexed { index, step ->
                    WhitelistStepCard(
                        stepNumber = index + 1,
                        step = step,
                        strings = strings,
                        onOpenSettings = {
                            step.intent?.let { intent ->
                                OemKeepAliveHelper.launchSafely(context, intent)
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Done button
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.commonDone)
            }
        }
    }
}

@Composable
private fun WhitelistStepCard(
    stepNumber: Int,
    step: OemKeepAliveHelper.WhitelistStep,
    strings: UiStrings,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${strings.stepLabel} $stepNumber",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onOpenSettings,
                enabled = step.intent != null
            ) {
                Text(if (step.intent != null) strings.openSettingsButton else strings.manualStepButton)
            }
        }
    }
}

private fun oemDisplayName(oemType: OemKeepAliveHelper.OemType): String = when (oemType) {
    OemKeepAliveHelper.OemType.XIAOMI -> "Xiaomi (MIUI)"
    OemKeepAliveHelper.OemType.SAMSUNG -> "Samsung (One UI)"
    OemKeepAliveHelper.OemType.OPPO -> "OPPO/Realme/OnePlus (ColorOS)"
    OemKeepAliveHelper.OemType.VIVO -> "Vivo (Funtouch OS)"
    OemKeepAliveHelper.OemType.HUAWEI -> "Huawei/Honor (EMUI)"
    OemKeepAliveHelper.OemType.STOCK_ANDROID -> "Stock Android"
}
