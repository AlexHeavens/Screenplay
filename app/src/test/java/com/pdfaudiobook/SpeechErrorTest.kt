/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What goes wrong when a speech engine hands back audio the import cannot use. */
class SpeechErrorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun messageOf(action: () -> Unit): String {
        try { action() } catch (e: Exception) { return e.message!! }
        fail("expected a failure")
        return ""
    }

    @Test
    fun aFileThatIsNotAWavIsRefused() {
        val file = tmp.newFile("speech.wav").also { it.writeText("this is plainly not a wav file") }
        assertEquals("Not a WAV file", messageOf { Wav.read(file) })
    }

    @Test
    fun aStereoWavIsRefusedBecauseSpeechMustBeMono() {
        val file = tmp.newFile("stereo.wav")
        Wav.write(file, 22_050, ByteArray(100))
        file.readBytes().also { it[22] = 2 }.let { file.writeBytes(it) } // The channel count sits at byte 22 of the header.
        assertEquals("Speech must be mono 16-bit PCM", messageOf { Wav.read(file) })
    }

    @Test
    fun aWavWithoutAnyAudioIsRefused() {
        val whole = tmp.newFile("whole.wav").also { Wav.write(it, 22_050, ByteArray(100)) }
        val headOnly = tmp.newFile("head.wav").also { it.writeBytes(whole.readBytes().copyOf(36)) } // RIFF header and fmt chunk, no data chunk.
        assertEquals("WAV has no data", messageOf { Wav.read(headOnly) })
    }

    @Test
    fun anEngineThatChangesItsSampleRateHalfWayFailsTheBookWithAMessage() {
        var calls = 0
        val changing = object : Synthesizer {
            override suspend fun speak(text: String, wav: File) = Wav.write(wav, if (calls++ == 0) 22_050 else 16_000, ByteArray(4_410))
            override fun close() = Unit
        }
        val message = messageOf { runBlocking { Speech.render(listOf(changing), listOf("one", "two"), tmp.newFile("s.wav"), tmp.newFile("a.pcm")) {} } }
        assertEquals("Speech engine changed sample rate mid-book", message)
    }
}
