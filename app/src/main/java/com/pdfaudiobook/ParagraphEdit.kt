/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

/** Everything that points into the transcript by paragraph position, so one edit can move all of it together. */
data class TranscriptState(
    val segments: List<Segment>,
    val paragraphNotes: List<ParagraphNote>,
    val highlights: List<ParagraphHighlight>,
    /** Null for a book whose chapters were never detected, which stays that way. */
    val chapters: List<ChapterMark>?,
)

/**
 * Editing and deleting one paragraph of a finished book. The audio is never re-spoken, so the paragraph's span of the audio stays
 * where it is: an edited paragraph keeps the span of the original (a split divides it by text length), and a deleted paragraph's span
 * is absorbed by its neighbour, so the audio still plays whole, the segments still cover it without a gap, and [Timeline.indexAt] still works.
 */
object ParagraphEdit {
    /** The paragraphs the user's text makes: each non-blank line is one. */
    fun parts(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Replaces paragraph [at] with the paragraphs of [text]. A split keeps the highlight on every part but leaves the note on the first part only;
     * everything after the paragraph moves down by the number of parts added.
     */
    fun replace(state: TranscriptState, at: Int, text: String): TranscriptState {
        val texts = parts(text)
        require(texts.isNotEmpty()) { "A paragraph cannot be edited to nothing; delete it instead." }
        val added = texts.size - 1
        val original = state.segments[at]
        val segments = state.segments.take(at) + divide(original, texts) + state.segments.drop(at + 1)
        val notes = remap(
            state.paragraphNotes, ParagraphNotes.place(state.paragraphNotes, state.segments),
            moveUnplaced = { if (it.index > at) it.copy(index = it.index + added) else it },
            movePlaced = { note, index ->
                when {
                    index < at -> listOf(note)
                    index == at -> listOf(note.copy(hash = ParagraphNotes.hash(texts.first())))
                    else -> listOf(note.copy(index = index + added))
                }
            },
        )
        val highlights = remap(
            state.highlights, Highlights.place(state.highlights, state.segments),
            moveUnplaced = { if (it.index > at) it.copy(index = it.index + added) else it },
            movePlaced = { highlight, index ->
                when {
                    index < at -> listOf(highlight)
                    index == at -> texts.mapIndexed { part, partText -> ParagraphHighlight(at + part, ParagraphNotes.hash(partText), highlight.color) }
                    else -> listOf(highlight.copy(index = index + added))
                }
            },
        )
        return TranscriptState(segments, notes, highlights, state.chapters?.map { if (it.index > at) it.copy(index = it.index + added) else it })
    }

    /** Removes paragraph [at] with its note and highlight, and moves everything after it up by one. A chapter that started on it is dropped. */
    fun delete(state: TranscriptState, at: Int): TranscriptState {
        val removed = state.segments[at]
        val segments = state.segments.toMutableList().also { it.removeAt(at) }
        // The silent span goes to the paragraph before, or to the one after when the first is removed, so the audio stays covered.
        if (at > 0) segments[at - 1] = segments[at - 1].copy(end = removed.end)
        else if (segments.isNotEmpty()) segments[0] = segments[0].copy(start = removed.start)
        val notes = remap(
            state.paragraphNotes, ParagraphNotes.place(state.paragraphNotes, state.segments),
            moveUnplaced = { if (it.index > at) it.copy(index = it.index - 1) else it },
            movePlaced = { note, index -> if (index == at) emptyList() else listOf(if (index > at) note.copy(index = index - 1) else note) },
        )
        val highlights = remap(
            state.highlights, Highlights.place(state.highlights, state.segments),
            moveUnplaced = { if (it.index > at) it.copy(index = it.index - 1) else it },
            movePlaced = { highlight, index -> if (index == at) emptyList() else listOf(if (index > at) highlight.copy(index = index - 1) else highlight) },
        )
        val chapters = state.chapters?.filter { it.index != at }?.map { if (it.index > at) it.copy(index = it.index - 1) else it }
        return TranscriptState(segments, notes, highlights, chapters)
    }

    /** [original]'s span cut into one piece per text, in proportion to the texts' lengths; the last piece ends exactly where the original did. */
    private fun divide(original: Segment, texts: List<String>): List<Segment> {
        val total = texts.sumOf { it.length }.toLong()
        var characters = 0L
        var start = original.start
        return texts.mapIndexed { part, text ->
            characters += text.length
            val end = if (part == texts.lastIndex) original.end else original.start + (original.end - original.start) * characters / total
            Segment(start, end, text).also { start = end }
        }
    }

    /** Each item moved by [movePlaced] from the paragraph it sits on now; one that sits on no paragraph (its text is gone) only follows the shift via [moveUnplaced]. */
    private fun <T> remap(items: List<T>, placed: Map<Int, T>, moveUnplaced: (T) -> T, movePlaced: (T, Int) -> List<T>): List<T> =
        items.flatMap { item ->
            val index = placed.entries.firstOrNull { it.value === item }?.key
            if (index == null) listOf(moveUnplaced(item)) else movePlaced(item, index)
        }
}
