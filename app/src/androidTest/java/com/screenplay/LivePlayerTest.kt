/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A book still being spoken can be played, and playback survives being stopped while it waits for more audio. */
@RunWith(AndroidJUnit4::class)
class LivePlayerTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = 16000

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
    }

    @Test
    fun stoppingPlaybackThatHasCaughtUpWithTheAudioDoesNotCrash() {
        val dir = Library.dir(ctx, "live").apply { mkdirs() }
        File(dir, "audio.pcm").writeBytes(ByteArray(rate * 2 / 5)) // 200 ms: the feeder drains it and then waits for more
        val player = LivePlayer(ctx, "live", rate)
        player.toggle()
        Thread.sleep(1000)
        assertTrue(player.playing)
        player.seek(0) // reopens the track, which interrupts the waiting feeder
        Thread.sleep(500)
        player.release()
        Thread.sleep(500) // an uncaught exception in the feeder thread would have killed the process by now
    }

    @Test
    fun resumingAfterAPauseKeepsAdvancingFromWhereItStopped() {
        val dir = Library.dir(ctx, "live").apply { mkdirs() }
        File(dir, "audio.pcm").writeBytes(ByteArray(rate * 2 * 30)) // 30 s of silence
        val player = LivePlayer(ctx, "live", rate)
        player.toggle()
        Thread.sleep(1500)
        player.tick()
        player.toggle() // pause
        player.tick()
        val pausedAt = player.position
        Thread.sleep(1000)
        player.tick()
        assertEquals("a paused player must not move", pausedAt, player.position)
        player.toggle() // resume
        val seen = ArrayList<Long>()
        repeat(20) { Thread.sleep(100); player.tick(); seen += player.position }
        player.release()
        println("pausedAt=$pausedAt seen=$seen")
        assertTrue("position jumped away after resume: $pausedAt -> $seen", seen.all { it in pausedAt - 50..pausedAt + 3000 })
        assertTrue("position stopped advancing after resume: $seen", seen.last() - pausedAt >= 1000)
    }
}
