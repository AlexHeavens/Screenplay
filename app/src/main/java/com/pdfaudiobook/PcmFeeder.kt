/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import java.io.File
import java.io.RandomAccessFile

/** Where a [PcmFeeder] sends audio. [LivePlayer] backs it with an AudioTrack. */
interface PcmSink {
    /** Blocks until written; returns the byte count, or a negative value when the sink is no longer usable. */
    fun write(buf: ByteArray, off: Int, len: Int): Int
    /** Must unblock a write() that is in progress. */
    fun stop()
    fun release()
}

/**
 * Copies a growing PCM file into a [PcmSink] on its own thread. [close] shuts down in a fixed order:
 * flag, interrupt, stop the sink (which frees a blocked write), wait for the thread, and only then release
 * the sink, so the thread never writes to a released AudioTrack. No exception leaves the thread.
 */
class PcmFeeder(private val file: File, startByte: Long, private val sink: PcmSink) {
    @Volatile private var stopped = false
    private val thread = Thread {
        try {
            val buf = ByteArray(16 * 1024)
            RandomAccessFile(file, "r").use { f ->
                f.seek(startByte)
                while (!stopped) {
                    val n = minOf(buf.size.toLong(), (f.length() - f.filePointer) and 1L.inv()).toInt()
                    if (n <= 0) {
                        // Waiting for more audio. close() interrupts this sleep to stop the thread, which is not an error.
                        Thread.sleep(100)
                        continue
                    }
                    f.readFully(buf, 0, n)
                    if (sink.write(buf, 0, n) < 0) break
                }
            }
        } catch (_: InterruptedException) {
            // close() asked the thread to stop.
        } catch (_: Exception) {
            // A failed write or read ends playback of this track; the player opens a fresh one on the next action.
        }
    }.apply { isDaemon = true; start() }

    fun close() {
        stopped = true
        thread.interrupt()
        try { sink.stop() } catch (_: Exception) {}
        // Bounded, so a stuck driver call cannot hang the UI thread. release() then follows regardless; the
        // thread swallows any error from a late write.
        try { thread.join(JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { sink.release() } catch (_: Exception) {}
    }

    private companion object { const val JOIN_MS = 1000L }
}
