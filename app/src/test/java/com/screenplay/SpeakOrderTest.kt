/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeakOrderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun order(count: Int, spoken: Set<Int> = emptySet()) = SpeakOrder(spoken).also { o -> repeat(count) { o.add("s$it") }; o.finish() }
    private fun SpeakOrder.drain(): List<Int> = generateSequence { pick() }.map { it.first }.toList()

    @Test
    fun withoutAJumpTheBookIsSpokenInOrder() {
        assertEquals(listOf(0, 1, 2, 3, 4), order(5).drain())
    }

    @Test
    fun afterAJumpSpeechStartsThereAndWrapsToTheEarliestSentenceWithoutAudio() {
        val o = order(8)
        assertEquals(listOf(0, 1), listOf(o.pick()!!.first, o.pick()!!.first))
        o.jump(5)
        assertEquals(listOf(5, 6, 7, 2, 3, 4), o.drain())
    }

    @Test
    fun aSentenceThatHasAudioIsNeverSpokenAgain() {
        val o = order(6, spoken = setOf(0, 1, 4))
        o.jump(3)
        assertEquals(listOf(3, 5, 2), o.drain())
    }

    @Test
    fun jumpingIntoSpokenTextContinuesAtTheFirstSentenceAfterItWithoutAudio() {
        val o = order(6, spoken = setOf(0, 1, 2, 3))
        o.jump(1)
        assertEquals(listOf(4, 5), o.drain())
    }

    @Test
    fun aSecondJumpMovesOnFromWhereItLandsAndTheFirstGapStaysForTheWrap() {
        val o = order(10)
        o.jump(6)
        assertEquals(listOf(6, 7), listOf(o.pick()!!.first, o.pick()!!.first))
        o.jump(2)
        assertEquals(listOf(2, 3, 4, 5, 8, 9, 0, 1), o.drain())
    }

    @Test
    fun everySentenceIsSpokenExactlyOnceWhateverTheJumps() {
        val o = order(50)
        val got = ArrayList<Int>()
        for (jump in listOf(30, 10, 45, 0, 20)) {
            o.jump(jump)
            repeat(7) { o.pick()?.let { got += it.first } }
        }
        got += o.drain()
        assertEquals((0 until 50).toList(), got.sorted())
    }

    @Test
    fun textStillBeingReadIsSpokenBeforeTheWrapAndTheWrapComesAfterTheLastSentence() = runBlocking {
        val o = SpeakOrder()
        repeat(6) { o.add("s$it") }
        o.jump(4)
        val got = ArrayList<Int>()
        repeat(2) { got += o.take()!!.first } // 4, 5
        o.add("s6")
        got += o.take()!!.first // the new text comes before the gap at 0..3
        assertEquals(listOf(4, 5, 6), got)
        o.finish()
        while (true) got += (o.take() ?: break).first
        assertEquals(listOf(4, 5, 6, 0, 1, 2, 3), got)
    }

    @Test
    fun takeWaitsForTextAndEndsWhenTheTextIsFinishedAndAllTaken() = runBlocking {
        val o = SpeakOrder()
        val first = async { o.take() }
        o.add("hello")
        assertEquals(0 to "hello", withTimeout(2_000) { first.await() })
        val end = async { o.take() }
        o.finish()
        assertNull(withTimeout(2_000) { end.await() })
    }

    @Test
    fun aJumpWakesAWaitingSpeaker() = runBlocking {
        val o = SpeakOrder(spoken = setOf(0, 1, 2))
        repeat(5) { o.add("s$it") }
        // 3 and 4 are next, then the text is exhausted: a jump back into spoken text changes nothing, a jump to 4 picks 4 first.
        o.jump(4)
        assertEquals(4, o.take()!!.first)
    }

    @Test
    fun theJumpIsKeptInTheBooksFolderAndReadBack() {
        val dir = tmp.newFolder()
        val f = File(dir, Files.JUMP)
        assertFalse(f.exists())
        f.writeText("12")
        assertEquals(12, f.readText().trim().toInt())
        assertTrue(f.delete())
    }

    @Test
    fun heldSentencesAreSpokenOnlyAfterTheirChapterIsReleased() {
        val held = order(6).also { it.hold() }
        assertNull(held.pick())
        held.releaseUpTo(2)
        assertEquals(listOf(0, 1), held.drain())
        held.releaseUpTo(6)
        assertEquals(listOf(2, 3, 4, 5), held.drain())
    }

    @Test
    fun aJumpPastTheReleasedChapterSpeaksTheReleasedSentencesFirst() {
        val held = order(6).also { it.hold(); it.releaseUpTo(3); it.jump(5) }
        assertEquals(listOf(0, 1, 2), held.drain())
    }
}
