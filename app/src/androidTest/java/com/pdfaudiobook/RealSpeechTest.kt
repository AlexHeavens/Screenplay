/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Skipped unless the device has a speech engine; the emulator image does not. */
@RunWith(AndroidJUnit4::class)
class RealSpeechTest {
    @Test
    fun androidEngineWritesAWav() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val synth = AndroidSynthesizer(ctx)
        val wav = File(ctx.cacheDir, "real.wav")
        try {
            synth.speak("Hello there.", wav)
        } catch (e: NoSpeechEngine) {
            assumeTrue("no speech engine: ${e.message}", false)
        } finally {
            synth.close()
        }
        assertTrue(Wav.read(wav).dataLength > 1000)
    }
}
