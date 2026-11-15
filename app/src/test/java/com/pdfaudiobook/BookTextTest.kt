/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookTextTest {
    @get:Rule val tmp = TemporaryFolder()

    private val text = listOf("A.", "B.", "C.", "D.", "E.")

    @Test
    fun aBookSpokenInOneRunShowsSpokenSentencesThenPendingOnes() {
        val book = BookText.merge(listOf(Segment(0, 10, "A."), Segment(10, 20, "B.")), text)
        assertEquals(text, book.map { it.text })
        assertEquals(listOf(false, false, true, true, true), book.map { it.pending })
    }

    @Test
    fun aJumpLeavesPendingSentencesBetweenSpokenOnesInTheOrderOfTheText() {
        val spoken = listOf(Segment(0, 10, "A.", 0), Segment(10, 20, "D.", 3), Segment(20, 30, "E.", 4))
        val book = BookText.merge(spoken, text)
        assertEquals(text, book.map { it.text })
        assertEquals(listOf(false, true, true, false, false), book.map { it.pending })
        assertEquals(10L, book[3].start)
    }

    @Test
    fun aListFollowingTheAudioInTimeIsFoundByTheTimeNotThePositionInTheText() {
        val book = BookText.merge(listOf(Segment(0, 10, "A.", 0), Segment(10, 20, "D.", 3), Segment(20, 30, "E.", 4)), text)
        assertEquals(3, Timeline.indexAt(book, 15))
        assertEquals(4, Timeline.indexAt(book, 25))
        assertEquals(0, Timeline.indexAt(book, 5))
    }

    @Test
    fun textNotReadAgainYetCutsTheListAtTheFirstSentenceNothingKnows() {
        val book = BookText.merge(listOf(Segment(0, 10, "A.", 0), Segment(10, 20, "D.", 3)), listOf("A.", "B."))
        assertEquals(listOf("A.", "B."), book.map { it.text })
    }

    @Test
    fun anOldTranscriptWithoutPlacesIsTheStartOfTheText() {
        val book = BookText.merge(listOf(Segment(0, 10, "A."), Segment(10, 20, "B.")), emptyList())
        assertEquals(listOf("A.", "B."), book.map { it.text })
        assertTrue(book.none { it.pending })
    }

    private class Beep : Synthesizer {
        override suspend fun speak(text: String, wav: File) = Wav.write(wav, 22_050, ByteArray(2_000 * text.length))
        override fun close() = Unit
    }

    @Test
    fun speechAfterAJumpSpeaksFromTheJumpWrapsAndNeverRepeatsASentence() = runBlocking {
        val order = SpeakOrder(from = 4)
        listOf("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7").forEach { order.add(it) }
        order.finish()
        val pcm = tmp.newFile("a.pcm")
        val (segments, rate) = Speech.render(listOf(Beep()), order::take, tmp.newFile("s.wav"), pcm) { _, _ -> }
        assertEquals(listOf(4, 5, 6, 7, 0, 1, 2, 3), segments.map { it.at })
        assertEquals(segments.map { it.at }.toSet().size, segments.size)
        assertFalse(Speech.inBookOrder(segments))

        // Laid out in the order of the text, the audio of every sentence is the same and the times follow the text.
        val out = tmp.newFile("ordered.pcm")
        val ordered = Speech.inBookOrder(segments, rate, pcm, out)
        assertEquals((0..7).map { "s$it" }, ordered.map { it.text })
        // Times are whole milliseconds, so each sentence's range is found to within a millisecond (44 bytes at this rate).
        assertTrue(Math.abs(pcm.length() - out.length()) <= 8 * 44)
        assertEquals(ordered.sortedBy { it.start }, ordered)
        assertTrue(Math.abs(segments.sumOf { it.end - it.start } - ordered.last().end) <= 8)
        // A position inside "s5" (the second one spoken) moves to where "s5" now is.
        val inS5 = segments[1].start + 30
        assertEquals(ordered[5].start + 30, Speech.moved(inS5, segments, ordered))
    }

    @Test
    fun aBookSpokenInOrderNeedsNoReordering() = runBlocking {
        val order = SpeakOrder()
        listOf("s0", "s1", "s2").forEach { order.add(it) }
        order.finish()
        val (segments, _) = Speech.render(listOf(Beep()), order::take, tmp.newFile("s.wav"), tmp.newFile("a.pcm")) { _, _ -> }
        assertTrue(Speech.inBookOrder(segments))
    }
}
