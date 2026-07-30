package com.razstudio.pos.ui.ambient

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.R
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.AmbientViewModel
import kotlinx.coroutines.delay

/** How long a new-order card stays up before fading out, so nothing lingers on the panel. */
private const val NEW_ORDER_VISIBLE_MS = 12_000L

/**
 * Full-screen ambient overlay. Rendered above the whole nav graph (like the demo banner) rather than
 * as a separate Activity — that keeps the running session, foreground service, and view-models
 * completely untouched, and means dismissing it can never disturb the back stack.
 *
 * Any touch anywhere resumes work.
 */
@Composable
fun AmbientOverlay(
    onDismiss: () -> Unit,
    viewModel: AmbientViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
) {
    val tables by viewModel.tableStates.collectAsState()
    val newOrder by viewModel.newOrder.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)

    // Retire the celebration card on its own timer.
    LaunchedEffect(newOrder) {
        if (newOrder != null) {
            delay(NEW_ORDER_VISIBLE_MS)
            viewModel.clearNewOrder()
        }
    }

    val fallbackName = stringResource(R.string.app_name)
    val cafeName = viewModel.cafeName.ifBlank { fallbackName }

    val newOrderLabel = newOrder?.let { order ->
        val tableLabel = tables.firstOrNull { it.table.id == order.tableId }?.table?.label
        if (tableLabel != null) "${strings.newOrder} · $tableLabel" else strings.newOrder
    }

    // Back must dismiss ambient mode, never pop the screen underneath it.
    BackHandler(enabled = true) { onDismiss() }

    /**
     * Go truly fullscreen for as long as ambient mode is showing.
     *
     * The app targets SDK 36, so Android draws it edge-to-edge and paints the status/navigation
     * bars ON TOP of our content. Against the ambient screen's true-black ground the system's
     * dark-on-light bar icons are unreadable, and a live clock plus notification icons sitting
     * over a screensaver also defeats the burn-in protection — they never move.
     *
     * So hide both bars (they stay swipe-reachable) and, for the moment they are transiently
     * swiped in, flip the icons to their light variant so they read on black. Everything is
     * restored on dispose, so the rest of the app keeps its normal system-bar appearance.
     */
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousLightNavBars = controller?.isAppearanceLightNavigationBars

        controller?.apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        onDispose {
            controller?.apply {
                show(WindowInsetsCompat.Type.systemBars())
                previousLightStatusBars?.let { isAppearanceLightStatusBars = it }
                previousLightNavBars?.let { isAppearanceLightNavigationBars = it }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Consume all touches: the first tap wakes the station instead of hitting the UI below.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        AmbientScreen(
            tables = tables,
            newOrderLabel = newOrderLabel,
            cafeName = cafeName,
            isCustomerFacing = viewModel.isCustomerFacing(),
            strings = strings,
        )
    }
}
