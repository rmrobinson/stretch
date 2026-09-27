package ca.rmrobinson.stretch

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * Drag-to-reorder for a keyed LazyColumn. The dragged row is drawn at its layout position
 * plus [dragOffset]; whenever its visual midpoint crosses a neighbour's midpoint, [onMove]
 * swaps them in the backing list and [dragOffset] is rebased so the row stays under the
 * finger. Dragging near the top/bottom edge auto-scrolls the list.
 */
class DragReorderState(
    private val listState: LazyListState,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set

    private val draggedItem: LazyListItemInfo?
        get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }

    fun onDragStart(key: Any) {
        draggingKey = key
        dragOffset = 0f
    }

    fun onDrag(deltaY: Float) {
        dragOffset += deltaY
        swapIfNeeded()
    }

    fun onDragEnd() {
        draggingKey = null
        dragOffset = 0f
    }

    private fun swapIfNeeded() {
        val item = draggedItem ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val mid = item.offset + dragOffset + item.size / 2f
        val target = if (dragOffset > 0) {
            visible.firstOrNull { it.index == item.index + 1 }?.takeIf { mid > it.offset + it.size / 2f }
        } else {
            visible.firstOrNull { it.index == item.index - 1 }?.takeIf { mid < it.offset + it.size / 2f }
        } ?: return
        // Where the dragged row's top will land after the swap, so we can keep it visually still.
        val newTop = if (target.index > item.index) target.offset + target.size - item.size else target.offset
        // LazyColumn anchors on the first visible item's key; if that's one of the two being
        // swapped, pin the scroll position so the list doesn't jump with it.
        val first = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        onMove(item.index, target.index)
        dragOffset -= newTop - item.offset
        if (first == item.index || first == target.index) {
            listState.requestScrollToItem(first, firstOffset)
        }
    }

    /** Scroll step (px/frame) when the dragged row overhangs the viewport edge, else 0. */
    internal fun overscroll(): Float {
        val item = draggedItem ?: return 0f
        val info = listState.layoutInfo
        val top = item.offset + dragOffset
        val bottom = top + item.size
        return when {
            bottom > info.viewportEndOffset -> ((bottom - info.viewportEndOffset) / 10f).coerceIn(1f, 30f)
            top < info.viewportStartOffset -> -((info.viewportStartOffset - top) / 10f).coerceIn(1f, 30f)
            else -> 0f
        }
    }

    internal suspend fun autoScroll() {
        while (draggingKey != null) {
            val step = overscroll()
            if (step != 0f) {
                val consumed = listState.scrollBy(step)
                // Scrolling moves the row's layout position; compensate so it stays under the finger.
                dragOffset += consumed
                swapIfNeeded()
            }
            withFrameNanos { }
        }
    }
}

@Composable
fun rememberDragReorderState(listState: LazyListState, onMove: (from: Int, to: Int) -> Unit): DragReorderState {
    val state = remember(listState) { DragReorderState(listState, onMove) }
    LaunchedEffect(state.draggingKey) { if (state.draggingKey != null) state.autoScroll() }
    return state
}
