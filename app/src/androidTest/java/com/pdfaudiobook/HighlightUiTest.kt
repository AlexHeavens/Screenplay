/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.center
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Paragraph highlights through the real transcript: swipe left toggles yellow, holding the swipe opens the six-slice wheel. Only the speech engine is faked. */
@RunWith(AndroidJUnit4::class)
class HighlightUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Engines.synthesizer = { FakeSynthesizer() }
    }

    @After
    fun close() { scenario?.close() }

    private fun waitFor(what: String, matcher: SemanticsMatcher) =
        compose.waitUntil(120_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** Opens a book made by [Fixtures.seedSpokenBook] in place of an import: these tests check the swipe wheel, not OCR. */
    private fun openBook() {
        Fixtures.seedSpokenBook(ctx)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("ready", hasContentDescription("Listened"))
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        waitFor("transcript", SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-0"))
    }

    private fun sentence(i: Int) = compose.onNodeWithTag("sentence-$i")
    private fun highlighted(color: String) = SemanticsMatcher.expectValue(HighlightKey, color)

    @Smoke @Test
    fun swipingLeftHighlightsInYellowAndAgainRemovesIt() {
        openBook()
        sentence(2).assert(SemanticsMatcher.keyNotDefined(HighlightKey))
        sentence(2).performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        compose.waitUntil(10_000) { compose.onAllNodes(highlighted("YELLOW")).fetchSemanticsNodes().isNotEmpty() }
        sentence(2).assert(highlighted("YELLOW"))
        assertEquals(listOf(HighlightColor.YELLOW), Highlights.load(ctx, Library.books.value.single().id).map { it.color })
        compose.onNodeWithTag("highlight-wheel").assertDoesNotExist()

        // A marker pen, not a block: the paragraph carries one band per text line, none taller than a line of its box.
        compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(HighlightBandsKey)).fetchSemanticsNodes().isNotEmpty() }
        val node = sentence(2).fetchSemanticsNode()
        val bands = node.config[HighlightBandsKey]
        assert(bands.isNotEmpty()) { "no bands" }
        assert(bands.all { it.height < node.size.height }) { "a band fills the whole paragraph: $bands in ${node.size}" }
        assert(bands.zipWithNext().all { (a, b) -> b.top > a.bottom }) { "bands touch: $bands" }

        sentence(2).performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.keyNotDefined(HighlightKey).and(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-2"))).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList<ParagraphHighlight>(), Highlights.load(ctx, Library.books.value.single().id))
    }

    /** Writes the highlighted paragraph as a real bitmap (emulator screenshots are black), for looking at; pulled from the app's external cache. */
    @Test
    fun rendersTheMarkerLookToAnImage() {
        openBook()
        sentence(2).performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        compose.waitUntil(10_000) { compose.onAllNodes(highlighted("YELLOW")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val bitmap = sentence(2).captureToImage().asAndroidBitmap()
        File(ctx.externalCacheDir, "marker.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Smoke @Test
    fun holdingTheSwipeOpensTheSixSliceWheelAndReleasingOnASlicePicksItsColour() {
        openBook()
        sentence(2).performTouchInput {
            down(Offset(width * 0.8f, centerY))
            moveTo(Offset(width * 0.6f, centerY))
            moveTo(Offset(width * 0.4f, centerY))
        }
        // Held past the threshold: the wheel opens, and nothing is highlighted yet.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("highlight-wheel").fetchSemanticsNodes().isNotEmpty() }
        for (k in 0..5) compose.onNodeWithTag("highlight-slice-$k").assertIsDisplayed()
        compose.onNodeWithTag("highlight-slice-6").assertDoesNotExist()
        sentence(2).assert(SemanticsMatcher.keyNotDefined(HighlightKey))

        // Slice 2 is green. Move the finger from the wheel's centre to the middle of that slice and lift.
        val wheel = compose.onNodeWithTag("highlight-wheel").fetchSemanticsNode().boundsInRoot.center
        val green = compose.onNodeWithTag("highlight-slice-2").fetchSemanticsNode().boundsInRoot.center
        sentence(2).performTouchInput { moveBy(green - wheel) }
        sentence(2).performTouchInput { up() }

        compose.waitUntil(10_000) { compose.onAllNodes(highlighted("GREEN")).fetchSemanticsNodes().isNotEmpty() }
        sentence(2).assert(highlighted("GREEN"))
        compose.onNodeWithTag("highlight-wheel").assertDoesNotExist()
        assertEquals(listOf(HighlightColor.GREEN), Highlights.load(ctx, Library.books.value.single().id).map { it.color })
    }

    @Smoke @Test
    fun releasingTheWheelInItsCentreCancels() {
        openBook()
        sentence(2).performTouchInput {
            down(Offset(width * 0.8f, centerY))
            moveTo(Offset(width * 0.6f, centerY))
            moveTo(Offset(width * 0.4f, centerY))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("highlight-wheel").fetchSemanticsNodes().isNotEmpty() }
        sentence(2).performTouchInput { up() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("highlight-wheel").fetchSemanticsNodes().isEmpty() }
        sentence(2).assert(SemanticsMatcher.keyNotDefined(HighlightKey))
    }
}
