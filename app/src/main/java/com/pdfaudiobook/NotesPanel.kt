/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One row of the Notes panel: a timestamped note or a paragraph note, placed in the book.
 * [paragraph] is the transcript index of a paragraph note or highlight, and null for a timestamped note. A highlighted paragraph has its [quote] and pen [color];
 * its [text] is the note on it, empty when there is none.
 */
data class NoteItem(val key: String, val at: Long, val percent: Int, val chapter: String, val text: String, val paragraph: Int?, val quote: String? = null, val color: HighlightColor? = null)

/**
 * The list of every note in the book. A tap on a row plays from it, a swipe right edits it in place and a swipe left deletes it (a highlight with no note: removes the highlight).
 */
@Composable
fun NotesPanel(
    items: List<NoteItem>,
    editingKey: String?,
    onOpen: (NoteItem) -> Unit,
    onEdit: (NoteItem) -> Unit,
    onEdited: (NoteItem, String) -> Unit,
    onDelete: (NoteItem) -> Unit,
    onCancelEdit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The panel covers the transcript, so it must take the taps and drags that land on it rather than pass them through.
    // A swipe right that no row has taken (a row swipes right to edit) closes the panel, or drops the open note field first.
    var cancelTick by remember { mutableIntStateOf(0) }
    val closeAt = with(LocalDensity.current) { 96.dp.toPx() }
    Surface(
        modifier.pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(closeAt, editingKey) {
                var dragged = 0f
                detectHorizontalDragGestures(onDragStart = { dragged = 0f }) { _, amount ->
                    dragged += amount
                    if (dragged >= closeAt) { dragged = Float.NEGATIVE_INFINITY; if (editingKey != null) cancelTick++ else onClose() }
                }
            }
            .testTag("notes-panel"),
        tonalElevation = 8.dp, shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Notes", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            if (items.isEmpty()) {
                Text("No notes yet", Modifier.padding(16.dp).testTag("panel-empty"), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(items, key = { _, n -> n.key }) { i, n ->
                    SwipeRow(onRight = { if (editingKey == n.key) cancelTick++ else onEdit(n) }, rightHint = "✎ Edit", onLeft = { onDelete(n) }) {
                        Column(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)).clickable { onOpen(n) }
                                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("panel-note-$i"),
                        ) {
                            Text("${n.percent}% · ${n.chapter}", Modifier.testTag("panel-note-meta-$i"), style = MaterialTheme.typography.labelMedium)
                            if (n.quote != null && n.color != null) PenText(n.quote, n.color, Modifier.testTag("panel-highlight-$i").semantics { highlight = n.color.name }, MaterialTheme.typography.bodyMedium)
                            if (editingKey == n.key) InlineNoteField(n.text, "panel-note-input", cancelTick, onCancelEdit) { onEdited(n, it) }
                            else if (n.text.isNotEmpty()) Text(n.text, Modifier.testTag("panel-note-text-$i"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
