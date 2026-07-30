package com.razstudio.pos.ui.ambient

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.razstudio.pos.R
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
) {
    val tables by viewModel.tableStates.collectAsState()
    val newOrder by viewModel.newOrder.collectAsState()

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
        if (tableLabel != null) "New order · $tableLabel" else "New order received"
    }

    // Back must dismiss ambient mode, never pop the screen underneath it.
    BackHandler(enabled = true) { onDismiss() }

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
        )
    }
}
