/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StagesTest {
    private val pdf = Book("id", "t", Status.QUEUED, 0f, null, 0, 0)
    private val found = pdf.copy(downloadUrl = "https://example.org/book.epub", awaitingDownload = true)

    private fun shown(book: Book, download: DownloadProgress? = null) = Stages.of(book, download).map { it.kind to it.state }

    @Test
    fun aQueuedPdfShowsItsFetchAsDoneAndReadingAsNext() {
        assertEquals(listOf(StageKind.DOWNLOAD to StageState.DONE, StageKind.READ to StageState.NEXT), shown(pdf))
    }

    @Test
    fun aBookFromFindBooksStartsWithItsFetchAndShowsNothingLater() {
        assertEquals(listOf(StageKind.DOWNLOAD to StageState.NEXT), shown(found))
        val running = Stages.of(found, DownloadProgress(1024, 2048))
        assertEquals("the fetch runs and reading is next", listOf(StageKind.DOWNLOAD to StageState.ONGOING, StageKind.READ to StageState.NEXT), running.map { it.kind to it.state })
        val fetching = running.first()
        assertEquals(0.5f, fetching.fraction)
        assertEquals("50% · 1/2 KB", fetching.text)
        assertEquals(listOf(StageKind.DOWNLOAD to StageState.DONE, StageKind.READ to StageState.NEXT), shown(found.copy(awaitingDownload = false)))
    }

    @Test
    fun aFetchOfUnknownLengthHasNoFractionAndOnlyTheBytesSoFar() {
        val fetching = Stages.of(found, DownloadProgress(1_048_576, -1)).first()
        assertNull(fetching.fraction)
        assertEquals("1.0 MB", fetching.text)
    }

    @Test
    fun readingShowsItsPercentAndPagesAndSpeakingIsNextUntilItHasWords() {
        val reading = pdf.copy(status = Status.READING, pages = 3, pagesTotal = 12)
        val stages = Stages.of(reading, null)
        assertEquals(listOf(StageKind.DOWNLOAD to StageState.DONE, StageKind.READ to StageState.ONGOING, StageKind.SPEAK to StageState.NEXT), stages.map { it.kind to it.state })
        assertEquals("25% · 3/12 pages", stages[1].text)
    }

    @Test
    fun readingAndSpeakingRunTogetherAndEncodingIsNext() {
        val both = pdf.copy(status = Status.READING, pages = 6, pagesTotal = 12, words = 1200, wordsQueued = 2400)
        val stages = Stages.of(both, null)
        assertEquals(listOf(StageState.DONE, StageState.ONGOING, StageState.ONGOING, StageState.NEXT), stages.map { it.state })
        assertEquals("1,200 words", stages[2].text.substringAfter("% · "))
    }

    @Test
    fun speakingShowsItsPercentAndWordsOutOfTheTotalWithReadingDone() {
        val speaking = pdf.copy(status = Status.SPEAKING, pages = 12, pagesTotal = 12, words = 500, wordsTotal = 2000)
        val stages = Stages.of(speaking, null)
        assertEquals(listOf(StageState.DONE, StageState.DONE, StageState.ONGOING, StageState.NEXT), stages.map { it.state })
        assertEquals("25% · 500/2,000 words", stages[2].text)
    }

    @Test
    fun encodingShowsItsPercentWithEverythingBeforeItDone() {
        val stages = Stages.of(pdf.copy(status = Status.ENCODING, progress = 0.95f), null)
        assertEquals(listOf(StageState.DONE, StageState.DONE, StageState.DONE, StageState.ONGOING), stages.map { it.state })
        assertEquals("50%", stages.last().text)
    }

    @Test
    fun aFailedBookShowsNoStages() {
        assertTrue(Stages.of(pdf.copy(status = Status.FAILED, error = "x"), null).isEmpty())
    }

    @Test
    fun aReadyBookShowsItsListeningAsTimeListenedOverItsLength() {
        val ready = pdf.copy(status = Status.READY, durationMs = 200_000)
        val never = Stages.of(ready, null).single()
        assertEquals(StageKind.LISTEN to StageState.DONE, never.kind to never.state)
        assertEquals(0f, never.fraction)
        assertEquals("0% · 0:00/3:20", never.text)
        val half = Stages.of(ready.copy(positionMs = 84_000), null).single()
        assertEquals(0.42f, half.fraction)
        assertEquals("42% · 1:24/3:20", half.text)
        assertEquals("42% · 1:24/3:20 · Chapter 3", Stages.of(ready.copy(positionMs = 84_000), null, "Chapter 3").single().text)
        assertEquals("100% · 3:20/3:20", Stages.of(ready.copy(positionMs = 5_000, finished = true), null).single().text)
        assertTrue("a failed book has no stages", Stages.of(pdf.copy(status = Status.FAILED, error = "x"), null).isEmpty())
    }
}
