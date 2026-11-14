/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speeds on offer, and a sleep timer that is replaced by one of the other kind. */
class PlaybackLimitsTest {
    @Test
    fun theSpeedsAreFiveAndTheSlowestIsThreeQuartersAndTheFastestTwice() {
        assertEquals(listOf(1f, 1.25f, 1.5f, 2f, 0.75f), Player.SPEEDS)
    }

    @Test
    fun aChapterEndTimerReplacesAMinuteTimerSoTheCountdownNeverFires() {
        var time = 0L
        val timer = SleepTimer { time }
        timer.startMinutes(5)
        timer.startAtPosition(900_000)
        assertEquals("Sleep at chapter end", timer.label)
        time += 10 * 60_000
        assertFalse(timer.tick(playing = true, position = 1_000))
        assertTrue(timer.running)
    }

    @Test
    fun aMinuteTimerReplacesAChapterEndTimerSoReachingTheChapterDoesNotFire() {
        var time = 0L
        val timer = SleepTimer { time }
        timer.startAtPosition(1_000)
        timer.startMinutes(30)
        time += 1_000
        assertFalse(timer.tick(playing = true, position = 5_000))
        assertEquals("Sleep 29:59", timer.label)
    }
}
