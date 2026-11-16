/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private var time = 0L
    private val timer = SleepTimer { time }

    private fun advance(ms: Long, playing: Boolean = true, position: Long = 0): Boolean { time += ms; return timer.tick(playing, position) }

    @Test fun countsDownWhilePlayingAndFiresOnceAtZero() {
        timer.startMinutes(5)
        assertEquals("Sleep 5:00", timer.label)
        assertFalse(advance(2 * 60_000))
        assertEquals("Sleep 3:00", timer.label)
        assertFalse(advance(3 * 60_000 - 1))
        assertTrue(advance(1))
        assertFalse(timer.running)
        assertNull(timer.label)
        assertFalse("fires only once", advance(60_000))
    }

    @Test fun pausedTimeDoesNotCount() {
        timer.startMinutes(5)
        advance(60_000)
        assertFalse(advance(10 * 60_000, playing = false))
        assertEquals("Sleep 4:00", timer.label)
        assertTrue(advance(4 * 60_000))
    }

    @Test fun offCancelsAndANewTimerReplacesTheOld() {
        timer.startMinutes(5)
        timer.cancel()
        assertFalse(advance(10 * 60_000))
        timer.startMinutes(5)
        advance(4 * 60_000)
        timer.startMinutes(10)
        assertEquals("Sleep 10:00", timer.label)
    }

    @Test fun chapterEndFiresWhenPlaybackReachesIt() {
        timer.startAtPosition(90_000)
        assertEquals("Sleep at chapter end", timer.label)
        assertFalse(advance(1000, position = 89_999))
        assertFalse("not while paused", advance(1000, playing = false, position = 90_000))
        assertTrue(advance(1000, position = 90_000))
        assertFalse(timer.running)
    }
}
