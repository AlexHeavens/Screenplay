/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The place in a book survives the system killing the app, not only the user leaving it. The instrumentation runs inside the app's
 * own process, so a test cannot kill the app and carry on: `scripts/ui-tests.sh` runs this class twice, `-e phase play` and `-e phase check`,
 * and runs `am force-stop` from the host between them, while the first run is still in the middle of playing.
 * Without a phase argument both tests are skipped, so a plain run of the class stays green.
 */
@RunWith(AndroidJUnit4::class)
class ForceStopTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val phase = InstrumentationRegistry.getArguments().getString("phase")
    private val expectedFile = File(ctx.filesDir, "force-stop-expected.json")
    private val readyFile = File(ctx.filesDir, "force-stop-ready")

    @Before
    fun speechIsFake() {
        Engines.synthesizer = { FakeSynthesizer(msPerWord = 600) }
    }

    private fun selected() = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    @Smoke @Test
    fun playUntilKilled() {
        assumeTrue(phase == "play")
        File(ctx.filesDir, "books").deleteRecursively()
        expectedFile.delete(); readyFile.delete()
        Library.refresh(ctx)
        Options(spell = false).save(ctx)
        ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(Fixtures.pdf(ctx), "application/pdf"),
        )
        compose.waitUntil(120_000) { compose.onAllNodes(hasContentDescription("Listened")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("sentence-2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sentence-1").performClick()
        compose.waitUntil(30_000) { Library.books.value.firstOrNull()?.positionMs.let { it != null && it > 0 } }
        // Pause, then move on as a seek from the notification does: the listener has changed place without anything playing.
        compose.onNodeWithTag("play").performClick()
        val player = MediaControl.current!!.player
        compose.waitUntil(10_000) { !player.playing }
        // Into the last paragraph, not past the end of the book, which would count as finishing it.
        val lastStart = Library.segmentStarts(ctx, Library.books.value.first().id).last()
        compose.runOnUiThread { player.seek(lastStart + 100) }
        Thread.sleep(1500) // longer than the service's tick, so a save that is going to happen has happened
        assertFalse("the player must still be paused", player.playing)
        val book = Library.books.value.first()
        val reached = Library.segmentStarts(ctx, book.id).last { it <= player.position }
        assertTrue("the seek should leave the second paragraph, not $reached", reached > Library.segmentStarts(ctx, book.id)[1])
        expectedFile.writeText(JSONObject().put("position", reached).put("percent", Played.percent(reached, false, player.duration, true, 1f)).toString())
        readyFile.writeText("ready")
        Thread.sleep(120_000) // the host kills this process now
        throw AssertionError("The host did not force-stop the app")
    }

    @Smoke @Test
    fun theSamePlaceIsShownAfterTheForceStop() {
        assumeTrue(phase == "check")
        val expected = JSONObject(expectedFile.readText())
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java))
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("book-${Fixtures.TITLE}").fetchSemanticsNodes().isNotEmpty() }
        val book = Library.books.value.first()
        assertEquals(expected.getLong("position"), book.positionMs)
        compose.onNodeWithTag("stage-text-LISTEN-${Fixtures.TITLE}", useUnmergedTree = true)
            .assertTextContains("${expected.getInt("percent")}%", substring = true)
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("sentence-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sentence-${Library.segmentStarts(ctx, book.id).indexOf(expected.getLong("position"))}").assert(selected())
        compose.onNodeWithTag("played").assertTextContains("${expected.getInt("percent")}% played", substring = true)
    }

    private fun assertEquals(expected: Long, actual: Long?) = org.junit.Assert.assertEquals(expected, actual)
}
