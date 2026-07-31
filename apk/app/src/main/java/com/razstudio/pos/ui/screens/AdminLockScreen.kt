package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.ui.components.AdBannerFooter
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import kotlinx.coroutines.delay

/**
 * Lock screen shown after sign-out. The admin token is kept in SecureStorage,
 * so re-opening doesn't require re-authentication — just a tap.
 *
 * M-9 mitigation: The reopen button is delayed by 2 seconds so it can't be
 * accidentally or casually tapped while the screen is briefly visible.
 */
@Composable
fun AdminLockScreen(
    onReopen: () -> Unit,
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    var unlockReady by remember { mutableStateOf(false) }
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    LaunchedEffect(Unit) {
        delay(2000L)
        unlockReady = true
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        // The app parks here overnight, so the banner sits in its own row at the bottom — far from
        // the centred Reopen button, which is itself delayed 2s against stray taps. AdBanner loads
        // once and pauses with the lifecycle, so a device left on this screen until morning renders
        // one impression rather than a night's worth to an empty room.
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = strings.cafeClosedTitle,
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.cafeClosedDesc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.tapToReopenDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onReopen,
                    enabled = unlockReady
                ) {
                    Text(if (unlockReady) strings.reopenCafeButton else "…")
                }
            }

            AdBannerFooter()
        }
    }
}
