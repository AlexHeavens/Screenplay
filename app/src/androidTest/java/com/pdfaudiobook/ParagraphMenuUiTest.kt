/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Intent
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
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
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

/** Long-pressing a paragraph opens a menu to edit or delete it, through the real transcript. Only the speech engine is faked. */
@RunWith(AndroidJUnit4::class)
class ParagraphMenuUiTest {
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

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(120_000) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForTag(tag: String) = waitFor(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag))

    private fun openBook() {
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(Fixtures.pdf(ctx), "application/pdf")
        scenario = ActivityScenario.launch(intent)
        waitFor(hasContentDescription("Listened"))
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        waitForTag("sentence-0")
    }

    private fun sentence(i: Int) = compose.onNodeWithTag("sentence-$i")
    private fun bookId() = Library.books.value.single().id

    private fun highlight(i: Int) {
        sentence(i).performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(HighlightKey).and(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-$i"))).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun note(i: Int, text: String) {
        sentence(i).performTouchInput { swipeRight(startX = width * 0.25f, endX = width * 0.9f) }
        compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true).performTextInput(text)
        compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true).performImeAction()
        waitForTag("paragraph-note-text-$i")
    }

    private fun longPressMenu(i: Int, item: String) {
        sentence(i).performTouchInput { longClick() }
        compose.onNodeWithTag(item, useUnmergedTree = true).performClick()
    }

    @Smoke @Test
    fun editingAParagraphIntoTwoKeepsTheHighlightOnBothAndTheNoteOnTheFirst() {
        openBook()
        highlight(1)
        note(1, "check this claim")
        longPressMenu(1, "paragraph-menu-edit")
        waitForTag("paragraph-edit-input")
        compose.onNodeWithTag("paragraph-edit-input", useUnmergedTree = true).performTextReplacement("Pack my box.\nWith five dozen liquor jugs.")
        compose.onNodeWithTag("paragraph-edit-save", useUnmergedTree = true).performClick()
        waitForTag("sentence-3")

        val yellow = SemanticsMatcher.expectValue(HighlightKey, "YELLOW")
        sentence(1).assert(yellow).assertTextContains("Pack my box.")
        sentence(2).assert(yellow).assertTextContains("With five dozen liquor jugs.")
        sentence(3).assertTextContains(Fixtures.sentences[2])
        compose.onNodeWithTag("paragraph-note-text-1", useUnmergedTree = true).assertTextEquals("check this claim")
        compose.onAllNodesWithTag("paragraph-note-text-2", useUnmergedTree = true).assertCountEquals(0)

        // What the device holds is the same: two highlights, one note, and segments that still tile the audio.
        val id = bookId()
        assertEquals(listOf(1, 2), Highlights.load(ctx, id).map { it.index })
        assertEquals(listOf(1), ParagraphNotes.load(ctx, id).map { it.index })
        val segments = Library.segments(ctx, id)
        assertEquals(Fixtures.sentences.size + 1, segments.size)
        assertEquals(segments.dropLast(1).map { it.end }, segments.drop(1).map { it.start })
    }

    @Smoke @Test
    fun deletingAParagraphRemovesItAndMovesLaterNotesUp() {
        openBook()
        note(2, "later note")
        longPressMenu(0, "paragraph-menu-delete")
        // Nothing was lost with it, so there was nothing to confirm.
        waitFor(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-1"))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("sentence-2").fetchSemanticsNodes().isEmpty() }
        sentence(0).assertTextContains(Fixtures.sentences[1])
        compose.onAllNodesWithText(Fixtures.sentences[0]).assertCountEquals(0)
        compose.onNodeWithTag("paragraph-note-text-1", useUnmergedTree = true).assertTextEquals("later note")
        val id = bookId()
        assertEquals(Fixtures.sentences.drop(1), Library.segments(ctx, id).map { it.text })
        assertEquals(listOf(1), ParagraphNotes.load(ctx, id).map { it.index })
    }

    @Smoke @Test
    fun deletingAHighlightedParagraphAsksFirstAndCancelKeepsIt() {
        openBook()
        highlight(1)
        longPressMenu(1, "paragraph-menu-delete")
        waitForTag("paragraph-delete-confirm")
        compose.onNodeWithTag("paragraph-delete-cancel", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("paragraph-delete-confirm", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertEquals(Fixtures.sentences.size, Library.segments(ctx, bookId()).size)

        longPressMenu(1, "paragraph-menu-delete")
        waitForTag("paragraph-delete-confirm")
        compose.onNodeWithTag("paragraph-delete-confirm", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("sentence-2").fetchSemanticsNodes().isEmpty() }
        assertEquals(emptyList<ParagraphHighlight>(), Highlights.load(ctx, bookId()))
        assertEquals(listOf(Fixtures.sentences[0], Fixtures.sentences[2]), Library.segments(ctx, bookId()).map { it.text })
    }
}
