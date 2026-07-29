package com.warungtomyam.pos.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.warungtomyam.pos.ui.i18n.LanguageViewModel
import com.warungtomyam.pos.ui.i18n.UiStrings
import com.warungtomyam.pos.ui.i18n.uiStrings

@Composable
fun DemoWalkthroughScreen(
    currentStep: Int,
    onNextStep: () -> Unit,
    onSkip: () -> Unit,
    languageViewModel: LanguageViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    Box(modifier = Modifier.fillMaxSize()) {
        // Underlying screen content
        content()

        // Semi-transparent overlay for walkthrough
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            WalkthroughCard(
                step = currentStep,
                strings = strings,
                onNextStep = onNextStep,
                onSkip = onSkip
            )
        }
    }
}

@Composable
private fun WalkthroughCard(
    step: Int,
    strings: UiStrings,
    onNextStep: () -> Unit,
    onSkip: () -> Unit
) {
    val (title, description, buttonText) = when (step) {
        1 -> Triple(
            strings.walkthroughStep1Title,
            strings.walkthroughStep1Desc,
            strings.commonNext
        )
        2 -> Triple(
            strings.walkthroughStep2Title,
            strings.walkthroughStep2Desc,
            strings.commonNext
        )
        3 -> Triple(
            strings.walkthroughStep3Title,
            strings.walkthroughStep3Desc,
            strings.getStartedButton
        )
        else -> Triple("", "", "")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Text(
                    text = "${strings.stepOfLabel} $step/3",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onSkip) {
                    Text(strings.skipWalkthroughButton)
                }

                Button(onClick = onNextStep) {
                    Text(buttonText)
                }
            }
        }
    }
}
