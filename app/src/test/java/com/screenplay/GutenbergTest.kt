/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GutenbergTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun theUserAgentNamesTheAppItsVersionAndWhereToAskAboutIt() {
        assertEquals("Screenplay/1.0 (+https://github.com/AlexHeavens/Screenplay)", Gutenberg.userAgent("1.0"))
    }

    /** Probes with [answers] (a site is up when its address is in it), as the screen does when it opens. */
    private fun probeWith(answers: Set<String>) = runBlocking {
        val saved = Engines.httpProbe
        Gutenberg.forgetProbe()
        try {
            Engines.httpProbe = { url, _ -> url in answers }
            Gutenberg.probe("Screenplay/test", maxAgeMs = 0)
        } finally {
            Engines.httpProbe = saved
        }
    }

    private val mainEpub = Gutenberg.epubUrl(84)
    private val mirrorEpub = "https://gutenberg.pglaf.org/cache/epub/84/pg84-images-3.epub"

    @Test
    fun aBookComesFromGutenbergWhileItAnswers() {
        probeWith(setOf(Gutenberg.SITE_URL, Gutenberg.MIRROR_URL))
        assertEquals(mainEpub, Gutenberg.routed(mainEpub))
    }

    @Test
    fun aBookComesFromTheMirrorWhenGutenbergIsDownAndTheMirrorAnswers() {
        probeWith(setOf(Gutenberg.MIRROR_URL))
        assertEquals(mirrorEpub, Gutenberg.routed(mainEpub))
    }

    @Test
    fun aBookStillAsksGutenbergWhenNeitherSiteAnswersSoTheReaderSeesTheUsualError() {
        probeWith(emptySet())
        assertEquals(mainEpub, Gutenberg.routed(mainEpub))
    }

    @Test
    fun aBookStillAsksGutenbergWhenOnlyTheMirrorIsDownOrNoProbeWasMade() {
        probeWith(setOf(Gutenberg.SITE_URL))
        assertEquals(mainEpub, Gutenberg.routed(mainEpub))
        Gutenberg.forgetProbe()
        assertEquals(mainEpub, Gutenberg.routed(mainEpub))
    }

    @Test
    fun onlyABooksEpubLinkIsSentToTheMirror() {
        probeWith(setOf(Gutenberg.MIRROR_URL))
        assertEquals(Catalogue.URL, Gutenberg.routed(Catalogue.URL))
        assertEquals("https://www.gutenberg.org/ebooks/84.epub3.images.evil", Gutenberg.routed("https://www.gutenberg.org/ebooks/84.epub3.images.evil"))
    }

    @Test
    fun aProbeThatIsRecentEnoughIsNotRepeated() = runBlocking {
        val saved = Engines.httpProbe
        Gutenberg.forgetProbe()
        val asked = mutableListOf<String>()
        try {
            Engines.httpProbe = { url, _ -> asked += url; true }
            Gutenberg.probe("Screenplay/test", maxAgeMs = 60_000)
            Gutenberg.probe("Screenplay/test", maxAgeMs = 60_000)
            assertEquals("one HEAD request for each site, once", listOf(Gutenberg.SITE_URL, Gutenberg.MIRROR_URL), asked.sorted().reversed())
            Gutenberg.probe("Screenplay/test", maxAgeMs = 0)
            assertEquals("a screen opening probes again", 4, asked.size)
        } finally {
            Engines.httpProbe = saved
            Gutenberg.forgetProbe()
        }
    }

    @Test
    fun waitsAfterAFailureAndDoublesTheWaitUpToALimit() {
        val backoff = Backoff(baseMs = 2_000, maxMs = 60_000)
        assertEquals(0, backoff.waitMs(0))
        backoff.failed(now = 1_000)
        assertEquals(2_000, backoff.waitMs(1_000))
        assertEquals(500, backoff.waitMs(2_500))
        backoff.failed(now = 3_000)
        assertEquals(4_000, backoff.waitMs(3_000))
        repeat(20) { backoff.failed(now = 0) }
        assertEquals(60_000, backoff.waitMs(0))
        backoff.succeeded()
        assertEquals(0, backoff.waitMs(0))
    }

    @Test
    fun followsTheServersRetryAfterButNeverWaitsMoreThanTheLimit() {
        val backoff = Backoff(maxMs = 60_000)
        backoff.failed(now = 0, retryAfterMs = 30_000)
        assertEquals(30_000, backoff.waitMs(0))
        backoff.failed(now = 0, retryAfterMs = 3_600_000)
        assertEquals(60_000, backoff.waitMs(0))
    }

    @Test
    fun aFailedDownloadLeavesNoFileAndAnEpubIsOnlyEverTheGutenbergLinkForTheBookId() = runBlocking {
        val saved = Engines.httpGet
        val client = CatalogueClient("Screenplay/test")
        val file = java.io.File(tmp.root, "book.epub")
        try {
            assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", Gutenberg.epubUrl(1342))
            Engines.httpGet = { _, _, out, _ -> out.write(1); throw CatalogueException("cut off") }
            try { client.download(Gutenberg.epubUrl(1), file); error("expected a failure") } catch (e: CatalogueException) {}
            assertFalse("a cut-off download never becomes the book's file", file.exists())
            assertFalse("and leaves no temporary file", java.io.File(tmp.root, "book.epub.part").exists())
        } finally {
            Engines.httpGet = saved
        }
    }

    @Test
    fun aDownloadReportsTheBytesReceivedAndTheAnnouncedLengthAndKeepsTheFileOnlyWhenWhole() = runBlocking {
        val saved = Engines.httpGet
        val file = File(tmp.root, "book.epub")
        val heard = mutableListOf<Pair<Long, Long>>()
        try {
            Engines.httpGet = { _, _, out, onProgress ->
                out.write(ByteArray(10)); onProgress(10, 30)
                assertFalse("the file is not the book until whole", file.exists())
                out.write(ByteArray(20)); onProgress(30, 30)
            }
            CatalogueClient("Screenplay/test").download("https://www.gutenberg.org/ebooks/1.epub3.images", file) { received, expected -> heard += received to expected }
            assertEquals(listOf(10L to 30L, 30L to 30L), heard)
            assertEquals(30L, file.length())
        } finally {
            Engines.httpGet = saved
        }
    }

    @Test
    fun theProjectGutenbergLicenceSurvivesReadingAndWritingTheBook() {
        val licence = "*** END OF THE PROJECT GUTENBERG EBOOK TINY ***"
        val terms = "This eBook is for the use of anyone anywhere in the United States and most other parts of the world at no cost and with almost no restrictions whatsoever."
        val epub = tmp.newFile("tiny.epub")
        val xhtml = { body: String -> """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>""" }
        ZipOutputStream(epub.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="c.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("c.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Tiny</dc:title><dc:creator>Ann Author</dc:creator></metadata>
                <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="l" href="l.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/><itemref idref="l"/></spine></package>""")
            put("a.xhtml", xhtml("<h2>One</h2><p>Some story.</p>"))
            put("l.xhtml", xhtml("<h2>Licence</h2><p>$licence</p><p>$terms</p>"))
        }
        val book = Epub.read(epub)
        assertTrue(book.paragraphs.toString(), licence in book.paragraphs && terms in book.paragraphs)
        val written = EpubWriter.chapters(book.paragraphs.map { Segment(0, 0, it) }, book.chapters, book.title).flatMap { it.paragraphs }
        assertTrue(written.toString(), licence in written && terms in written)
    }
}
