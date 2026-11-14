/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real neural voice. Skipped unless its model is on the device or the run passes `-e neuralDownload true`
 * (which downloads the model), so CI and the quick runs never fetch it.
 */
@RunWith(AndroidJUnit4::class)
class RealNeuralVoiceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val paragraph = InstrumentationRegistry.getInstrumentation().context.assets.open("ocr/origin.txt").bufferedReader().readText()
        .lines().first { it.length > 300 }.replace(Regex("\\s+"), " ").trim()

    /** Which neural voice to exercise: `-e neuralVoice PIPER`, else Kokoro. */
    private val voice = Voice.valueOf(InstrumentationRegistry.getArguments().getString("neuralVoice") ?: "KOKORO")

    private fun lane(): Synthesizer {
        val download = InstrumentationRegistry.getArguments().getString("neuralDownload") == "true"
        assumeTrue("natural voice not downloaded", download || NeuralVoice.installed(ctx, voice))
        runBlocking { NeuralVoice.ensure(ctx, voice) }
        return NeuralVoice.Lane(ctx, voice)
    }

    @Test
    fun neuralVoiceWritesAWavThatTheImportCanRead(): Unit = runBlocking {
        val synth = lane()
        val wav = File(ctx.cacheDir, "neural.wav")
        try {
            val start = System.nanoTime()
            synth.speak(paragraph, wav)
            val info = Wav.read(wav)
            val seconds = info.dataLength / 2.0 / info.rate
            val took = (System.nanoTime() - start) / 1e9
            Log.i("NeuralVoice", "rate=${info.rate} audio=%.1fs took=%.1fs words=${paragraph.split(' ').size}".format(seconds, took))
            assertTrue("expected speech, got ${seconds}s", seconds > 5)
            wav.copyTo(File(ctx.getExternalFilesDir(null), "neural-sample.wav"), overwrite = true)
            // The first call includes loading the model; time warm calls over a minute of speech.
            var audio = 0.0
            val warm = System.nanoTime()
            while (audio < 60) { synth.speak(paragraph, wav); Wav.read(wav).let { audio += it.dataLength / 2.0 / it.rate } }
            Log.i("NeuralVoice", "warm: %.1fs of audio in %.1fs".format(audio, (System.nanoTime() - warm) / 1e9))
        } finally {
            synth.close()
        }
    }

    @Test
    fun twoDownloadsAtOnceLeaveOneWorkingVoice(): Unit = runBlocking {
        // Only with `-e neuralDownload true`: it removes the downloaded model and fetches it again.
        assumeTrue("download not requested", InstrumentationRegistry.getArguments().getString("neuralDownload") == "true")
        File(ctx.filesDir, "voices").deleteRecursively()
        val first = async(Dispatchers.IO) { NeuralVoice.ensure(ctx, voice) }
        val second = async(Dispatchers.IO) { NeuralVoice.ensure(ctx, voice) }
        first.await(); second.await()
        assertTrue(NeuralVoice.installed(ctx, voice))
        val synth = NeuralVoice.Lane(ctx, voice)
        try {
            val wav = File(ctx.cacheDir, "twice.wav")
            synth.speak("Hello there.", wav)
            assertTrue(Wav.read(wav).dataLength > 1000)
        } finally {
            synth.close()
        }
    }

    @Test
    fun lanesSpeakAtTheSameTimeWithoutWaitingForEachOther(): Unit = runBlocking {
        lane().close()
        val lanes = List(voice.lanes) { NeuralVoice.Lane(ctx, voice) }
        val files = List(lanes.size) { File(ctx.cacheDir, "lane$it.wav") }
        try {
            val start = System.nanoTime()
            lanes.indices.map { i -> async(Dispatchers.Default) { lanes[i].speak(paragraph, files[i]) } }.forEach { it.await() }
            Log.i("NeuralVoice", "%d lanes, one paragraph each: %.1fs".format(lanes.size, (System.nanoTime() - start) / 1e9))
            for (f in files) assertTrue(Wav.read(f).dataLength > 100_000)
        } finally {
            lanes.forEach { it.close() }
        }
    }
}
