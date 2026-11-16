/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Imports an EPUB through "Import" and exports it as one again: the real picker button, parser, pipeline, transcript, reader and share
 * sheet. Only the system file picker and the speech engine are faked. The book is `assets/epub/lighthouse.epub`, text written for this project.
 */
@RunWith(AndroidJUnit4::class)
class EpubUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private var scenario: ActivityScenario<MainActivity>? = null
    private val title = "The Lighthouse Almanac"

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options().save(ctx)
        Engines.synthesizer = { FakeSynthesizer() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun uriOf(f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    private fun asset(name: String) = File(ctx.cacheDir, name).also { f -> assets.open("epub/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }

    /** The shelf's import button, with the system picker answering [file]. */
    private fun importThroughThePicker(file: File) {
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uriOf(file))))
            compose.onNodeWithTag("import").performClick()
            compose.waitUntil(30_000) { Intents.getIntents().any { it.action == Intent.ACTION_OPEN_DOCUMENT } }
            // The picker is asked for EPUBs as well as PDFs.
            val asked = Intents.getIntents().first { it.action == Intent.ACTION_OPEN_DOCUMENT }.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList()
            assertTrue(asked.toString(), "application/epub+zip" in asked && "application/pdf" in asked)
        } finally {
            Intents.release()
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(120_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    @Smoke @Test
    fun importsAnEpubWithItsTitleAuthorChaptersAndText() {
        importThroughThePicker(asset("lighthouse.epub"))
        waitFor(hasContentDescription("Listened"))

        val book = Library.books.value.single()
        assertEquals(title, book.title)
        assertEquals("Ada Example", book.author)
        compose.onNodeWithTag("book-$title").assertExists()
        compose.onNodeWithTag("author-$title", useUnmergedTree = true).assertExists()
        val segments = Library.segments(ctx, book.id)
        assertEquals(listOf("Part One", "The Lamp", "The Fog"), Chapters.find(segments, book.contents).map { it.title })
        assertEquals("Every evening the keeper climbed ninety steps to light the lamp.", segments[3].text)
        assertTrue(File(Library.dir(ctx, book.id), Files.EPUB).exists())

        compose.onNodeWithTag("book-$title").performClick()
        waitFor(hasText("On the foggy mornings he rang the bell instead."))
    }

    @Smoke @Test
    fun anEpubThatPrintsItsOwnContentsListHasEachChapterOnce() {
        val xhtml = { body: String -> """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>""" }
        val book = File(ctx.cacheDir, "contents-list.epub")
        ZipOutputStream(book.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="c.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("c.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Printed Contents</dc:title><dc:creator>Ann Author</dc:creator></metadata>
                <manifest><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""")
            put("nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
                <li><a href="a.xhtml#one">CHAPTER 1. Loomings.</a></li><li><a href="a.xhtml#two">CHAPTER 2. The Carpet-Bag.</a></li></ol></nav></body></html>""")
            put("a.xhtml", xhtml("""<p>CHAPTER 1. Loomings.</p><p>CHAPTER 2. The Carpet-Bag.</p><h2 id="one">CHAPTER 1. Loomings</h2><p>Call me Ishmael.</p><h2 id="two">CHAPTER 2. The Carpet-Bag</h2><p>I stuffed a shirt into my bag.</p>"""))
        }
        importThroughThePicker(book)
        waitFor(hasContentDescription("Listened"))

        val id = Library.books.value.single().id
        val segments = Library.segments(ctx, id)
        val chapters = Library.chapters(ctx, id)
        assertEquals(listOf("CHAPTER 1. Loomings.", "CHAPTER 2. The Carpet-Bag."), chapters.map { it.title })
        assertEquals(chapters.map { it.title }, chapters.map { segments[it.index].text })
        assertEquals(listOf("Call me Ishmael.", "I stuffed a shirt into my bag."), chapters.map { segments[it.index + 2].text })
    }

    @Smoke @Test
    fun anEpubWithBlockCapitalChapterTitlesShowsThemInStandardCapitals() {
        val xhtml = { body: String -> """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>""" }
        val book = File(ctx.cacheDir, "block-capitals.epub")
        ZipOutputStream(book.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="c.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("c.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Block Capitals</dc:title><dc:creator>Ann Author</dc:creator></metadata>
                <manifest><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""")
            put("nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
                <li><a href="a.xhtml#one">CHAPTER III</a></li><li><a href="a.xhtml#two">THE PRINCE'S DUTIES</a></li><li><a href="a.xhtml#three">The Last Word</a></li></ol></nav></body></html>""")
            put("a.xhtml", xhtml("""<h2 id="one">CHAPTER III</h2><p>The first chapter is short.</p><h2 id="two">THE PRINCE'S DUTIES</h2><p>The second chapter is short too.</p><h2 id="three">The Last Word</h2><p>The third chapter ends the book.</p>"""))
        }
        importThroughThePicker(book)
        waitFor(hasContentDescription("Listened"))

        val id = Library.books.value.single().id
        assertEquals(listOf("Chapter III", "The Prince's Duties", "The Last Word"), Library.chapters(ctx, id).map { it.title })

        compose.onNodeWithTag("book-Block Capitals").performClick()
        waitFor(hasText("The second chapter is short too."))
        compose.onNodeWithTag("chapters").performClick()
        waitFor(hasText("The Prince's Duties"))
    }

    @Smoke @Test
    fun exportsAnEpubThatImportsAgainAsTheSameBook() {
        importThroughThePicker(asset("lighthouse.epub"))
        waitFor(hasContentDescription("Listened"))
        val original = Library.books.value.single()
        compose.onNodeWithTag("book-$title").performClick()
        waitFor(hasContentDescription("Share"))

        val exported = File(ctx.cacheDir, "exported.epub")
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            compose.onNodeWithTag("export").performClick()
            compose.onNodeWithText("EPUB").performClick()
            compose.waitUntil(60_000) { Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER } }
            @Suppress("DEPRECATION")
            val send = Intents.getIntents().first { it.action == Intent.ACTION_CHOOSER }.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            @Suppress("DEPRECATION")
            val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            ctx.contentResolver.openInputStream(uri)!!.use { i -> exported.outputStream().use { i.copyTo(it) } }
        } finally {
            Intents.release()
        }
        val first = java.util.zip.ZipInputStream(exported.inputStream()).use { it.nextEntry!! }
        assertEquals("mimetype", first.name)

        // Back to the shelf, then import the file the export shared.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        importThroughThePicker(exported)
        compose.waitUntil(120_000) { Library.books.value.let { b -> b.size == 2 && b.all { it.status == Status.READY } } }

        val copy = Library.books.value.single { it.id != original.id }
        assertEquals(original.title, copy.title)
        assertEquals(original.author, copy.author)
        assertEquals(Chapters.find(Library.segments(ctx, original.id), original.contents).map { it.title }, Chapters.find(Library.segments(ctx, copy.id), copy.contents).map { it.title })
        assertEquals(Library.segments(ctx, original.id).map { it.text }, Library.segments(ctx, copy.id).map { it.text })
    }

    @Smoke @Test
    fun saysWhyAnEpubWithDrmCannotBeImported() {
        val drm = File(ctx.cacheDir, "locked.epub")
        ZipOutputStream(drm.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("META-INF/container.xml")); z.write("<container><rootfiles><rootfile full-path=\"c.opf\"/></rootfiles></container>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/encryption.xml"))
            z.write("<encryption><EncryptedData><EncryptionMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#aes128-cbc\"/></EncryptedData></encryption>".toByteArray()); z.closeEntry()
        }
        importThroughThePicker(drm)
        waitFor(hasText("Failed: This EPUB is protected by DRM, so it cannot be imported."))
        assertEquals(Status.FAILED, Library.books.value.single().status)
    }
}
