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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
 * "Find books" through the real screen, stored book list, download and import queue. Only the network is faked, behind [Engines.httpGet]
 * (and speech, as everywhere): the book list release answers with a small xz file and the EPUB link with a small book built here.
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

    /** The book list file as scripts/build-catalogue.py writes it: tags 0 Science Fiction, 1 Adventure; the columns are id, title, authors, languages, downloads, tags. */
    private fun list(vararg extra: String): ByteArray {
        val text = (listOf(
            "catalogue-format 1 build 2026-11-14",
            "tags\tScience Fiction\tAdventure",
            "4242\tHarbour Notes\tExample, Ada\ten\t30000\t0",
            "77\tQuiet Shore\tExample, Ada\ten\t3000\t0",
            "90\tAdventure Isle\tVoyager, Sam\ten\t2000\t1",
            "5\tOther Book\tExample, Ada\ten\t100\t",
            "6\tLe Livre Calme\tLecteur, Léa\tfr\t40000\t",
        ) + extra).joinToString("\n", postfix = "\n")
        return ByteArrayOutputStream().also { out -> org.tukaani.xz.XZOutputStream(out, org.tukaani.xz.LZMA2Options()).use { it.write(text.toByteArray()) } }.toByteArray()
    }

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

    /** What the book list address answers with; a test changes it to publish a new list or to lose the connection. */
    private var listAnswer: (java.io.OutputStream) -> Unit = { it.write(list()) }
    private val storedList get() = File(ctx.filesDir, Catalogue.FILE)

    @Before
    fun fakeNetwork() {
        File(ctx.filesDir, "books").deleteRecursively()
        storedList.delete()
        File(ctx.filesDir, Catalogue.FILE + ".new").delete()
        Library.refresh(ctx)
        Options().save(ctx)
        Engines.synthesizer = { FakeSynthesizer() }
        Gutenberg.client = null
        Catalogue.reset()
        Engines.httpGet = { url, agent, out, _ ->
            requests += url
            agents += agent
            when {
                url == Catalogue.URL -> listAnswer(out)
                url.endsWith(".epub3.images") -> out.write(book())
                else -> error("unexpected request: $url")
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun restore() {
        Engines.httpGet = Gutenberg::fetch
        Gutenberg.client = null
        Catalogue.reset()
        scenario?.close()
    }

    private val listRequests get() = requests.count { it == Catalogue.URL }

    /** Opens the screen again as after a restart of the app: the list in memory is forgotten, the stored file is not. */
    private fun reopenAfterRestart() {
        compose.onNodeWithTag("find-back").performClick()
        Catalogue.reset()
        Gutenberg.client = null // a new process has no memory of the last failure's wait
        compose.onNodeWithTag("find-books").performClick()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitForTag(tag: String) = compose.waitUntil(30_000) { exists(tag) }

    private fun openFindBooks() {
        compose.onNodeWithTag("find-books").performClick()
        compose.onNodeWithTag("find-notice").assertIsDisplayed()
    }

    @Smoke @Test
    fun searchesByTitleGenreAndPopularityThenAddsTheTappedBookToTheImportQueue() {
        openFindBooks()
        waitForTag("result-4242")
        assertEquals("the list is downloaded once, when the screen opens", listOf(Catalogue.URL), requests.toList())
        assertTrue("the most downloaded English book is first, and a book in French is not offered", exists("result-77") && !exists("result-6"))

        compose.onNodeWithTag("find-query").performTextInput("harbour")
        compose.waitUntil(10_000) { !exists("result-77") }
        compose.onNodeWithTag("result-title-4242", useUnmergedTree = true).assertTextContains("Harbour Notes")
        compose.onNodeWithTag("result-detail-4242", useUnmergedTree = true).assertTextContains("Ada Example · EN · 30,000 downloads")
        compose.onNodeWithTag("find-query").performTextInput("zzz")
        waitForTag("find-empty")
        compose.onNodeWithTag("find-query").performTextReplacement("")

        compose.onNodeWithTag("genre-ADVENTURE").performClick()
        waitForTag("result-90")
        assertTrue("a genre keeps only its books", !exists("result-4242"))
        compose.onNodeWithTag("genre-ADVENTURE").performClick()
        compose.onNodeWithTag("genre-SCIENCE_FICTION").performClick()
        waitForTag("result-4242")
        waitForTag("result-77")

        compose.onNodeWithTag("popularity-VERY_POPULAR").performClick()
        compose.waitUntil(10_000) { !exists("result-77") }
        assertTrue(exists("result-4242"))
        compose.onNodeWithTag("genre-SCIENCE_FICTION").performClick()
        compose.onNodeWithTag("find-english").performScrollTo().performClick()
        waitForTag("result-6")
        assertTrue("English only off also offers the book in French", exists("result-4242"))

        compose.onNodeWithTag("result-4242").performClick()
        waitForTag("result-state-4242")
        compose.waitUntil(120_000) { Library.books.value.singleOrNull()?.status == Status.READY }

        assertEquals("searching sends no request; only the list and the tapped book", listOf(Catalogue.URL, epubLink), requests.toList())
        assertTrue(agents.toString(), agents.all { it.startsWith("Screenplay/") && "github.com/AlexHeavens/Screenplay" in it })
        val added = Library.books.value.single()
        assertEquals("gutenberg:4242", added.source)
        assertEquals("Harbour Notes", added.title)
        assertTrue("the licence text is kept", Library.segments(ctx, added.id).any { "END OF THE PROJECT GUTENBERG EBOOK" in it.text })

        compose.onNodeWithTag("find-back").performClick()
        compose.onNodeWithTag("book-Harbour Notes").assertIsDisplayed()
    }

    @Smoke @Test
    fun searchesTheStoredListWithNoConnectionAfterTheFirstDownload() {
        openFindBooks()
        waitForTag("result-4242")
        listAnswer = { throw CatalogueException("Could not reach the book catalogue. Check the connection and try again.") }

        reopenAfterRestart()
        waitForTag("result-4242")
        compose.onNodeWithTag("find-query").performTextInput("quiet")
        compose.waitUntil(10_000) { !exists("result-4242") }
        assertTrue(exists("result-77"))
        assertEquals("a list stored today is not downloaded again", 1, listRequests)
        assertTrue(!exists("find-failed") && !exists("find-message"))
    }

    @Test
    fun aListOlderThanADayIsReplacedAndAFailedOrUnreadableReplacementKeepsTheOldOne() {
        openFindBooks()
        waitForTag("result-4242")
        val oldBytes = storedList.readBytes()
        fun ageTheStoredList() { storedList.setLastModified(System.currentTimeMillis() - 2 * Catalogue.MAX_AGE_MS) }

        ageTheStoredList()
        listAnswer = { throw CatalogueException("Could not reach the book catalogue. Check the connection and try again.") }
        reopenAfterRestart()
        waitForTag("result-4242")
        compose.waitUntil(30_000) { listRequests == 2 }
        Thread.sleep(500)
        assertTrue("a failed refresh says nothing and the old list stays", exists("result-4242") && !exists("find-failed"))
        assertTrue(oldBytes.contentEquals(storedList.readBytes()))

        listAnswer = { it.write("<html>maintenance</html>".toByteArray()) }
        reopenAfterRestart()
        waitForTag("result-4242")
        compose.waitUntil(30_000) { listRequests == 3 }
        Thread.sleep(500)
        assertTrue("an answer that is not a list is not kept", exists("result-4242") && oldBytes.contentEquals(storedList.readBytes()))
        assertTrue(!File(ctx.filesDir, Catalogue.FILE + ".new").exists())

        listAnswer = { it.write(list("9\tFresh Arrival\tNew, Nia\ten\t40000\t0")) }
        reopenAfterRestart()
        waitForTag("result-9")
        assertEquals(4, listRequests)
        assertTrue("the replacement is stored for the next day", System.currentTimeMillis() - storedList.lastModified() < Catalogue.MAX_AGE_MS)
    }

    @Test
    fun saysWhenTheFirstDownloadFailsAndOffersToTryAgain() {
        listAnswer = { throw CatalogueException("Could not reach the book catalogue. Check the connection and try again.") }
        openFindBooks()
        waitForTag("find-failed")
        compose.onNodeWithTag("find-failed").assertTextContains("Could not reach the book catalogue", substring = true)
        assertTrue(!storedList.exists())
        assertTrue(Library.books.value.isEmpty())

        listAnswer = { it.write(list()) }
        Gutenberg.client = null // the client would ask for a 2 s wait after the failure; a tap that late is another test
        compose.onNodeWithTag("find-retry").performClick()
        waitForTag("result-4242")
        assertTrue(!exists("find-failed"))
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

    private fun barRange(title: String) = compose.onNodeWithTag("stage-bar-DOWNLOAD-$title", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]

    private fun waitForBar(title: String, value: ProgressBarRangeInfo) = compose.waitUntil(30_000) { exists("stage-bar-DOWNLOAD-$title") && barRange(title) == value }

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
        compose.onNodeWithTag("stage-text-DOWNLOAD-Harbour Notes", useUnmergedTree = true).assertTextContains("50% · 1.9/3.8 MB")

        mayFinish.complete(Unit)
        compose.waitUntil(30_000) { !exists("stage-bar-DOWNLOAD-Harbour Notes") }
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
        compose.onNodeWithTag("stage-text-DOWNLOAD-Quiet Shore", useUnmergedTree = true).assertTextContains("50%", substring = true)
        assertTrue("the book being made has its file and shows no download bar", !exists("stage-bar-DOWNLOAD-Harbour Notes"))

        mayFinish.complete(Unit)
        compose.waitUntil(30_000) { !exists("stage-bar-DOWNLOAD-Quiet Shore") }
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
        compose.onNodeWithTag("stage-text-DOWNLOAD-Harbour Notes", useUnmergedTree = true).assertTextContains("1.0 MB")

        mayFinish.complete(Unit)
        compose.waitUntil(30_000) { !exists("stage-bar-DOWNLOAD-Harbour Notes") }
    }
}
