package com.razstudio.pos.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.realtime.OrderingForegroundService
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.UiStrings
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.OrderingViewModel

/**
 * Ordering home — state-machine container that renders CafeClosedScreen,
 * CheckInScreen, or OrderingScreen based on the ViewModel state.
 * Starts the OrderingForegroundService on composition.
 */
@Composable
fun OrderingHomeScreen(
    viewModel: OrderingViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isCheckingIn by viewModel.isCheckingIn.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    // Start the foreground service when this screen is composed
    DisposableEffect(Unit) {
        // In Demo Mode there is no backend/notifications; keep the app fully offline.
        if (!com.razstudio.pos.data.demo.DemoSession.active) OrderingForegroundService.start(context)
        onDispose { /* Service stays running — it's persistent */ }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = state, label = "cafe_state") { currentState ->
            when (currentState) {
                OrderingViewModel.CafeState.LOADING -> LoadingScreen(strings)
                OrderingViewModel.CafeState.CAFE_CLOSED -> CafeClosedScreen(strings)
                OrderingViewModel.CafeState.CHECK_IN -> CheckInScreen(
                    strings = strings,
                    errorMessage = errorMessage,
                    isLoading = isCheckingIn,
                    onCheckIn = { viewModel.checkIn() },
                    onClearError = { viewModel.clearError() }
                )
                OrderingViewModel.CafeState.ORDERING -> OrderingScreen(
                    onCheckOut = { viewModel.checkOut() }
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen(strings: UiStrings) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = strings.connectingLabel,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/**
 * Displayed when the café is closed. Waits for CAFE_OPEN broadcast.
 */
@Composable
private fun CafeClosedScreen(strings: UiStrings) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🔒",
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = strings.cafeClosedTitle,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = strings.cafeClosedWaitingDesc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Check-in screen: validates GPS proximity before allowing attendance.
 */
@Composable
private fun CheckInScreen(
    strings: UiStrings,
    errorMessage: String?,
    isLoading: Boolean,
    onCheckIn: () -> Unit,
    onClearError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = "Location",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = strings.readyToCheckInTitle,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strings.gpsVerifyDesc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                onClearError()
                onCheckIn()
            },
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(strings.checkingLocationLabel)
            } else {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(strings.checkInButton)
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Ordering screen: staff is checked in. Shows the full Staff Table View
 * with RBAC-controlled order management and new order entry.
 */
@Composable
private fun OrderingScreen(
    onCheckOut: () -> Unit
) {
    StaffTableViewScreen(onCheckOut = onCheckOut)
}
