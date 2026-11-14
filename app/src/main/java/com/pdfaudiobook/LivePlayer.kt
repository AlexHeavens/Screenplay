/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile

/**
 * Plays the raw PCM of a book still being made. The file only grows, so playback follows the end of it and
 * waits there if it catches up; a seek or speed change opens a fresh track at the new offset.
 */
class LivePlayer(ctx: Context, id: String, private val rate: Int, startMs: Long = 0) : Playback {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val file = File(Library.dir(ctx, id), "audio.pcm")
    private var track: AudioTrack? = null
    private var feeder: Thread? = null
    private var baseMs = startMs

    override var position by mutableStateOf(startMs)
        private set
    override var playing by mutableStateOf(false)
        private set
    override var speed by mutableStateOf(prefs.getFloat("speed", 1f))
        private set
    /** Grows as the book is spoken. */
    override var duration by mutableStateOf(available())
        private set

    private fun available() = file.length() / 2 * 1000 / rate

    private fun open(atMs: Long, play: Boolean) {
        close()
        baseMs = atMs
        val size = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        position = atMs
        if (play) { t.play(); t.playbackParams = PlaybackParams().setSpeed(speed) }
        playing = play
        feeder = Thread {
            val buf = ByteArray(16 * 1024)
            RandomAccessFile(file, "r").use { f ->
                f.seek(atMs * rate / 1000 * 2)
                while (!Thread.currentThread().isInterrupted) {
                    val n = minOf(buf.size.toLong(), (f.length() - f.filePointer) and 1L.inv()).toInt()
                    if (n <= 0) {
                        // Waiting for more audio. close() interrupts this sleep to stop the thread, which is not an error.
                        try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                        continue
                    }
                    f.readFully(buf, 0, n)
                    if (t.write(buf, 0, n) < 0) break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun close() {
        feeder?.interrupt()
        feeder = null
        track?.release()
        track = null
    }

    override fun toggle() {
        if (playing) {
            // Pausing the AudioTrack itself leaves the feeder blocked in write(), and after a resume the track
            // plays its buffer and stalls. Closing it and opening a fresh one at the same point avoids that.
            tick()
            close()
            playing = false
        } else open(position, true)
    }

    override fun cycleSpeed() {
        speed = Player.SPEEDS[(Player.SPEEDS.indexOf(speed) + 1) % Player.SPEEDS.size]
        prefs.edit().putFloat("speed", speed).apply()
        // A new track also resets the head position, which is the only clock the position is read from.
        if (track != null) open(position, playing)
    }

    override fun seek(ms: Long) {
        val to = ms.coerceIn(0, available())
        if (track != null) open(to, playing) else position = to
    }

    override fun volume(level: Float) { track?.setVolume(level) }

    override fun tick() {
        duration = available()
        val t = track
        if (t != null && playing) position = baseMs + t.playbackHeadPosition * 1000L / rate
    }

    override fun release() = close()
}
