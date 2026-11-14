/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Test

/** Chapters are a property of the text: found with no audio, and placed on audio times only once a sentence is spoken. */
class ChapterDetectionTest {
    private val text = listOf(
        "It was a long road home.", "Nobody spoke for hours.",
        "Chapter Two",
        "The next morning was bright.", "They set out early.",
        "Appendix A",
    )

    @Test fun detectsHeadingsInTextThatHasNoAudio() {
        assertEquals(listOf(ChapterMark(2, "Chapter Two"), ChapterMark(5, "Appendix A")), Chapters.detect(text))
    }

    @Test fun marksOnUnspokenSentencesStartAtZeroAndSpokenOnesAtTheirAudioTime() {
        val spoken = text.take(3).mapIndexed { i, t -> Segment(i * 1000L, i * 1000L + 900, t) }
        val chapters = Chapters.place(Chapters.detect(text), spoken)
        assertEquals(listOf(Chapter(2, 2000, "Chapter Two"), Chapter(5, 0, "Appendix A")), chapters)
    }
}
