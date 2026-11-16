/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which chapters an exported audiobook marks. */
class ExportSpansTest {
    private val segments = listOf(Segment(0, 4_000, "a"), Segment(4_000, 9_000, "b"), Segment(9_000, 12_000, "c"))

    @Test
    fun aBookWithNoChaptersIsOneChapterNamedForTheBook() {
        assertEquals(listOf(Span("The Book", 0, 12_000)), Export.spans(segments, emptyList(), "The Book"))
    }

    @Test
    fun audioBeforeTheFirstHeadingIsNamedStart() {
        val spans = Export.spans(segments, listOf(Chapter(1, 4_000, "One")), "The Book")
        assertEquals(listOf(Span("Start", 0, 4_000), Span("One", 4_000, 12_000)), spans)
    }

    @Test
    fun aHeadingAtTheVeryStartNeedsNoStart() {
        val spans = Export.spans(segments, listOf(Chapter(0, 0, "One"), Chapter(2, 9_000, "Two")), "The Book")
        assertEquals(listOf(Span("One", 0, 9_000), Span("Two", 9_000, 12_000)), spans)
    }

    @Test
    fun twoHeadingsAtTheSameTimeMakeOneChapter() {
        val spans = Export.spans(segments, listOf(Chapter(1, 4_000, "One"), Chapter(1, 4_000, "One again")), "The Book")
        assertEquals(listOf("Start", "One"), spans.map { it.title })
    }

    @Test
    fun noMoreThan255ChaptersAreMarkedBecauseTheFormatCannotCountMore() {
        val many = List(400) { Chapter(it, it * 10L, "Chapter $it") }
        val spans = Export.spans(listOf(Segment(0, 4_000, "a")), many, "The Book")
        assertEquals(254, spans.size)
        assertTrue(spans.zipWithNext().all { (a, b) -> a.end == b.start })
    }

    @Test
    fun aBookWithNoSpeechIsOneEmptyChapter() {
        assertEquals(listOf(Span("The Book", 0, 0)), Export.spans(emptyList(), emptyList(), "The Book"))
    }
}
