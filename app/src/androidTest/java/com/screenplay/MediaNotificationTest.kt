/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Playing a book puts a media notification in the shade, and its buttons, its seek bar's session and the media keys
 * of a headset drive the real player. Everything is real but the speech engine ([FakeSynthesizer]).
 */
@RunWith(AndroidJUnit4::class)
class MediaNotificationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options(spell = false, voice = Voice.PHONE).save(ctx)
        // Slow words make the book longer than one 10 s skip, so the skips can be told from the clamps.
        Engines.synthesizer = { FakeSynthesizer(msPerWord = 2500) }
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun mediaNotification(): Notification? =
        nm.activeNotifications.firstOrNull { it.packageName == ctx.packageName && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }?.notification

    private fun waitForNotification(what: String, check: (Notification) -> Boolean): Notification {
        var last: Notification? = null
        compose.waitUntil(15_000) { last = mediaNotification(); last?.let(check) == true }
        return last ?: error(what)
    }

    private fun buttonTitle(n: Notification) = n.actions.getOrNull(1)?.title?.toString()

    private fun controller(n: Notification): MediaController {
        @Suppress("DEPRECATION")
        val token = n.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        return MediaController(ctx, token!!)
    }

    private fun playLabel() = compose.onNodeWithTag("play")

    @Smoke @Test
    fun theNotificationShowsTheBookAndItsButtonsAndSeekAndHeadsetKeysDriveThePlayer() {
        Fixtures.seedSpokenBook(ctx, msPerWord = 2500)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(120_000) { compose.onAllNodes(hasContentDescription("Listened")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-0")).fetchSemanticsNodes().isNotEmpty() }
        playLabel().assertTextEquals("Play")

        // Playing shows the notification with the book's title and back, pause and forward buttons, all in the compact view.
        playLabel().performClick()
        playLabel().assertTextEquals("Pause")
        var n = waitForNotification("notification") { buttonTitle(it) == "Pause" }
        assertEquals(Fixtures.TITLE, n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Back 10 seconds", "Pause", "Forward 10 seconds"), n.actions.map { it.title.toString() })
        assertEquals(listOf(0, 1, 2), n.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS)!!.toList())

        // Its pause and play buttons act on the player.
        n.actions[1].actionIntent.send()
        compose.waitUntil(10_000) { runCatching { playLabel().assertTextEquals("Play") }.isSuccess }
        n = waitForNotification("play button") { buttonTitle(it) == "Play" }
        n.actions[1].actionIntent.send()
        compose.waitUntil(10_000) { runCatching { playLabel().assertTextEquals("Pause") }.isSuccess }
        n = waitForNotification("pause button") { buttonTitle(it) == "Pause" }

        // The session behind the seek bar moves the player, and the headset keys pause and resume it.
        val session = controller(n)
        assertTrue("the session offers seeking", session.playbackState!!.actions and android.media.session.PlaybackState.ACTION_SEEK_TO != 0L)
        assertTrue("the duration is the audio so far", session.metadata!!.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) > 5000)
        session.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        session.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        compose.waitUntil(10_000) { runCatching { playLabel().assertTextEquals("Play") }.isSuccess }
        session.transportControls.seekTo(6000)
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("clock").assertTextEquals("0:06 / ${clockOf(session)}") }.isSuccess }

        // Skipping moves the position by 10 s and stops at both ends; the buttons and the session's transport controls both do it.
        val state = session.playbackState!!
        assertTrue("rewind and fast-forward are offered", state.actions and (PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD) == (PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD))
        assertEquals(listOf(PlaybackService.BACK, PlaybackService.FORWARD), state.customActions.map { it.action })
        val player = MediaControl.current!!.player
        val end = player.duration
        assertEquals("the skip step", 10_000L, SKIP_MS)
        assertTrue("the fixture is longer than one skip", end > 10_000L)
        session.transportControls.rewind()
        compose.waitUntil(10_000) { player.position == 0L }
        session.transportControls.fastForward()
        compose.waitUntil(10_000) { player.position == minOf(10_000L, end) }
        session.transportControls.seekTo(end - 3000)
        compose.waitUntil(10_000) { player.position == end - 3000 }
        session.transportControls.fastForward()
        compose.waitUntil(10_000) { player.position == end }
        session.transportControls.seekTo(10_000L + 2000)
        compose.waitUntil(10_000) { player.position == 10_000L + 2000 }
        n = waitForNotification("skip buttons") { it.actions.size == 3 }
        n.actions[0].actionIntent.send()
        compose.waitUntil(10_000) { player.position == 2000L }
        n.actions[2].actionIntent.send()
        compose.waitUntil(10_000) { player.position == 2000L + 10_000L }
        session.transportControls.sendCustomAction(PlaybackService.BACK, null)
        compose.waitUntil(10_000) { player.position == 2000L }
        session.transportControls.sendCustomAction(PlaybackService.FORWARD, null)
        compose.waitUntil(10_000) { player.position == 2000L + 10_000L }
        session.transportControls.seekTo(6000)
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("clock").assertTextEquals("0:06 / ${clockOf(session)}") }.isSuccess }

        session.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        session.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        compose.waitUntil(10_000) { runCatching { playLabel().assertTextEquals("Pause") }.isSuccess }

        // Leaving the book while it speaks keeps the notification: speech belongs to the app, not to the book screen.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("mini-player").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mini-title", useUnmergedTree = true).assertTextEquals(Fixtures.TITLE)
        compose.onNodeWithTag("mini-play").assertTextEquals("Pause")
        waitForNotification("pause button after leaving") { buttonTitle(it) == "Pause" }

        // The library's player pauses, resumes and closes it; closing takes the notification away.
        compose.onNodeWithTag("mini-play").performClick()
        compose.onNodeWithTag("mini-play").assertTextEquals("Play")
        waitForNotification("paused from the library") { buttonTitle(it) == "Play" }
        compose.onNodeWithTag("mini-play").performClick()
        waitForNotification("resumed from the library") { buttonTitle(it) == "Pause" }

        // Back in the book, the same player is still speaking.
        compose.onNodeWithTag("mini-open").performClick()
        compose.waitUntil(15_000) { runCatching { playLabel().assertTextEquals("Pause") }.isSuccess }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("mini-close").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mini-close").performClick()
        compose.waitUntil(15_000) { mediaNotification() == null }
        compose.onNodeWithTag("mini-player").assertDoesNotExist()
    }

    private fun clockOf(c: MediaController): String {
        val ms = c.metadata!!.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        return "%d:%02d".format(ms / 60000, ms / 1000 % 60)
    }
}
