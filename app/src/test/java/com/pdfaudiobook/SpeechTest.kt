/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeechTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Stalls(private val bad: String) : Synthesizer {
        override suspend fun speak(text: String, wav: File) {
            if (text == bad) throw SentenceStalled(text)
            Wav.write(wav, 22_050, ByteArray(44_100))
        }
        override fun close() = Unit
    }

    @Test
    fun aStalledSentenceBecomesASecondOfSilenceAndTheBookContinues() = runBlocking {
        val (segments, _) = Speech.render(
            listOf(Stalls("bad"), Stalls("bad")), listOf("one", "bad", "two"), tmp.newFile("s.wav"), tmp.newFile("a.pcm"),
        ) {}
        assertEquals(listOf("one", "bad", "two"), segments.map { it.text })
        assertTrue(segments[1].end - segments[1].start >= 1_000)
    }

    @Test(expected = SentenceStalled::class)
    fun aStallOnTheFirstSentenceStillFailsBecauseTheSampleRateIsUnknown() {
        runBlocking { Speech.render(listOf(Stalls("bad")), listOf("bad"), tmp.newFile("s.wav"), tmp.newFile("a.pcm")) {} }
    }

    @Test
    fun aBookWithMoreAudioThanTheHeapStillRenders() = runBlocking {
        val sentence = ByteArray(4 shl 20)
        val loud = object : Synthesizer {
            override suspend fun speak(text: String, wav: File) = Wav.write(wav, 22_050, sentence)
            override fun close() = Unit
        }
        val count = (Runtime.getRuntime().maxMemory() * 3 / 2 / sentence.size).toInt()
        val pcm = tmp.newFile("a.pcm")
        val (segments, _) = Speech.render(List(3) { loud }, List(count) { "s$it" }, tmp.newFile("s.wav"), pcm) {}
        assertEquals(count, segments.size)
        assertTrue(pcm.length() > Runtime.getRuntime().maxMemory())
    }

    @Test
    fun parallelEnginesStillWriteSentencesInOrderAndReportChunks() = runBlocking {
        val chunks = ArrayList<Int>()
        val sentences = List(60) { "s$it" }
        val queue = Channel<String>(Channel.UNLIMITED).apply { sentences.forEach { trySend(it) }; close() }
        val (segments, rate) = Speech.render(
            List(3) { Stalls("none") }, queue, tmp.newFile("s.wav"), tmp.newFile("a.pcm"),
            onChunk = { done, _ -> chunks += done.size },
            flushMs = 1_000_000, // No idle flush: a slow runner would add a chunk of its own.
        ) {}
        assertEquals(sentences, segments.map { it.text })
        assertEquals(segments.sortedBy { it.start }, segments)
        assertEquals(listOf(25, 50), chunks)
        assertEquals(22_050, rate)
    }

    @Test
    fun audioIsReportedWhileMoreSentencesAreStillToCome() = runBlocking {
        val queue = Channel<String>(Channel.UNLIMITED)
        val reported = CompletableDeferred<Int>()
        val render = async {
            Speech.render(
                listOf(Stalls("none")), queue, tmp.newFile("s.wav"), tmp.newFile("a.pcm"),
                onChunk = { done, _ -> reported.complete(done.size) }, flushMs = 50,
            ) {}
        }
        queue.send("one")
        queue.send("two")
        // Only two sentences, far short of a chunk, and the channel is still open: they are reported anyway.
        assertEquals(2, withTimeout(10_000) { reported.await() })
        queue.send("three")
        queue.close()
        assertEquals(listOf("one", "two", "three"), render.await().first.map { it.text })
    }

    @Test
    fun aStoppedBookCarriesOnFromItsKeptSpeechWithoutSpeakingItAgain() = runBlocking {
        val pcm = tmp.newFile("a.pcm")
        val (first, rate) = Speech.render(listOf(Stalls("none")), listOf("one", "two", "three"), tmp.newFile("s.wav"), pcm) {}
        // The audio ran on past the saved transcript when the book was stopped: that extra is cut, not kept.
        pcm.appendBytes(ByteArray(777))
        val kept = Speech.kept(first.take(2), rate, pcm.length())
        java.io.RandomAccessFile(pcm, "rw").use { it.setLength(kept.bytes) }

        val spoken = ArrayList<String>()
        val counting = object : Synthesizer {
            override suspend fun speak(text: String, wav: File) { spoken += text; Wav.write(wav, 22_050, ByteArray(44_100)) }
            override fun close() = Unit
        }
        val more = Channel<String>(Channel.UNLIMITED).apply { trySend("three"); trySend("four"); close() }
        val (all, _) = Speech.render(listOf(counting), more, tmp.newFile("s2.wav"), pcm, resume = kept) {}

        assertEquals("only the new sentences are spoken", listOf("three", "four"), spoken)
        assertEquals(listOf("one", "two", "three", "four"), all.map { it.text })
        assertEquals("the kept segments are unchanged", first.take(2), all.take(2))
        assertEquals("the next one starts where the kept audio ends", first[1].end, all[2].start)
        assertEquals("the audio holds exactly what the segments describe", all.last().end, pcm.length() * 500 / rate)
    }

    @Test
    fun keptSpeechIsOnlyWhatIsWholeInTheAudioOnDisk() {
        val segments = listOf(Segment(0, 1_000, "a"), Segment(1_000, 2_000, "b"), Segment(2_000, 3_000, "c"))
        val rate = 22_050
        val perSecond = rate * 2L
        assertEquals(listOf("a", "b", "c"), Speech.kept(segments, rate, 3 * perSecond + 99).segments.map { it.text })
        assertEquals("audio cut short drops the segment it cuts", listOf("a", "b"), Speech.kept(segments, rate, 3 * perSecond - 2).segments.map { it.text })
        assertEquals(2 * perSecond, Speech.kept(segments, rate, 3 * perSecond - 2).bytes)
        assertTrue("no audio keeps nothing", Speech.kept(segments, rate, 0).segments.isEmpty())
        assertTrue("an unknown rate keeps nothing", Speech.kept(segments, 0, 1_000_000).segments.isEmpty())
    }
}
