/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Test

class BookLabelTest {
    private val book = Book("id", "t", Status.READING, 0f, null, 0, 0)

    @Test
    fun readingShowsItsPercentageAndTheWordsSpokenSoFar() {
        assertEquals("Reading 25% · 1 of 4 pages", book.copy(pages = 1, pagesTotal = 4).label())
        assertEquals(
            "Reading 50% · 2 of 4 pages · Speaking 1,200 words so far · ready to play",
            book.copy(pages = 2, pagesTotal = 4, words = 1200, rate = 16000, durationMs = 5000).label(),
        )
    }

    @Test
    fun everyLaterPhaseShowsTheEarlierOnesAsComplete() {
        val speaking = book.copy(status = Status.SPEAKING, words = 500, wordsTotal = 2000)
        assertEquals("Reading 100% · Speaking 25% · 500 of 2,000 words", speaking.label())
        assertEquals("Reading 100% · Speaking 100% · Encoding 50%", book.copy(status = Status.ENCODING, progress = 0.95f).label())
    }
}

class BookProgressTest {
    private val book = Book("id", "t", Status.READING, 0f, null, 0, 0, pagesTotal = 10)

    @Test
    fun speechProgressIsTheSpokenShareOfTheWordsReadScaledByThePagesRead() {
        val b = book.copy(pages = 4, words = 100, wordsQueued = 200)
        assertEquals(0.4f, b.readProgress, 0.001f)
        assertEquals(0.2f, b.speechProgress, 0.001f)
    }

    @Test
    fun onceTheLastPageIsReadSpeechProgressIsWordsSpokenOfTheTotal() {
        val b = book.copy(status = Status.SPEAKING, words = 500, wordsTotal = 2000)
        assertEquals(1f, b.readProgress, 0.001f)
        assertEquals(0.25f, b.speechProgress, 0.001f)
    }

    @Test
    fun aBookWithNothingReadHasNoProgressAndOnlyAFinishedOrReadingBookOpens() {
        assertEquals(0f, book.speechProgress, 0f)
        assertEquals(false, book.copy(status = Status.QUEUED).openable)
        assertEquals(true, book.openable)
        assertEquals(false, book.copy(status = Status.READY).importing)
    }
}
