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
 * Drag a row of the import queue (a book, or a picked file still being saved) up or down: the row follows the finger and, on release, [onDrop] is given
 * the number of books above it (0 for the book made first). The row being dragged is drawn over the others. A row starts to move after
 * a press and hold anywhere on it ([modifier]), or at once from its handle ([handle]).
 */
class QueueDrag(
    private val rows: () -> List<ImportQueue.QueueRow>,
    private val onDrop: (id: String, pending: Boolean, place: Int) -> Unit,
) {
    private var dragging by mutableStateOf<String?>(null)
    private var offset by mutableFloatStateOf(0f)
    private var rowHeight = 0f

    fun isDragging(id: String) = dragging == id

    private fun start(id: String) { dragging = id; offset = 0f }

    private fun move(amount: Float) { offset += amount }

    private fun cancel() { dragging = null; offset = 0f }

    /** One row of drag is one place; the rows sit in line order, one below the other. */
    private fun moved() = if (rowHeight > 0f) (offset / rowHeight).roundToInt() else 0

    /** A book is dropped at the number of books above it; a picked file that is not yet a book is given the same number, which it takes up once it is one. */
    private fun end(id: String) {
        val moved = moved()
        val all = rows()
        val to = ImportQueue.dropRow(all, id, moved)
        cancel()
        if (moved != 0 && to >= 0) onDrop(id, all.first { it.id == id }.pending, ImportQueue.booksAbove(ImportQueue.moveRow(all, id, to), id))
    }

    /** While a row is dragged: true for the row where a line above it marks the drop place, false for a line below it, null for no line. A row left where it was has its own line, above it. */
    fun dropLineAbove(id: String): Boolean? {
        val moving = dragging ?: return null
        val all = rows()
        val from = all.indexOfFirst { it.id == moving }
        val to = ImportQueue.dropRow(all, moving, moved())
        if (from < 0 || all.indexOfFirst { it.id == id } != to) return null
        // Moving up, the dragged row lands above the row now there; moving down, below it.
        return to <= from
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
fun rememberQueueDrag(rows: List<ImportQueue.QueueRow>, onDrop: (id: String, pending: Boolean, place: Int) -> Unit): QueueDrag {
    val latest by rememberUpdatedState(rows)
    val drop by rememberUpdatedState(onDrop)
    return remember { QueueDrag({ latest }, { id, pending, place -> drop(id, pending, place) }) }
}
