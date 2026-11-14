/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Intent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A scanned page's footnote is kept as an annotation of the paragraph that cites it and is never spoken. The import, the transcript, the
 * reader and the backup are real; OCR is replaced by the page text a scan gives (the footnote as its own block, as a book such as The Prince
 * prints it) and the speech engine by a recorder that also makes the sound.
 */
@RunWith(AndroidJUnit4::class)
class FootnoteUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val footnote = "[3] Louis XII. entered Italy in 1499 at the invitation of the Venetians."
    private val page = listOf(
        "The Venetians had called the French into Italy to win half of Lombardy for themselves.",
        "King Louis made five mistakes in Italy, for he allowed [3] the weaker powers to be destroyed and the strong one to grow stronger.",
        footnote,
    )

    private class Recording(private val spoken: MutableList<String>) : Synthesizer {
        override suspend fun speak(text: String, wav: File) { spoken += text; FakeSynthesizer().speak(text, wav) }
        override fun close() = Unit
    }

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        spoken.clear()
        Engines.synthesizer = { Recording(spoken) }
        Engines.recognisePage = { page }
    }

    @After
    fun restore() {
        Engines.recognisePage = null
        Options().save(ctx)
        scenario?.close()
    }

    private fun import(options: Options) {
        options.save(ctx)
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(Fixtures.pdf(ctx), "application/pdf"),
        )
        compose.waitUntil(120_000) { compose.onAllNodes(hasText("Ready", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openBook() {
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-0")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun bookId() = Library.books.value.single().id

    /** The paragraph the footnote's marker was in, by the text that follows it. */
    private fun citingParagraph(): Int = Library.segments(ctx, bookId()).indexOfFirst { "five mistakes" in it.text }

    @Test
    fun aFootnoteIsAnAnnotationUnderItsParagraphAndIsNeverSpoken() {
        import(Options(footnotes = true))
        val id = bookId()
        val texts = Library.segments(ctx, id).map { it.text }
        assertTrue("the footnote must not be in the transcript: $texts", texts.none { "Louis XII" in it })
        assertTrue("the marker must not be read: $texts", texts.none { "[3]" in it })
        assertTrue("the footnote must not be spoken: $spoken", spoken.none { "Louis XII" in it || "[3]" in it })
        assertEquals(listOf(footnote), Library.footnotes(ctx, id).map { it.text })

        openBook()
        val at = citingParagraph()
        assertTrue("the paragraph with the marker is in the transcript: $texts", at >= 0)
        compose.onNodeWithTag("footnote-$at", useUnmergedTree = true).assertTextContains(footnote)
        assertEquals("only the paragraph that cites it carries the footnote", 1, compose.onAllNodesWithTag("footnote-$at", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, texts.indices.count { compose.onAllNodesWithTag("footnote-$it", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() })
        // A tap on the annotation is not a tap on the paragraph: the audio does not start.
        compose.onNodeWithTag("footnote-$at", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("play").assertTextEquals("Play")
    }

    @Test
    fun aBackupCarriesTheFootnotesAndRestoresThemWithTheBook() {
        import(Options(footnotes = true))
        val id = bookId()
        Backup.restore(ctx, Backup.zip(ctx, id, Fixtures.TITLE))
        val restored = Library.books.value.single { it.id != id }
        assertEquals(Library.footnotes(ctx, id), Library.footnotes(ctx, restored.id))
        assertEquals(1, Library.footnotes(ctx, restored.id).size)
    }

    @Test
    fun withTheOptionOffTheFootnoteIsSpokenAsBefore() {
        import(Options(footnotes = false))
        val id = bookId()
        assertTrue("the footnote is read aloud as before: $spoken", spoken.any { "Louis XII" in it })
        assertTrue(Library.footnotes(ctx, id).isEmpty())
        openBook()
        assertFalse(Library.segments(ctx, id).indices.any { compose.onAllNodesWithTag("footnote-$it", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() })
    }

    @Test
    fun theFootnoteOptionIsOnByDefaultAndSavedWithTheOthers() {
        Options().save(ctx)
        assertTrue(Options.load(ctx).footnotes)
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("opt-footnotes").assertIsOn()
        compose.onNodeWithTag("opt-footnotes").performClick()
        compose.onNodeWithTag("opt-footnotes").assertIsOff()
        assertFalse(Options.load(ctx).footnotes)
    }
}
