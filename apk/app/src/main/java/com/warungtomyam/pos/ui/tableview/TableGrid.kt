package com.warungtomyam.pos.ui.tableview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.warungtomyam.pos.ui.i18n.UiStrings

/**
 * Shared table grid composable.
 * Renders a [LazyVerticalGrid] of [TableCell]s; used by both the admin home and
 * the staff table-view screen (Requirement 5.1, 5.2).
 *
 * Callers supply their own click handler so each role can perform role-appropriate
 * actions (admin loads detail via [TableViewViewModel]; staff via [StaffOrderViewModel]).
 */
@Composable
fun TableGrid(
    tableStates: List<TableState>,
    strings: UiStrings,
    onTableClick: (TableState) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        items(tableStates) { tableState ->
            TableCell(
                tableState = tableState,
                strings = strings,
                onClick = { onTableClick(tableState) },
            )
        }
    }
}
