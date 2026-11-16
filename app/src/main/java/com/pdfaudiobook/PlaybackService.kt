/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The book being played, which the notification, the media session and the library's mini player control.
 * It belongs to the process, not to the book screen: leaving the screen while speech plays keeps the player, and so the sound, going.
 * [key] says which player a screen may take over: the same book, in the same form (finished file or live audio).
 */
object MediaControl {
    class Now(val player: Playback, val title: String, val author: String, val id: String, val key: Any)

    /** Compose state, so the library's mini player follows play, pause and close. */
    var current by mutableStateOf<Now?>(null)
        private set

    /** The sleep timer of the current listening session: cleared when the book is closed or replaced, and driven by the service so it runs with the screen off. */
    val sleep = SleepTimer()

    /** The player already running for this book, if any, for the book screen to pick up instead of starting over. */
    fun reuse(id: String, key: Any): Playback? = current?.takeIf { it.id == id && it.key == key }?.player

    fun start(ctx: Context, now: Now) {
        val old = current
        current = now
        if (old != null && old.player !== now.player) { sleep.cancel(); old.player.release() }
        try {
            ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
        } catch (e: RuntimeException) {
            // The system may refuse a foreground service (no permission, restricted background); playback itself is unaffected.
            android.util.Log.w("MediaControl", "no media notification", e)
        }
    }

    /** Asks for the notification permission (Android 13+) the first time only; a refusal leaves playback working, just without the shade entry. */
    fun askOnce(ctx: Context, ask: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (markAsked(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))) ask()
    }

    /** True the first time only, and records that the question was asked. */
    internal fun markAsked(prefs: SharedPreferences): Boolean {
        if (prefs.getBoolean("asked-notifications", false)) return false
        prefs.edit().putBoolean("asked-notifications", true).apply()
        return true
    }

    /** Moves the spoken position by [deltaMs], clamped to the audio there is; the transcript and progress bar follow the player. */
    fun skip(player: Playback, deltaMs: Long) = player.seek(skippedTo(player.position, deltaMs, player.duration))

    /** The book screen is gone: a player that is speaking carries on, one that is not is released. */
    fun leave(player: Playback) {
        if (player.playing) return
        player.release()
        if (current?.player === player) { current = null; sleep.cancel() }
    }

    /** Stops and forgets the book; the service sees it gone on its next tick and removes the notification. */
    fun close() {
        val player = current?.player
        current = null
        player?.release()
        sleep.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val saveScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
    private val saver = PositionSaver(
        { id -> Library.segmentStarts(appContext, id) },
        { id, ms, finished -> Library.savePosition(appContext, id, ms, finished) },
    )
    private lateinit var appContext: Context

    /** Called by the service's ticker: remembers the book's place as each paragraph starts, off the main thread. */
    fun keepPlace(ctx: Context, now: Now) {
        appContext = ctx.applicationContext
        val p = now.player
        val playing = p.playing; val position = p.position; val duration = p.duration
        saveScope.launch { runCatching { saver.tick(now.id, playing, position, duration, p is Player) } }
    }

    /** Called by the service's ticker: when the sleep timer runs out the speech fades and pauses, keeping the position. */
    fun sleepTick(player: Playback) {
        if (sleep.tick(player.playing, player.position)) fadeOutAndPause(player)
    }

    private const val FADE_STEPS = 10
    private const val FADE_STEP_MS = 150L

    private fun fadeOutAndPause(player: Playback) {
        val handler = Handler(Looper.getMainLooper())
        var step = 0
        handler.post(object : Runnable {
            override fun run() {
                if (current?.player !== player) return // closed meanwhile; the player is already released
                step++
                // Someone paused meanwhile, or the fade is complete: finish with the speech paused and the volume back up.
                if (step >= FADE_STEPS || !player.playing) {
                    if (player.playing) player.toggle()
                    player.volume(1f)
                } else {
                    player.volume(1f - step.toFloat() / FADE_STEPS)
                    handler.postDelayed(this, FADE_STEP_MS)
                }
            }
        })
    }

    /** A deleted book must stop sounding. */
    fun closeIf(id: String) {
        if (current?.id == id) close()
    }
}

/** How far the notification's back and forward buttons skip. */
const val SKIP_MS = 10_000L

/** Where a skip of [deltaMs] from [position] lands in audio of [duration] ms: never before the start or past the end. */
fun skippedTo(position: Long, deltaMs: Long, duration: Long): Long = (position + deltaMs).coerceIn(0L, maxOf(duration, 0L))

