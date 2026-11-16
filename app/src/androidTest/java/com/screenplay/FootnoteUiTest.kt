/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
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
        Engines.surveyPages = { emptyList() }
    }

    @After
    fun restore() {
        Engines.recognisePage = null
        Engines.surveyPages = null
        Options(spell = false).save(ctx)
        scenario?.close()
    }

    private fun import(options: Options) {
        options.save(ctx)
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(Fixtures.pdf(ctx), "application/pdf"),
        )
        compose.waitUntil(120_000) { compose.onAllNodes(hasContentDescription("Listened")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openBook() {
        compose.onNodeWithTag("book-${Library.books.value.single().title}").performClick()
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
        // The citation stays in the shown text, where the note belongs, though it is not in the transcript and is not spoken.
        compose.onNode(hasText("allowed[3] the weaker", substring = true), useUnmergedTree = true).assertExists()
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
        Options(spell = false).save(ctx)
        assertTrue(Options.load(ctx).footnotes)
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("opt-footnotes").assertIsOn()
        compose.onNodeWithTag("opt-footnotes").performClick()
        compose.onNodeWithTag("opt-footnotes").assertIsOff()
        assertFalse(Options.load(ctx).footnotes)
    }

    /** A chapter as Project Gutenberg's EPUB of The Prince marks it: a linked `[3]` in the text and the note as `<p class="footnote">` after its paragraph. */
    private fun gutenbergEpub(): File = File(ctx.cacheDir, "prince.epub").also { book ->
        java.util.zip.ZipOutputStream(book.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="c.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("c.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Footnoted Prince</dc:title><dc:creator>N. M.</dc:creator></metadata>
                <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""")
            put("a.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body><h2>CHAPTER III</h2>
                <p>The Venetians had called the French into Italy to win half of Lombardy for themselves.</p>
                <p>King Louis made five mistakes in Italy, for he allowed<a href="#fn-3.1" id="fnref-3.1"><sup>[3]</sup></a> the weaker powers to be destroyed.</p>
                <p class="footnote"><a id="fn-3.1"/> <a href="#fnref-3.1">[3]</a> $FOOT_TEXT</p><p>He then went on to lose the whole of it again.</p></body></html>""")
        }
    }

    private val FOOT_TEXT = "Louis XII entered Italy in 1499 at the invitation of the Venetians."

    private fun importEpub(options: Options) {
        options.save(ctx)
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", gutenbergEpub()), "application/epub+zip"),
        )
        compose.waitUntil(120_000) { Library.books.value.any { it.status == Status.READY } }
    }

    @Test
    fun anEpubFootnoteIsAnAnnotationUnderItsParagraphAndIsNeverSpoken() {
        importEpub(Options(footnotes = true))
        val id = bookId()
        val texts = Library.segments(ctx, id).map { it.text }
        assertTrue("the footnote must not be in the transcript: $texts", texts.none { "Louis XII" in it || "[3]" in it })
        assertTrue("the footnote must not be spoken: $spoken", spoken.none { "Louis XII" in it || "[3]" in it })
        assertEquals(listOf("[3] $FOOT_TEXT"), Library.footnotes(ctx, id).map { it.text })
        openBook()
        val at = citingParagraph()
        compose.onNodeWithTag("footnote-$at", useUnmergedTree = true).assertTextContains("[3] $FOOT_TEXT")
        compose.onNode(hasText("allowed[3] the weaker", substring = true), useUnmergedTree = true).assertExists()
    }

    @Test
    fun anEpubWithTheOptionOffSpeaksItsFootnoteAsBefore() {
        importEpub(Options(footnotes = false))
        assertTrue("the footnote is read aloud as before: $spoken", spoken.any { "Louis XII" in it })
        assertTrue(Library.footnotes(ctx, bookId()).isEmpty())
    }
}
