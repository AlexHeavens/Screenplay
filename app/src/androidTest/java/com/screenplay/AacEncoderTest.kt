/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/** A book is hours of audio; encoding must run far faster than real time or generation takes all night. */
@RunWith(AndroidJUnit4::class)
class AacEncoderTest {
    @Test
    fun encodesAtLeastTwentyTimesFasterThanRealTime() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val rate = 22_050
        val seconds = 600
        val pcm = File(dir, "speed.pcm")
        pcm.outputStream().buffered().use { out ->
            repeat(rate * seconds) { i ->
                val s = (sin(i * 0.05) * 8000).toInt()
                out.write(s and 0xff)
                out.write(s shr 8 and 0xff)
            }
        }
        val m4a = File(dir, "speed.m4a")
        val start = System.nanoTime()
        val progress = ArrayList<Float>()
        AacEncoder.encode(pcm, rate, m4a) { progress += it }
        val took = (System.nanoTime() - start) / 1e9
        assertTrue("progress $progress", progress.isNotEmpty() && progress.last() == 1f && progress == progress.sorted())
        assertTrue("encoded $seconds s of audio in $took s", seconds / took >= 20)
    }
}