/**
 * Keeps playback alive with the screen off or the app in the background and shows it as a media notification (title, author, back 10 s, play/pause, forward 10 s, seek bar)
 * backed by a platform [MediaSession], so the lock screen, headsets and Bluetooth controls drive the same [Playback].
 * The duration is what has been spoken so far, so it grows while a book is still being imported.
 */
class PlaybackService : Service() {
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var shownPlayer: Playback? = null
    private var shownPlaying: Boolean? = null
    private var shownSpeed = 0f
    private var shownPosition = 0L
    private var shownAt = 0L
    private var shownDuration = -1L

    private val ticker = object : Runnable {
        override fun run() {
            val now = MediaControl.current
            if (now == null) { stopSelf(); return }
            now.player.tick()
            MediaControl.sleepTick(now.player)
            MediaControl.keepPlace(this@PlaybackService, now)
            publish(now)
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "Screenplay").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { MediaControl.current?.player?.let { if (!it.playing) it.toggle() } }
                override fun onPause() { MediaControl.current?.player?.let { if (it.playing) it.toggle() } }
                override fun onSeekTo(pos: Long) { MediaControl.current?.player?.seek(pos) }
                override fun onRewind() { MediaControl.current?.player?.let { MediaControl.skip(it, -SKIP_MS) } }
                override fun onFastForward() { MediaControl.current?.player?.let { MediaControl.skip(it, SKIP_MS) } }
                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        BACK -> onRewind()
                        FORWARD -> onFastForward()
                    }
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val now = MediaControl.current
        val player = now?.player
        when (intent?.action) {
            PLAY -> if (player != null && !player.playing) player.toggle()
            PAUSE -> if (player != null && player.playing) player.toggle()
            BACK -> if (player != null) MediaControl.skip(player, -SKIP_MS)
            FORWARD -> if (player != null) MediaControl.skip(player, SKIP_MS)
        }
        // A foreground service must call startForeground soon after it is started, even when it is about to stop.
        publish(now, force = true)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_NOT_STICKY
    }

    private fun publish(now: MediaControl.Now?, force: Boolean = false) {
        val player = now?.player
        val playing = player?.playing == true
        val position = player?.position ?: 0L
        val duration = player?.duration ?: 0L
        val speed = if (playing) player?.speed ?: 1f else 0f
        val clock = SystemClock.elapsedRealtime()
        // The notification extrapolates the position itself, so only a pause, a speed change or a jump (a seek) needs a new state.
        val expected = shownPosition + ((clock - shownAt) * shownSpeed).toLong()
        val changed = force || player !== shownPlayer || playing != shownPlaying || speed != shownSpeed ||
            kotlin.math.abs(position - expected) > 1500
        // The duration of a book being made grows all the time; re-posting for every tick would flood the shade.
        val grown = kotlin.math.abs(duration - shownDuration) >= 5000
        if (!changed && !grown) return
        if (now != null) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, now.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, now.author)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                    .build(),
            )
            session.setPlaybackState(
                PlaybackState.Builder()
                    // Newer system UIs (Samsung's, Android 13+) draw the media controls from this state, not from the notification's actions.
                    .addCustomAction(PlaybackState.CustomAction.Builder(BACK, "Back 10 seconds", R.drawable.ic_replay_10).build())
                    .addCustomAction(PlaybackState.CustomAction.Builder(FORWARD, "Forward 10 seconds", R.drawable.ic_forward_10).build())
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD)
                    .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, speed, clock)
                    .build(),
            )
        }
        shownPlayer = player; shownPlaying = playing; shownSpeed = speed; shownPosition = position; shownAt = clock; shownDuration = duration
        val n = notification(now, playing)
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
    }

    private fun notification(now: MediaControl.Now?, playing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val action = if (playing) PAUSE else PLAY
        fun button(res: Int, label: String, act: String, code: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, res), label,
            PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(now?.title ?: getString(R.string.app_name))
            .setContentText(now?.author?.ifBlank { null })
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(button(R.drawable.ic_replay_10, "Back 10 seconds", BACK, 1))
            .addAction(button(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pause" else "Play", action, 0))
            .addAction(button(R.drawable.ic_forward_10, "Forward 10 seconds", FORWARD, 2))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "playback"
        const val ID = 2
        const val PLAY = "com.pdfaudiobook.PLAY"
        const val PAUSE = "com.pdfaudiobook.PAUSE"
        const val BACK = "com.pdfaudiobook.BACK"
        const val FORWARD = "com.pdfaudiobook.FORWARD"
    }
}
