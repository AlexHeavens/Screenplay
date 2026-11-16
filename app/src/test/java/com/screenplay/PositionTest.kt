/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionTest {
    private val book = Book("id", "t", Status.READY, 1f, null, durationMs = 200_000, created = 0)
    private val segments = listOf(Segment(0, 10, "a"), Segment(10, 20, "b"), Segment(20, 30, "c"))

    @Test fun aBookNeverPlayedHasNoPercentage() {
        assertNull(book.playedPercent)
        assertEquals("Ready · 3:20", book.label())
        assertEquals(0L, Played.resumeAt(segments, null))
    }

    @Test fun percentIsPositionOverDurationWithTheEndsIncluded() {
        assertEquals(0, book.copy(positionMs = 0).playedPercent)
        assertEquals(42, book.copy(positionMs = 84_000).playedPercent)
        assertEquals(100, book.copy(positionMs = 200_000).playedPercent)
        assertEquals(100, book.copy(positionMs = 5_000, finished = true).playedPercent)
        assertEquals("Ready · 3:20 · 42% played", book.copy(positionMs = 84_000).label())
    }

    @Test fun aBookStillBeingMadeScalesTheAudioSoFarByTheShareSpoken() {
        // 100 s of audio exist for a quarter of the book, so the whole is about 400 s: 100 s in is 25%.
        val making = book.copy(status = Status.SPEAKING, durationMs = 100_000, words = 500, wordsTotal = 2000, positionMs = 100_000)
        assertEquals(25, making.playedPercent)
        assertEquals("Reading 100% · Speaking 25% · 500 of 2,000 words · 25% played", making.label())
        assertEquals(0, making.copy(words = 0, durationMs = 0).playedPercent)
    }

    @Test fun resumeIsTheStartOfTheParagraphHoldingThePosition() {
        assertEquals(10L, Played.resumeAt(segments, 15))
        assertEquals(20L, Played.resumeAt(segments, 999))
        assertEquals(0L, Played.resumeAt(emptyList(), 15))
    }

    private val saved = ArrayList<Triple<String, Long, Boolean>>()
    private var time = 0L
    private val saver = PositionSaver({ listOf(0L, 10L, 20L) }, { id, ms, f -> saved += Triple(id, ms, f) }, { time })

    @Test fun savesOncePerParagraphWhilePlayingOnly() {
        saver.tick("b", false, 12, 30, true)
        assertEquals(emptyList<Any>(), saved)
        saver.tick("b", true, 1, 30, true)
        saver.tick("b", true, 9, 30, true)
        saver.tick("b", true, 11, 30, true)
        saver.tick("b", true, 12, 30, true)
        assertEquals(listOf(Triple("b", 0L, false), Triple("b", 10L, false)), saved)
    }

    @Test fun savesFinishedWhenPlaybackStopsAtTheEndAndPlayingAgainClearsIt() {
        saver.tick("b", true, 25, 30_000, true)
        saver.tick("b", false, 30_000, 30_000, true)
        assertEquals(Triple("b", 30_000L, true), saved.last())
        saver.tick("b", true, 25, 30_000, true)
        assertEquals(Triple("b", 20L, false), saved.last())
    }

    @Test fun savesAPlaceMovedToWhilePausedButNotTheFirstLookAtAnUnplayedBook() {
        saver.tick("b", true, 1, 30_000, true)
        saver.tick("b", false, 1, 30_000, true)
        saver.tick("b", false, 12, 30_000, true)
        saver.tick("b", false, 13, 30_000, true)
        assertEquals(listOf(Triple("b", 0L, false), Triple("b", 10L, false)), saved)
        time = 10_000
        val opened = ArrayList<Long>()
        val other = PositionSaver({ listOf(0L, 10L, 20L) }, { _, ms, _ -> opened += ms }, { time })
        other.tick("c", false, 12, 30_000, true)
        other.tick("c", false, 12, 30_000, true)
        assertEquals(emptyList<Long>(), opened)
    }

    @Test fun aPauseMidBookIsNotFinished() {
        saver.tick("b", true, 5, 30_000, true)
        saver.tick("b", false, 5, 30_000, true)
        assertEquals(1, saved.size)
    }
}
