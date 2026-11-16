/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportNoticeTest {
    private fun book(id: String, status: Status, order: Long = 0) = Book(id, "Title $id", status, 0f, null, 0, order.toLong(), queueOrder = order)

    @Test
    fun theNoticeNamesTheBookBeingMadeAndItsStageWithPercent() {
        val reading = book("a", Status.READING).copy(pages = 5, pagesTotal = 10)
        val notice = ImportNotice.of(listOf(book("b", Status.QUEUED, 1), reading), paused = false)
        assertEquals(ImportNotice("Title a", "Reading 50%", false), notice)
    }

    @Test
    fun speakingShowsTheWordsShare() {
        val speaking = book("a", Status.SPEAKING).copy(pages = 10, pagesTotal = 10, words = 500, wordsTotal = 2000)
        assertEquals("Speaking 25%", ImportNotice.of(listOf(speaking), false).text)
    }

    @Test
    fun followsTheQueueWhenTheNextBookTakesOver() {
        val done = book("a", Status.READY)
        val next = book("b", Status.READING).copy(pages = 1, pagesTotal = 10)
        assertEquals("Title b", ImportNotice.of(listOf(done, next), false).title)
    }

    @Test
    fun aWaitingBookIsNamedWhenNoneRuns() {
        assertEquals(ImportNotice("Title b", "Waiting", false), ImportNotice.of(listOf(book("c", Status.QUEUED, 2), book("b", Status.QUEUED, 1)), false))
    }

    @Test
    fun pausedSaysSoAndKeepsTheStage() {
        val speaking = book("a", Status.SPEAKING).copy(words = 500, wordsTotal = 2000)
        assertEquals(ImportNotice("Title a", "Paused · Speaking 25%", true), ImportNotice.of(listOf(speaking), true))
    }

    @Test
    fun anEmptyQueueHasTheGeneralTitle() {
        assertEquals(ImportNotice("Making an audiobook", "", false), ImportNotice.of(emptyList(), false))
    }

    @Test
    fun theShelfLabelsTheRunningBookPausedOnlyWhilePaused() {
        val running = book("a", Status.SPEAKING)
        assertEquals("Paused", ImportQueue.label(listOf(running), running, paused = true))
        assertEquals("", ImportQueue.label(listOf(running), running, paused = false))
    }
}
