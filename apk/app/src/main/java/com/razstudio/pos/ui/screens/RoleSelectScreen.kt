package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.ui.i18n.LanguageButton
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings

/**
 * Entry point screen: choose role (Ordering Staff or Admin).
 * Big "Connect as Ordering Staff" button, smaller "Connect as Admin" button.
 */
@Composable
fun RoleSelectScreen(
    onAdminConnect: () -> Unit,
    onOrderingConnect: () -> Unit,
    onTryDemo: () -> Unit = {},
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    Surface(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.fillMaxSize()) {
        // Language selector, top-right (default BM). Visible from first open.
        LanguageButton(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Warung Tom Yam",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = strings.appSubtitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(64.dp))

            // Primary: Ordering Staff (most common role)
            Button(
                onClick = onOrderingConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = strings.connectAsStaff,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Secondary: Admin (one-time setup)
            OutlinedButton(
                onClick = onAdminConnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.connectAsAdmin)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Demo Mode entry point
            OutlinedButton(
                onClick = onTryDemo,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.tryDemo)
            }
        }
      }
    }
}
