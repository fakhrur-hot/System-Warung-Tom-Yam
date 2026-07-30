package com.razstudio.pos.ui.tableview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.razstudio.pos.ui.i18n.UiStrings

/**
 * Single table cell in the grid, colored by [TableState.order] status via [tableColor].
 * Displays the table label and a human-readable status line.
 * Shared between the admin and staff roles (Requirement 5.1, 5.2).
 */
@Composable
fun TableCell(
    tableState: TableState,
    strings: UiStrings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = tableState.order?.status.tableColor()
    val statusLabel = when (tableState.status) {
        TableUiStatus.FREE            -> strings.free
        TableUiStatus.RECEIVED        -> strings.statusNew
        TableUiStatus.SENT_TO_KITCHEN -> strings.statusKitchen
        TableUiStatus.PREPARING       -> strings.statusPreparing
        TableUiStatus.READY           -> strings.statusReady
    }

    Box(
        modifier = modifier
            .size(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = tableState.table.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
        }
    }
}
