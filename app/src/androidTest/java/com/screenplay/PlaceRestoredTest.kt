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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The place in a book survives the app being closed: after two paragraphs have played, a fresh launch opens the book on the
 * paragraph reached, and the library shows how much has been played. Everything is real but the speech engine ([FakeSynthesizer]).
 */
@RunWith(AndroidJUnit4::class)
class PlaceRestoredTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options(spell = false).save(ctx)
        Engines.synthesizer = { FakeSynthesizer(msPerWord = 600) }
    }

    @After
    fun close() {
        scenario?.close()
        MediaControl.close()
    }

    private fun selected() = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    @Smoke @Test
    fun theBookReopensOnTheParagraphReachedAndTheLibraryShowsThePercentPlayed() {
        Fixtures.seedSpokenBook(ctx, msPerWord = 600)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(120_000) { compose.onAllNodes(hasContentDescription("Listened")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("sentence-2").fetchSemanticsNodes().isNotEmpty() }
        // Play the second paragraph (a tap speaks it from its start) and wait for the save of it to reach the book's details.
        compose.onNodeWithTag("sentence-1").performClick()
        compose.waitUntil(30_000) { Library.books.value.firstOrNull()?.positionMs.let { it != null && it > 0 } }
        MediaControl.close()
        scenario!!.close()

        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("book-${Fixtures.TITLE}").fetchSemanticsNodes().isNotEmpty() }
        val played = Library.books.value.first().playedPercent!!
        compose.onNodeWithTag("stage-text-LISTEN-${Fixtures.TITLE}", useUnmergedTree = true).assertTextContains("$played%", substring = true)
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("sentence-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sentence-1").assert(selected())
        compose.onNodeWithTag("played").assertTextContains("% played", substring = true)
    }
}
