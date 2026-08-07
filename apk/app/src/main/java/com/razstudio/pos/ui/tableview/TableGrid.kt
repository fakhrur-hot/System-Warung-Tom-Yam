package com.razstudio.pos.ui.tableview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.razstudio.pos.ui.i18n.UiStrings

/** Columns and rows a tablet till lays the floor plan out in. */
private const val TABLET_COLUMNS = 6
private const val TABLET_ROWS = 6

private val GRID_PADDING = 12.dp
private val GRID_SPACING = 8.dp

/**
 * Shared table grid composable.
 * Renders a [LazyVerticalGrid] of [TableCell]s; used by both the admin home and
 * the staff table-view screen (Requirement 5.1, 5.2).
 *
 * Callers supply their own click handler so each role can perform role-appropriate
 * actions (admin loads detail via [TableViewViewModel]; staff via [StaffOrderViewModel]).
 *
 * ## Two layouts, because a till is not a phone
 *
 * On a phone the grid stays adaptive: as many 100dp cells as fit, which is the right answer for a
 * hand-held screen of unknown width.
 *
 * A landscape tablet gets a fixed 6x6 floor plan instead. The adaptive rule was actively wrong
 * there: the D3 MINI reports a 1280dp-wide canvas (it is 1280px at density 160), so it packed
 * **eleven** 100dp columns across the screen and left the bottom third empty. The cards were as
 * small as a phone's while the screen was four times the size, which is the same legibility problem
 * the type ramp addresses — staff and customers could not read a table's status across the counter.
 *
 * Sizing to fill the viewport instead roughly doubles each card's width, and 6x6 holds 36 tables,
 * which is what a floor plan of thirty tables plus take-away slots actually needs.
 */
@Composable
fun TableGrid(
    tableStates: List<TableState>,
    strings: UiStrings,
    onTableClick: (TableState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Same threshold the type ramp uses, deliberately: both answer "is this a till or a phone?",
    // and letting them disagree would scale the text for a tablet inside a phone-shaped grid.
    val isTablet = configuration.smallestScreenWidthDp >= 600

    if (!isLandscape || !isTablet) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(GRID_PADDING),
            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
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
        return
    }

    BoxWithConstraints(modifier = modifier) {
        // The space one cell may occupy once padding and the gutters between cells are taken out.
        val usableWidth = maxWidth - GRID_PADDING * 2 - GRID_SPACING * (TABLET_COLUMNS - 1)
        val usableHeight =
            maxHeight - GRID_PADDING * 2 - FAB_CLEARANCE - GRID_SPACING * (TABLET_ROWS - 1)

        // Width and height are computed independently rather than squared off against the smaller
        // of the two. A square cell here would be capped by the height — six rows have to fit a
        // screen that is far wider than it is tall — and would leave the sides as empty as the
        // bottom used to be, for no gain in the thing being fixed: how big the label reads.
        val cellWidth = (usableWidth / TABLET_COLUMNS).coerceAtLeast(MIN_CELL_SIZE)
        val cellHeight = (usableHeight / TABLET_ROWS).coerceAtLeast(MIN_CELL_SIZE)

        LazyVerticalGrid(
            columns = GridCells.Fixed(TABLET_COLUMNS),
            contentPadding = PaddingValues(
                start = GRID_PADDING,
                end = GRID_PADDING,
                top = GRID_PADDING,
                bottom = GRID_PADDING + FAB_CLEARANCE,
            ),
            // Centred on both axes so a café with fewer than 36 tables gets its floor plan in the
            // middle of the screen rather than pinned to the top-left corner of a mostly empty one.
            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(tableStates) { tableState ->
                TableCell(
                    tableState = tableState,
                    strings = strings,
                    onClick = { onTableClick(tableState) },
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                )
            }
        }
    }
}

/**
 * Floor below which a cell stops shrinking and the grid scrolls instead.
 *
 * A cell smaller than this cannot hold a table label and a status word at any legible size, so on a
 * short screen it is better to overflow — the grid scrolls — than to render 36 unreadable chips.
 */
private val MIN_CELL_SIZE = 72.dp

/**
 * Room kept clear at the bottom for the New Dine-In Order button.
 *
 * The adaptive layout never needed this: eleven columns ran out of tables well before the bottom of
 * the screen, so the floating button sat over empty space. A grid that fills its viewport does not
 * have that luxury, and a café with exactly 36 tables would find the last one — Tapaw 6, on this
 * floor plan — permanently half-covered by the button.
 */
private val FAB_CLEARANCE = 72.dp
