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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.R
import com.razstudio.pos.ui.i18n.LanguageButton
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.SetupViewModel

/**
 * Entry point screen: choose role (Ordering Staff or Admin).
 * Big "Connect as Ordering Staff" button, smaller "Connect as Admin" button.
 */
@Composable
fun RoleSelectScreen(
    onAdminConnect: () -> Unit,
    onOrderingConnect: () -> Unit,
    onTryDemo: () -> Unit = {},
    onSetup: () -> Unit = {},
    languageViewModel: LanguageViewModel = hiltViewModel(),
    setupViewModel: SetupViewModel = hiltViewModel()
) {
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    // Show the operator's configured café name once set; otherwise the neutral app name.
    val configuredName by setupViewModel.state.collectAsState()
    val title = configuredName.cafeName.ifBlank { stringResource(R.string.app_name) }

    var menuOpen by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.fillMaxSize()) {
        // Setup / configuration menu, top-left (mirrors the language selector at top-right).
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Setup menu")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Setup") },
                    onClick = { menuOpen = false; onSetup() },
                )
            }
        }
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
                text = title,
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
