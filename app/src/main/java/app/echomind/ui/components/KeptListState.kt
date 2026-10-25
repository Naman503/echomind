package app.echomind.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A list position that survives going to another page and back. Pages load their
 * data again when you return, so for a moment the list is short; a plain list
 * state would be pulled to the top then. This one waits until the list has
 * loaded far enough (up to 3 s), then puts you back where you were.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberKeptListState(): LazyListState {
    // Index, offset and how many rows the page had, kept with the page's saved state (same array, updated as you scroll).
    val saved = rememberSaveable { intArrayOf(0, 0, 0) }
    val state = remember { LazyListState(0, 0) }
    LaunchedEffect(state) {
        val (index, offset, rows) = Triple(saved[0], saved[1], saved[2])
        if (index > 0 || offset > 0) {
            // Wait until the page has loaded back to (about) the rows it had, and stopped changing.
            withTimeoutOrNull(3_000) {
                snapshotFlow { state.layoutInfo.totalItemsCount }.debounce(150).first { it > index && it >= rows }
            } ?: withTimeoutOrNull(500) { snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > index } }
            if (state.layoutInfo.totalItemsCount > index) {
                state.scrollToItem(index, offset)
                // Bars around the list can settle a moment later and clamp the end of a list; put it back once.
                kotlinx.coroutines.delay(300)
                if (!state.isScrollInProgress && (state.firstVisibleItemIndex != index || state.firstVisibleItemScrollOffset != offset)) state.scrollToItem(index, offset)
            }
        }
        // Only positions you scrolled to count: when the page is leaving, bars around it change size and
        // the list re-settles by itself, which must not overwrite where you were.
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) { saved[0] = state.firstVisibleItemIndex; saved[1] = state.firstVisibleItemScrollOffset; saved[2] = state.layoutInfo.totalItemsCount }
        }
    }
    return state
}
