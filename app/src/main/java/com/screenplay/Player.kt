/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** What the reader screen needs from a player, whether the audio is finished or still being made. */
interface Playback {
    val position: Long
    val playing: Boolean
    val speed: Float
    val duration: Long
    fun toggle()
    fun cycleSpeed()
    fun seek(ms: Long)
    /** Output loudness from 0 to 1, for fading out; a pause or a new track restores nothing, so callers put it back to 1. */
    fun volume(level: Float)
    fun tick()
    fun release()
}

/** Wraps MediaPlayer and exposes position and play state to Compose. */
class Player(ctx: Context, id: String) : Playback {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val media = MediaPlayer().apply {
        setDataSource(File(Library.dir(ctx, id), Files.AUDIO).path)
        prepare()
    }
    override var position by mutableStateOf(0L)
        private set
    override var playing by mutableStateOf(false)
        private set
    /** Playback speed, kept across books and launches. */
    override var speed by mutableStateOf(savedSpeed(prefs))
        private set
    override val duration: Long get() = media.duration.toLong()

    init {
        media.setOnCompletionListener { playing = false; position = duration }
    }

    override fun toggle() {
        if (media.isPlaying) media.pause() else { media.start(); applySpeed() }
        playing = media.isPlaying
    }

    /** Steps to the next entry of [SPEEDS], wrapping round. */
    override fun cycleSpeed() {
        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
        saveSpeed(prefs, speed)
        applySpeed()
    }

    // Setting playback params on a paused player can start it on some devices, so only touch a playing one.
    private fun applySpeed() {
        if (media.isPlaying) media.playbackParams = media.playbackParams.setSpeed(speed)
    }

    override fun seek(ms: Long) {
        media.seekTo(ms.toInt())
        position = ms
    }

    override fun volume(level: Float) = media.setVolume(level, level)

    override fun tick() {
        if (media.isPlaying) position = media.currentPosition.toLong()
    }

    override fun release() = media.release()

    companion object {
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

        /** The saved playback speed, 1 until one is saved; shared by [Player] and [LivePlayer]. */
        fun savedSpeed(prefs: SharedPreferences) = prefs.getFloat("speed", 1f)

        fun saveSpeed(prefs: SharedPreferences, speed: Float) { prefs.edit().putFloat("speed", speed).apply() }
    }
}
