/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A scanned book's contents pages are shown in the reader but not spoken and not counted. The import, the transcript and the reader are real;
 * OCR is replaced by the text of each page (a contents page, its continuation, then prose) and the speech engine by a recorder.
 */
@RunWith(AndroidJUnit4::class)
class ContentsUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val pages = listOf(
        listOf("Contents", "Introduction ........ 1", "Chapter 1 The Duties of a Prince 41", "Chapter 2 On Hereditary Principalities 57", "Chapter 3 Mixed Principalities 63"),
        listOf("The Venetians had called the French into Italy to win half of Lombardy for themselves."),
        listOf("King Louis made five mistakes in Italy, for he allowed the weaker powers to be destroyed."),
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
        Engines.recognisePage = { index -> pages[index] }
    }

    @After
    fun restore() {
        Engines.recognisePage = null
        Options(spell = false, voice = Voice.PHONE).save(ctx)
        scenario?.close()
    }

    private fun import(options: Options) {
        options.save(ctx)
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(Fixtures.pdf(ctx, pages = pages.size), "application/pdf"),
        )
        compose.waitUntil(120_000) { compose.onAllNodes(hasContentDescription("Listened")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openBook() {
        compose.onNodeWithTag("book-${Library.books.value.single().title}").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-0")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aContentsPageIsShownInTheReaderButNeverSpokenOrInTheTranscript() {
        import(Options(contents = true, voice = Voice.PHONE))
        val id = Library.books.value.single().id
        val texts = Library.segments(ctx, id).map { it.text }
        assertTrue("the book itself is spoken: $texts", texts.any { "Venetians" in it } && texts.any { "King Louis" in it })
        assertTrue("the contents must not be in the transcript: $texts", texts.none { "Duties of a Prince" in it })
        assertTrue("the contents must not be spoken: $spoken", spoken.none { "Duties of a Prince" in it || "Hereditary" in it })

        val notes = Library.footnotes(ctx, id)
        assertEquals(1, notes.size)
        assertTrue(notes.single().contents)

        openBook()
        compose.onNodeWithTag("footnote-0", useUnmergedTree = true).assertTextContains("Chapter 1 The Duties of a Prince 41", substring = true)
        assertEquals("one annotation, for the one contents list", 1, texts.indices.count { compose.onAllNodesWithTag("footnote-$it", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() })
        // A tap on the contents is not a tap on the paragraph: the audio does not start.
        compose.onNodeWithTag("footnote-0", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("play").assertTextContains("Play")
    }

    @Test
    fun withTheOptionOffTheContentsPageIsSpokenAsBefore() {
        import(Options(contents = false, voice = Voice.PHONE))
        val id = Library.books.value.single().id
        assertTrue("the contents is read aloud as before: $spoken", spoken.any { "Duties of a Prince" in it })
        assertTrue(Library.footnotes(ctx, id).isEmpty())
        assertTrue(Library.segments(ctx, id).any { "Duties of a Prince" in it.text })
    }

    @Test
    fun theContentsOptionIsOnByDefaultAndSavedWithTheOthers() {
        Options(spell = false, voice = Voice.PHONE).save(ctx)
        assertTrue(Options.load(ctx).contents)
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("opt-contents").assertIsOn()
        compose.onNodeWithTag("opt-contents").performClick()
        compose.onNodeWithTag("opt-contents").assertIsOff()
        assertFalse(Options.load(ctx).contents)
    }
}
