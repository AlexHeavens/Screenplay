/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What an import has done so far, each as a share of the whole book: pages read by OCR, and text that has audio. */
data class ImportProgress(val read: Float, val speech: Float)

/**
 * A strip along one edge of the transcript: drag it to scroll the whole book at once. Ticks mark chapter starts,
 * and [onScrub] reports the sentence under the finger (null once released) so the caller can name the chapter.
 * While the book is still being made, [progress] shades the track: light grey for the pages read so far, darker grey
 * over it for the part that has audio, white for the unread rest. The transcript holds the sentences read so far,
 * spoken or not, so they span the read part of the track.
 */
@Composable
fun BookScrubber(
    count: Int, chapters: List<Chapter>, list: LazyListState, onScrub: (Int?) -> Unit, modifier: Modifier = Modifier,
    progress: ImportProgress? = null,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val last = (count - 1).coerceAtLeast(1)
    // The share of the track the transcript covers; the whole track once the book is finished.
    val span = progress?.read?.coerceAtLeast(0.01f) ?: 1f
    val fraction = dragging ?: (list.firstVisibleItemIndex.toFloat() / last * span)
    val track = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.secondary
    val thumb = MaterialTheme.colorScheme.primary
    val readGrey = ReadGrey
    val speechGrey = SpeechGrey

    Box(
        modifier.width(28.dp).fillMaxHeight().pointerInput(count) {
            fun go(y: Float) {
                if (count == 0) return
                val f = (y / size.height).coerceIn(0f, span)
                val index = (f / span * (count - 1)).toInt()
                dragging = f
                onScrub(index)
                scope.launch { list.scrollToItem(index) }
            }
            fun stop() { dragging = null; onScrub(null) }
            detectDragGestures(onDragStart = { go(it.y) }, onDragEnd = ::stop, onDragCancel = ::stop) { change, _ ->
                change.consume()
                go(change.position.y)
            }
        },
    ) {
        Canvas(Modifier.fillMaxHeight().width(28.dp)) {
            val x = size.width / 2
            if (progress == null) {
                drawLine(track, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx())
            } else {
                drawLine(readGrey, Offset(x, 0f), Offset(x, progress.read * size.height), strokeWidth = 4.dp.toPx())
                drawLine(speechGrey, Offset(x, 0f), Offset(x, progress.speech * size.height), strokeWidth = 4.dp.toPx())
            }
            chapters.forEach {
                val y = it.index.toFloat() / last * span * size.height
                drawLine(tick, Offset(x - 9.dp.toPx(), y), Offset(x + 9.dp.toPx(), y), strokeWidth = 2.dp.toPx())
            }
            val h = 36.dp.toPx()
            drawRoundRect(thumb, Offset(x - 6.dp.toPx(), (fraction * (size.height - h))), Size(12.dp.toPx(), h),
                androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        }
    }
}
