/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The edges of editing and deleting a paragraph: the first one, the only one, one that opens a chapter, and an edit to nothing. */
class ParagraphEditEdgeTest {
    private val segments = listOf(Segment(0, 1000, "One."), Segment(1000, 2000, "Two."), Segment(2000, 3000, "Three."))
    private fun state(items: List<Segment> = segments, chapters: List<ChapterMark>? = emptyList()) = TranscriptState(items, emptyList(), emptyList(), chapters)

    @Test
    fun deletingTheFirstParagraphGivesItsSpanToTheNextSoTheAudioStillStartsAtZero() {
        val after = ParagraphEdit.delete(state(), 0)
        assertEquals(listOf("Two.", "Three."), after.segments.map { it.text })
        assertEquals(0L, after.segments.first().start)
        assertEquals(2000L, after.segments.first().end)
    }

    @Test
    fun deletingTheLastParagraphGivesItsSpanToThePreviousOne() {
        val after = ParagraphEdit.delete(state(), 2)
        assertEquals(3000L, after.segments.last().end)
    }

    @Test
    fun deletingTheOnlyParagraphLeavesAnEmptyTranscript() {
        val after = ParagraphEdit.delete(state(listOf(Segment(0, 1000, "Only."))), 0)
        assertTrue(after.segments.isEmpty())
    }

    @Test
    fun deletingTheParagraphThatOpensAChapterDropsTheChapterAndMovesLaterOnesUp() {
        val chapters = listOf(ChapterMark(1, "Chapter one"), ChapterMark(2, "Chapter two"))
        val after = ParagraphEdit.delete(state(chapters = chapters), 1)
        assertEquals(listOf(ChapterMark(1, "Chapter two")), after.chapters)
    }

    @Test
    fun aBookWithNoDetectedChaptersStaysThatWayAfterADelete() {
        assertEquals(null, ParagraphEdit.delete(state(chapters = null), 1).chapters)
    }

    @Test
    fun editingAParagraphToNothingIsRefusedAndSaysToDeleteIt() {
        try { ParagraphEdit.replace(state(), 1, "  \n  "); fail("expected a refusal") } catch (e: IllegalArgumentException) {
            assertEquals("A paragraph cannot be edited to nothing; delete it instead.", e.message)
        }
    }

    @Test
    fun editedTextIsTrimmedLineByLine() {
        assertEquals(listOf("a b", "c"), ParagraphEdit.parts("  a b  \n\n   \n c "))
    }
}
