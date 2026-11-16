/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphEditTest {
    private val texts = listOf("First paragraph.", "Second paragraph here.", "Third paragraph.", "Fourth paragraph.")
    private val segments = texts.mapIndexed { i, text -> Segment(i * 1000L, (i + 1) * 1000L, text) }
    private fun hash(index: Int) = ParagraphNotes.hash(texts[index])

    private fun state(
        notes: List<ParagraphNote> = emptyList(),
        highlights: List<ParagraphHighlight> = emptyList(),
        chapters: List<ChapterMark>? = emptyList(),
    ) = TranscriptState(segments, notes, highlights, chapters)

    @Test
    fun splittingKeepsTheHighlightOnBothPartsAndTheNoteOnTheFirstOnly() {
        val before = state(
            notes = listOf(ParagraphNote(1, hash(1), "check this")),
            highlights = listOf(ParagraphHighlight(1, hash(1), HighlightColor.GREEN)),
        )
        val after = ParagraphEdit.replace(before, 1, "Second paragraph\nhere, in two.")

        assertEquals(listOf("First paragraph.", "Second paragraph", "here, in two.", "Third paragraph.", "Fourth paragraph."), after.segments.map { it.text })
        assertEquals(setOf(1, 2), Highlights.place(after.highlights, after.segments).keys)
        assertTrue(Highlights.place(after.highlights, after.segments).values.all { it.color == HighlightColor.GREEN })
        assertEquals(mapOf(1 to "check this"), ParagraphNotes.place(after.paragraphNotes, after.segments).mapValues { it.value.text })
    }

    @Test
    fun splittingDividesTheOriginalSpanByTextLengthAndLeavesNoGap() {
        val after = ParagraphEdit.replace(state(), 1, "aaaa\nbbbbbbbb")
        val parts = after.segments.subList(1, 3)
        assertEquals(1000L, parts.first().start)
        assertEquals(1333L, parts.first().end)
        assertEquals(1333L, parts.last().start)
        assertEquals(2000L, parts.last().end)
        assertEquals(after.segments.dropLast(1).map { it.end }, after.segments.drop(1).map { it.start })
        assertEquals(1, Timeline.indexAt(after.segments, 1200))
        assertEquals(2, Timeline.indexAt(after.segments, 1500))
    }

    @Test
    fun splittingMovesLaterNotesHighlightsAndChaptersDownByOne() {
        val before = state(
            notes = listOf(ParagraphNote(3, hash(3), "late")),
            highlights = listOf(ParagraphHighlight(2, hash(2), HighlightColor.BLUE)),
            chapters = listOf(ChapterMark(0, "One"), ChapterMark(1, "Two"), ChapterMark(2, "Three")),
        )
        val after = ParagraphEdit.replace(before, 1, "Second\nparagraph")
        assertEquals(listOf(4), after.paragraphNotes.map { it.index })
        assertEquals(listOf(3), after.highlights.map { it.index })
        assertEquals(listOf(0, 1, 3), after.chapters!!.map { it.index })
        assertEquals(mapOf(4 to "late"), ParagraphNotes.place(after.paragraphNotes, after.segments).mapValues { it.value.text })
    }

    @Test
    fun editingWithoutASplitKeepsSpanNoteAndHighlightOnTheNewText() {
        val before = state(
            notes = listOf(ParagraphNote(1, hash(1), "note")),
            highlights = listOf(ParagraphHighlight(1, hash(1), HighlightColor.PINK)),
        )
        val after = ParagraphEdit.replace(before, 1, "  A better second paragraph.  ")
        assertEquals(Segment(1000, 2000, "A better second paragraph."), after.segments[1])
        assertEquals(4, after.segments.size)
        assertEquals(setOf(1), ParagraphNotes.place(after.paragraphNotes, after.segments).keys)
        assertEquals(setOf(1), Highlights.place(after.highlights, after.segments).keys)
    }

    @Test
    fun blankLinesAndSpacesMakeNoEmptyParagraph() {
        assertEquals(listOf("a", "b"), ParagraphEdit.parts("a\n\n  \n b \n"))
        assertEquals(emptyList<String>(), ParagraphEdit.parts(" \n "))
    }

    @Test
    fun deletingRemovesTheParagraphItsNoteAndHighlightAndShiftsLaterOnesUp() {
        val before = state(
            notes = listOf(ParagraphNote(1, hash(1), "gone"), ParagraphNote(3, hash(3), "kept")),
            highlights = listOf(ParagraphHighlight(1, hash(1), HighlightColor.YELLOW), ParagraphHighlight(2, hash(2), HighlightColor.BLUE)),
            chapters = listOf(ChapterMark(1, "Two"), ChapterMark(2, "Three")),
        )
        val after = ParagraphEdit.delete(before, 1)

        assertEquals(listOf("First paragraph.", "Third paragraph.", "Fourth paragraph."), after.segments.map { it.text })
        assertEquals(mapOf(2 to "kept"), ParagraphNotes.place(after.paragraphNotes, after.segments).mapValues { it.value.text })
        assertEquals(mapOf(1 to HighlightColor.BLUE), Highlights.place(after.highlights, after.segments).mapValues { it.value.color })
        assertEquals(listOf(ChapterMark(1, "Three")), after.chapters)
    }

    @Test
    fun deletingKeepsTheAudioCoveredByGivingTheSpanToTheNeighbour() {
        val middle = ParagraphEdit.delete(state(), 1).segments
        assertEquals(Segment(0, 2000, "First paragraph."), middle[0])
        assertEquals(0, Timeline.indexAt(middle, 1500))
        assertEquals(1, Timeline.indexAt(middle, 2000))

        val first = ParagraphEdit.delete(state(), 0).segments
        assertEquals(Segment(0, 2000, "Second paragraph here."), first[0])

        val last = ParagraphEdit.delete(state(), 3).segments
        assertEquals(4000L, last.last().end)
    }

    @Test
    fun aBookWithNoStoredChaptersStaysThatWay() {
        assertEquals(null, ParagraphEdit.delete(state(chapters = null), 1).chapters)
        assertEquals(null, ParagraphEdit.replace(state(chapters = null), 1, "a\nb").chapters)
    }
}
