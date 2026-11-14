/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Find books" through the real screen, search client, download and import queue. Only the network is faked, behind [Engines.httpGet]
 * (and speech, as everywhere): the catalogue answers with canned pages and the EPUB link with a small book built here.
 */
@RunWith(AndroidJUnit4::class)
class FindBooksUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val requests = CopyOnWriteArrayList<String>()
    private val agents = CopyOnWriteArrayList<String>()
    private val epubLink = "https://www.gutenberg.org/ebooks/4242.epub3.images"

    private fun result(id: Int, title: String, downloads: Int, epub: Boolean) =
        """{"id":$id,"title":"$title","authors":[{"name":"Example, Ada"}],"languages":["en"],"copyright":false,"media_type":"Text","download_count":$downloads,
            "formats":{${if (epub) "\"application/epub+zip\":\"https://www.gutenberg.org/ebooks/$id.epub3.images\"" else "\"text/html\":\"https://www.gutenberg.org/ebooks/$id.html.images\""}}}"""

    private fun page(vararg results: String) = """{"count":${results.size},"next":null,"previous":null,"results":[${results.joinToString(",")}]}"""

    private fun book(): ByteArray {
        val out = ByteArrayOutputStream()
        val xhtml = { body: String -> """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>""" }
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="c.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("c.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Harbour Notes</dc:title><dc:creator>Ada Example</dc:creator></metadata>
                <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""")
            put("a.xhtml", xhtml("<p>The harbour opens at dawn.</p><p>*** END OF THE PROJECT GUTENBERG EBOOK HARBOUR NOTES ***</p>"))
        }
        return out.toByteArray()
    }

    @Before
    fun fakeNetwork() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options().save(ctx)
        Engines.synthesizer = { FakeSynthesizer() }
        Gutendex.client = null
        Engines.httpGet = { url, agent, out, _ ->
            requests += url
            agents += agent
            when {
                url.endsWith(".epub3.images") -> out.write(book())
                "topic=" in url -> out.write(page(result(4242, "Harbour Notes", 30_000, epub = true), result(77, "Quiet Shore", 3_000, epub = true), result(88, "Text Only", 2_000, epub = false)).toByteArray())
                else -> out.write(page(result(5, "Other Book", 100, epub = true)).toByteArray())
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun restore() {
        Engines.httpGet = Gutendex::fetch
        Gutendex.client = null
        scenario?.close()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitForTag(tag: String) = compose.waitUntil(30_000) { exists(tag) }

    private fun openFindBooks() {
        compose.onNodeWithTag("find-books").performClick()
        compose.onNodeWithTag("find-notice").assertIsDisplayed()
    }

    @Smoke @Test
    fun searchesByGenreAndPopularityThenAddsTheTappedBookToTheImportQueue() {
        openFindBooks()
        assertTrue("nothing is requested until the reader asks", requests.isEmpty())

        compose.onNodeWithTag("find-query").performTextInput("harbour")
        compose.onNodeWithTag("find-search").performClick()
        waitForTag("result-5")
        assertEquals(listOf("https://gutendex.com/books/?search=harbour&languages=en&sort=popular"), requests.toList())

        compose.onNodeWithTag("genre-SCIENCE_FICTION").performClick()
        waitForTag("result-4242")
        assertEquals("https://gutendex.com/books/?search=harbour&topic=science+fiction&languages=en&sort=popular", requests.last())
        compose.onNodeWithTag("result-title-4242", useUnmergedTree = true).assertTextContains("Harbour Notes")
        compose.onNodeWithTag("result-detail-4242", useUnmergedTree = true).assertTextContains("Ada Example · EN · 30,000 downloads")
        waitForTag("result-77")

        val before = requests.size
        compose.onNodeWithTag("popularity-VERY_POPULAR").performClick()
        compose.waitUntil(10_000) { !exists("result-77") }
        assertTrue(exists("result-4242"))
        assertEquals("a popularity filter works on the page already loaded", before, requests.size)

        compose.onNodeWithTag("result-4242").performClick()
        waitForTag("result-state-4242")
        compose.waitUntil(120_000) { Library.books.value.singleOrNull()?.status == Status.READY }

        assertEquals(epubLink, requests.last())
        assertTrue(agents.toString(), agents.all { it.startsWith("Screenplay/") && "github.com/AlexHeavens/Screenplay" in it })
        val added = Library.books.value.single()
        assertEquals("gutenberg:4242", added.source)
        assertEquals("Harbour Notes", added.title)
        assertTrue("the licence text is kept", Library.segments(ctx, added.id).any { "END OF THE PROJECT GUTENBERG EBOOK" in it.text })

        compose.onNodeWithTag("find-back").performClick()
        compose.onNodeWithTag("book-Harbour Notes").assertIsDisplayed()
    }

    @Test
    fun theSecondBookIsDownloadedWhileTheFirstIsBeingMade() {
        val firstLink = epubLink
        val secondLink = "https://www.gutenberg.org/ebooks/77.epub3.images"
        val inner = Engines.httpGet
        val arrived = CopyOnWriteArrayList<String>()
        val firstMayFinish = CompletableDeferred<Unit>()
        Engines.httpGet = { url, agent, out, onProgress ->
            if (url.endsWith(".epub3.images")) arrived += url
            if (url == firstLink) firstMayFinish.await()
            inner(url, agent, out, onProgress)
        }
        val speechMayGo = holdSpeech()
        fun bookFrom(source: String) = Library.books.value.single { it.source == source }

        openFindBooks()
        compose.onNodeWithTag("genre-SCIENCE_FICTION").performClick()
        waitForTag("result-77")
        compose.onNodeWithTag("result-4242").performClick()
        compose.onNodeWithTag("result-77").performClick()
        compose.waitUntil(30_000) { firstLink in arrived }
        compose.waitUntil(30_000) { Library.books.value.size == 2 }
        Thread.sleep(1_500)
        assertEquals("one download at a time, in queue order", listOf(firstLink), arrived.toList())

        firstMayFinish.complete(Unit)
        compose.waitUntil(60_000) { bookFrom("gutenberg:4242").status != Status.QUEUED }
        compose.waitUntil(60_000) { !bookFrom("gutenberg:77").awaitingDownload }
        assertTrue("the first book is still being made", bookFrom("gutenberg:4242").status != Status.READY)
        assertEquals(Status.QUEUED, bookFrom("gutenberg:77").status)
        assertEquals(listOf(firstLink, secondLink), arrived.toList())

        speechMayGo.complete(Unit)
        compose.waitUntil(120_000) { Library.books.value.all { it.status == Status.READY } }
        assertEquals("each book is downloaded once", listOf(firstLink, secondLink), arrived.toList())
        assertTrue("no partial file is left", Library.books.value.all { b -> File(Library.dir(ctx, b.id), "source.epub.part").exists().not() })
    }

    /** Speech is held, so the first book stays "being made" until the returned gate is opened. */
    private fun holdSpeech(): CompletableDeferred<Unit> {
        val speechMayGo = CompletableDeferred<Unit>()
        Engines.synthesizer = {
            val real = FakeSynthesizer()
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) { speechMayGo.await(); real.speak(text, wav) }
                override fun close() = real.close()
            }
        }
        return speechMayGo
    }

    /**
     * Makes the download of [link] report [steps] (bytes received, bytes announced) and then wait at [mayFinish], as a slow connection would;
     * [atMidPoint] opens once the steps are reported. Every other request is served as before.
     */
    private fun slowDownload(link: String, steps: List<Pair<Long, Long>>, atMidPoint: CompletableDeferred<Unit>, mayFinish: CompletableDeferred<Unit>) {
        val inner = Engines.httpGet
        Engines.httpGet = { url, agent, out, onProgress ->
            if (url == link) {
                out.write(book())
                steps.forEach { (received, announced) -> onProgress(received, announced) }
                atMidPoint.complete(Unit)
                mayFinish.await()
                if (steps.last().second > 0) onProgress(steps.last().second, steps.last().second)
            } else inner(url, agent, out, onProgress)
        }
    }

    private fun barRange(title: String) = compose.onNodeWithTag("download-bar-$title", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]

    private fun waitForBar(title: String, value: ProgressBarRangeInfo) = compose.waitUntil(30_000) { exists("download-bar-$title") && barRange(title) == value }

    @Smoke @Test
    fun aBookDownloadedOnItsOwnTurnShowsItsProgressOnItsResultAndItsRowAndThenCarriesOn() {
        val midPoint = CompletableDeferred<Unit>()
        val mayFinish = CompletableDeferred<Unit>()
        slowDownload(epubLink, listOf(1_000_000L to 4_000_000L, 2_000_000L to 4_000_000L), midPoint, mayFinish)

        openFindBooks()
        compose.onNodeWithTag("genre-SCIENCE_FICTION").performClick()
        waitForTag("result-4242")
        compose.onNodeWithTag("result-4242").performClick()
        compose.waitUntil(30_000) { midPoint.isCompleted }
        compose.waitUntil(30_000) { exists("result-state-4242") && "Downloading 50%" in compose.onNodeWithTag("result-state-4242", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString() }

        compose.onNodeWithTag("find-back").performClick()
        waitForBar("Harbour Notes", ProgressBarRangeInfo(0.5f, 0f..1f))
        compose.onNodeWithTag("status-Harbour Notes", useUnmergedTree = true).assertTextContains("Downloading 50% · 1.9 of 3.8 MB")

        mayFinish.complete(Unit)
        compose.waitUntil(30_000) { !exists("download-bar-Harbour Notes") }
        compose.waitUntil(120_000) { Library.books.value.singleOrNull()?.status == Status.READY }
    }

    @Test
    fun aBookDownloadedAheadShowsItsProgressWhileTheBookBeingMadeShowsNoneAndReturnsToQueued() {
        val midPoint = CompletableDeferred<Unit>()
        val mayFinish = CompletableDeferred<Unit>()
        slowDownload("https://www.gutenberg.org/ebooks/77.epub3.images", listOf(1_000_000L to 4_000_000L, 2_000_000L to 4_000_000L), midPoint, mayFinish)
        val speechMayGo = holdSpeech()

        openFindBooks()
        compose.onNodeWithTag("genre-SCIENCE_FICTION").performClick()
        waitForTag("result-77")
        compose.onNodeWithTag("result-4242").performClick()
        compose.onNodeWithTag("result-77").performClick()
        compose.waitUntil(30_000) { midPoint.isCompleted }
        compose.onNodeWithTag("find-back").performClick()

        waitForBar("Quiet Shore", ProgressBarRangeInfo(0.5f, 0f..1f))
        compose.onNodeWithTag("status-Quiet Shore", useUnmergedTree = true).assertTextContains("Downloading 50%", substring = true)
        assertTrue("the book being made has its file and shows no download bar", !exists("download-bar-Harbour Notes"))

        mayFinish.complete(Unit)
        compose.waitUntil(30_000) { !exists("download-bar-Quiet Shore") }
        compose.onNodeWithTag("status-Quiet Shore", useUnmergedTree = true).assertTextContains("Queued · position 2")

        speechMayGo.complete(Unit)
        compose.waitUntil(120_000) { Library.books.value.size == 2 && Library.books.value.all { it.status == Status.READY } }
    }

    @Test
    fun aDownloadOfUnknownLengthShowsAnIndeterminateBarAndTheBytesSoFar() {
        val midPoint = CompletableDeferred<Unit>()
        val mayFinish = CompletableDeferred<Unit>()
        slowDownload(epubLink, listOf(1_048_576L to -1L), midPoint, mayFinish)

        openFindBooks()
        compose.onNodeWithTag("genre-SCIENCE_FICTION").performClick()
        waitForTag("result-4242")
        compose.onNodeWithTag("result-4242").performClick()
        compose.waitUntil(30_000) { midPoint.isCompleted }
        compose.onNodeWithTag("find-back").performClick()

        waitForBar("Harbour Notes", ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithTag("status-Harbour Notes", useUnmergedTree = true).assertTextContains("Downloading 1.0 MB")

        mayFinish.complete(Unit)
        compose.waitUntil(30_000) { !exists("download-bar-Harbour Notes") }
    }

    @Smoke @Test
    fun saysWhenABookHasNoEpubAndSendsNoRequestForIt() {
        openFindBooks()
        compose.onNodeWithTag("genre-ADVENTURE").performClick()
        waitForTag("result-88")
        compose.onNodeWithTag("result-state-88", useUnmergedTree = true).assertTextContains("No EPUB available")
        val before = requests.size
        compose.onNodeWithTag("result-88").performClick()
        compose.waitUntil(10_000) { exists("find-message") }
        compose.onNodeWithTag("find-message").assertTextContains("no EPUB", substring = true)
        assertEquals(before, requests.size)
        assertTrue(Library.books.value.isEmpty())
    }

    @Smoke @Test
    fun showsAFriendlyMessageWhenTheCatalogueCannotBeReached() {
        Engines.httpGet = { _, _, _, _ -> throw CatalogueException("Could not reach the book catalogue. Check the connection and try again.") }
        openFindBooks()
        compose.onNodeWithTag("find-query").performTextInput("anything")
        compose.onNodeWithTag("find-search").performClick()
        waitForTag("find-message")
        compose.onNodeWithTag("find-message").assertTextContains("Could not reach the book catalogue", substring = true)
        assertTrue(Library.books.value.isEmpty())
    }
}
