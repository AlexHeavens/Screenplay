/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Decides when listening should stop for the night: after a number of minutes of playing, or when playback reaches a position (the end of a chapter).
 * It counts only the time the book is playing, so pausing to answer the door does not eat the timer.
 * [tick] is called regularly by the playback service, which is why the timer survives leaving the book screen; [now] is injectable for tests.
 */
class SleepTimer(private val now: () -> Long = SystemClock::elapsedRealtime) {
    /** How long a "minute" lasts. Only a test sets it, so a real-UI test can run a 5 minute timer in seconds. */
    var minuteMs = 60_000L

    private var left by mutableStateOf<Long?>(null)
    private var endAt by mutableStateOf<Long?>(null)
    private var last = 0L

    val running: Boolean get() = left != null || endAt != null

    /** What the controls show while a timer runs, or null when none does. */
    val label: String?
        get() = left?.let { "Sleep ${clock((it + 999) / 1000 * 1000)}" } ?: endAt?.let { "Sleep at chapter end" }

    fun startMinutes(minutes: Int) { cancel(); last = now(); left = minutes * minuteMs }

    fun startAtPosition(ms: Long) { cancel(); endAt = ms }

    fun cancel() { left = null; endAt = null }

    /** Advances the countdown by the time since the last call, if [playing]. Returns true once, when the timer has just run out and is cleared. */
    fun tick(playing: Boolean, position: Long): Boolean {
        val t = now()
        val elapsed = t - last
        last = t
        if (!playing) return false
        left?.let {
            val rest = it - elapsed
            if (rest <= 0) { left = null; return true }
            left = rest
        }
        endAt?.let { if (position >= it) { endAt = null; return true } }
        return false
    }
}
