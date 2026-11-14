/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Drag a book in the import queue up or down: the row follows the finger and, on release, takes the place [onDrop] is given
 * (a place in the line, 0 for the book made first). The row being dragged is drawn over the others. A row starts to move after
 * a press and hold anywhere on it ([modifier]), or at once from its handle ([handle]).
 */
class QueueDrag(
    private val books: () -> List<Book>,
    private val onDrop: (id: String, to: Int) -> Unit,
) {
    private var dragging by mutableStateOf<String?>(null)
    private var offset by mutableFloatStateOf(0f)
    private var rowHeight = 0f

    fun isDragging(id: String) = dragging == id

    private fun start(id: String) { dragging = id; offset = 0f }

    private fun move(amount: Float) { offset += amount }

    private fun cancel() { dragging = null; offset = 0f }

    private fun end(id: String) {
        // One row of drag is one place; the rows sit in line order, one below the other.
        val places = if (rowHeight > 0f) (offset / rowHeight).roundToInt() else 0
        val at = ImportQueue.lineIndex(books(), id)
        cancel()
        if (places != 0 && at >= 0) onDrop(id, at + places)
    }

    fun modifier(id: String): Modifier = Modifier
        .onSizeChanged { rowHeight = it.height.toFloat() }
        .zIndex(if (dragging == id) 1f else 0f)
        .graphicsLayer { translationY = if (dragging == id) offset else 0f }
        .pointerInput(id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { start(id) },
                onDrag = { change, amount -> change.consume(); move(amount.y) },
                onDragEnd = { end(id) },
                onDragCancel = ::cancel,
            )
        }

    /** The handle on a row: a touch that moves starts the drag at once, with no wait. */
    fun handle(id: String): Modifier = Modifier.pointerInput(id) {
        detectDragGestures(
            onDragStart = { start(id) },
            onDrag = { change, amount -> change.consume(); move(amount.y) },
            onDragEnd = { end(id) },
            onDragCancel = ::cancel,
        )
    }
}

@Composable
fun rememberQueueDrag(books: List<Book>, onDrop: (id: String, to: Int) -> Unit): QueueDrag {
    val latest by rememberUpdatedState(books)
    val drop by rememberUpdatedState(onDrop)
    return remember { QueueDrag({ latest }, { id, to -> drop(id, to) }) }
}
