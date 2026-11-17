/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A sleep timer chosen on the book screen keeps counting in the playback service, shows on the library's player,
 * and pauses the speech when it runs out, leaving the position where it was. Everything is real but the speech engine ([FakeSynthesizer]).
 * The menu's minutes are shortened to seconds through [SleepTimer.minuteMs], the one test hook, so "5 minutes" lasts 5 seconds.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options(spell = false, voice = Voice.PHONE).save(ctx)
        Engines.synthesizer = { FakeSynthesizer(msPerWord = 2500) }
        MediaControl.sleep.minuteMs = 1000
    }

    @After
    fun close() {
        scenario?.close()
        MediaControl.close()
        MediaControl.sleep.minuteMs = 60_000
    }

    private fun waitFor(what: String, ms: Long = 20_000, check: () -> Unit) {
        compose.waitUntil(ms) { runCatching(check).isSuccess }
    }

    @Smoke @Test
    fun theTimerCountsInTheServiceAndPausesTheSpeechWhenItRunsOut() {
        Fixtures.seedSpokenBook(ctx, msPerWord = 2500)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(120_000) { compose.onAllNodes(hasContentDescription("Listened")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sleep")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sleep").assertTextEquals("Sleep")
        compose.onNodeWithTag("play").performClick()
        compose.onNodeWithTag("play").assertTextEquals("Pause")

        // A timer shows its remaining time, a pick of Off clears it, and the library's player shows it too.
        compose.onNodeWithTag("sleep").performClick()
        compose.onNodeWithTag("sleep-60").performClick()
        waitFor("remaining time") { compose.onNodeWithTag("sleep").assertTextEquals("Sleep 0:59") }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("mini-sleep", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mini-open").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("sleep").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sleep").performClick()
        compose.onNodeWithTag("sleep-off").performClick()
        compose.onNodeWithTag("sleep").assertTextEquals("Sleep")

        // The book has no contents page, so there is no chapter to stop at.
        compose.onNodeWithTag("sleep").performClick()
        compose.onNodeWithTag("sleep-chapter").assertDoesNotExist()

        // A short timer runs out: the speech pauses where it was, and the control resets.
        compose.onNodeWithTag("sleep-5").performClick()
        compose.onNodeWithTag("play").assertTextEquals("Pause")
        waitFor("pause when the timer ran out") { compose.onNodeWithTag("play").assertTextEquals("Play") }
        compose.onNodeWithTag("sleep").assertTextEquals("Sleep")
        val player = MediaControl.current!!.player
        assertTrue("paused, not stopped: the position is kept", player.position > 0)
    }
}
