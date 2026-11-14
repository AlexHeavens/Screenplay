/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

/** How far through a book the listener is: stored per book in `meta.json` (so a backup carries it) and shown as a percentage. */
object Played {
    /**
     * Share of the book heard, 0 to 100, or null when there is no saved place. A finished book reads 100.
     * While a book is still being made its length is unknown: [durationMs] is the audio so far, and the whole is estimated
     * as that divided by the share of the book already spoken ([speechProgress]).
     */
    fun percent(positionMs: Long?, finished: Boolean, durationMs: Long, ready: Boolean, speechProgress: Float): Int? {
        if (finished) return 100
        if (positionMs == null) return null
        val total = if (ready) durationMs.toDouble() else if (speechProgress > 0f) durationMs / speechProgress.toDouble() else 0.0
        if (total <= 0.0) return 0
        return (positionMs * 100 / total).toInt().coerceIn(0, 100)
    }

    /** The start of the paragraph that holds [positionMs]: where the book screen selects and playback resumes. */
    fun resumeAt(segments: List<Segment>, positionMs: Long?): Long =
        if (positionMs == null || segments.isEmpty()) 0L else segments[Timeline.indexAt(segments, positionMs)].start
}

/**
 * Writes the listening place once per paragraph, as it starts to play or is moved to while paused, and "finished" when the end is reached.
 * [write] gets the book id, the position and whether the book is finished; it runs wherever [tick] does, so call it off the main thread.
 */
class PositionSaver(
    private val loadStarts: (String) -> List<Long>,
    private val write: (String, Long, Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var id: String? = null
    private var starts = emptyList<Long>()
    private var loadedAt = 0L
    private var index = -1
    private var wasPlaying = false

    @Synchronized
    fun tick(id: String, playing: Boolean, position: Long, duration: Long, whole: Boolean) {
        if (id != this.id) { this.id = id; starts = emptyList(); index = -1; wasPlaying = false }
        if (playing) {
            // A book being made grows, so the paragraph starts are read again once playback passes the last one known.
            if (starts.isEmpty() || (position >= starts.last() && clock() - loadedAt > RELOAD_MS)) { starts = loadStarts(id); loadedAt = clock() }
            if (starts.isNotEmpty()) {
                val i = starts.indexOfLast { it <= position }.coerceAtLeast(0)
                if (i != index) { index = i; write(id, starts[i], false) }
            }
        } else if (wasPlaying && whole && duration > 0 && position >= duration - END_MS) {
            index = -1 // playing the last paragraph again must save its place again
            write(id, duration, true)
        } else if (!playing) {
            // Moved while paused (a skip, a seek from the notification): that is the place to come back to. The first look at a book only notes where it is.
            if (starts.isEmpty() && clock() - loadedAt > RELOAD_MS) { starts = loadStarts(id); loadedAt = clock() }
            if (starts.isNotEmpty()) {
                val i = starts.indexOfLast { it <= position }.coerceAtLeast(0)
                if (index != -1 && i != index) write(id, starts[i], false)
                index = i
            }
        }
        wasPlaying = playing
    }

    private companion object {
        const val RELOAD_MS = 2000L
        const val END_MS = 500L
    }
}
