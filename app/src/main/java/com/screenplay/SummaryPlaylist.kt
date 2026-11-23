/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * A book's spoken chapter summaries, in chapter order, played as one track. It is a [Playback] like the book's own player, so the
 * notification, headset buttons, mini player and sleep timer drive it the same way. Position and duration run over all the clips together.
 */
class SummaryPlaylist(private val ctx: Context, private val id: String, chapters: List<Int>) : Playback {
    private class Clip(val chapter: Int, val file: File, val start: Long, val length: Long)

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val lengths = HashMap<Int, Long>()
    private var clips = emptyList<Clip>()
    private var at = 0
    private var media: MediaPlayer? = null
    private var level = 1f

    override var position by mutableStateOf(0L)
        private set
    override var playing by mutableStateOf(false)
        private set
    override var speed by mutableStateOf(Player.savedSpeed(prefs))
        private set
    override var duration by mutableStateOf(0L)
        private set

    init { update(chapters) }

    /** Takes the chapters that now have a clip, as they are made; the clip being played carries on. */
    fun update(chapters: List<Int>) {
        val sorted = chapters.sorted()
        if (sorted == clips.map { it.chapter }) return
        val playingChapter = clips.getOrNull(at)?.chapter
        var start = 0L
        clips = sorted.map { chapter ->
            val file = File(Library.dir(ctx, id), Files.summaryAudio(chapter))
            val length = lengths.getOrPut(chapter) { lengthOf(file) }
            Clip(chapter, file, start, length).also { start += length }
        }
        duration = start
        val kept = clips.indexOfFirst { it.chapter == playingChapter }
        if (kept >= 0) at = kept else if (clips.isNotEmpty()) load(0)
    }

    private fun lengthOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } finally { retriever.release() }
    }

    /** Loads clip [n], paused at its start. */
    private fun load(n: Int) {
        media?.release()
        at = n
        media = MediaPlayer().apply {
            setDataSource(clips[n].file.path)
            prepare()
            setVolume(level, level)
            setOnCompletionListener { next() }
        }
    }

    private fun next() {
        if (at + 1 < clips.size) { load(at + 1); media?.start(); applySpeed() }
        else { playing = false; position = duration }
    }

    // Setting playback params on a paused player can start it on some devices, so only touch a playing one.
    private fun applySpeed() = media?.let { if (it.isPlaying) it.playbackParams = it.playbackParams.setSpeed(speed) }

    override fun toggle() {
        val m = media ?: return
        if (m.isPlaying) m.pause() else {
            if (position >= duration && clips.isNotEmpty()) { load(0); position = 0 }
            media?.start(); applySpeed()
        }
        playing = media?.isPlaying == true
    }

    override fun cycleSpeed() {
        speed = Player.SPEEDS[(Player.SPEEDS.indexOf(speed) + 1) % Player.SPEEDS.size]
        Player.saveSpeed(prefs, speed)
        applySpeed()
    }

    override fun seek(ms: Long) {
        if (clips.isEmpty()) return
        val target = ms.coerceIn(0L, duration)
        val n = clips.indexOfLast { it.start <= target }.coerceAtLeast(0)
        val wasPlaying = playing
        if (n != at) load(n)
        media?.seekTo((target - clips[n].start).toInt())
        if (wasPlaying) { media?.start(); applySpeed() }
        position = target
    }

    override fun volume(level: Float) { this.level = level; media?.setVolume(level, level) }

    override fun tick() {
        val m = media ?: return
        if (m.isPlaying) position = clips[at].start + m.currentPosition
    }

    override fun release() { media?.release(); media = null }

    /** The chapter whose summary is at [ms], or null with no clips. */
    fun chapterAt(ms: Long): Int? = clips.lastOrNull { it.start <= ms }?.chapter ?: clips.firstOrNull()?.chapter

    /** Where the summary of [chapter] starts, or null when it has no clip. */
    fun startOf(chapter: Int): Long? = clips.firstOrNull { it.chapter == chapter }?.start

    /** Where the clip playing at [ms] ends, for the sleep timer's end of chapter. */
    fun clipEndAt(ms: Long): Long? = clips.lastOrNull { it.start <= ms }?.let { it.start + it.length }
}
