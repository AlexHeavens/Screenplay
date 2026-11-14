/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GutendexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Four books of a real response (`?search=dickens`, 2026-11-14), trimmed of summaries: an audiobook in copyright (9695) and a book with no author (28198). */
    private val sample = File("src/test/resources/gutendex/search-dickens.json").readText()

    @Test
    fun readsTitleAuthorLanguageDownloadsAndEpubOfARealResponse() {
        val page = Gutendex.parse(sample)
        assertEquals(231, page.total)
        assertTrue(page.hasNext)
        assertEquals(
            Gutendex.Entry(98, "A Tale of Two Cities", "Charles Dickens", listOf("en"), 50704, "https://www.gutenberg.org/ebooks/98.epub3.images"),
            page.entries.first(),
        )
        assertEquals("Unknown author", page.entries.single { it.id == 28198 }.author)
    }

    @Test
    fun anEntryWithoutAnEpubIsKeptWithNoLink() {
        val json = """{"count":1,"next":null,"results":[{"id":7,"title":"Text only","authors":[],"languages":["en"],"copyright":false,"media_type":"Text","formats":{"text/html":"https://www.gutenberg.org/ebooks/7.html.images"},"download_count":5}]}"""
        assertNull(Gutendex.parse(json).entries.single().epubUrl)
    }

    @Test
    fun leavesOutTheCopyrightedAudiobooksARealResponseHolds() {
        assertEquals(listOf(98, 1400, 28198), Gutendex.parse(sample).entries.map { it.id })
    }

    @Test
    fun leavesOutBooksInCopyrightAndAnythingNotText() {
        val json = """{"count":3,"next":null,"results":[
            {"id":1,"title":"Kept","authors":[],"languages":["en"],"copyright":null,"media_type":"Text","formats":{},"download_count":5},
            {"id":2,"title":"Copyrighted","authors":[],"languages":["en"],"copyright":true,"media_type":"Text","formats":{},"download_count":5},
            {"id":3,"title":"Audio","authors":[],"languages":["en"],"copyright":false,"media_type":"Sound","formats":{},"download_count":5}]}"""
        val page = Gutendex.parse(json)
        assertEquals(listOf("Kept"), page.entries.map { it.title })
        assertFalse(page.hasNext)
    }

    @Test
    fun choosesTheEpubFormatAndNothingElse() {
        fun pick(formats: String) = Gutendex.epubUrl(JSONObject(formats))
        assertEquals("https://www.gutenberg.org/ebooks/1.epub3.images", pick("""{"text/html":"https://www.gutenberg.org/ebooks/1.html","application/epub+zip":"https://www.gutenberg.org/ebooks/1.epub3.images"}"""))
        assertNull("no EPUB among the formats", pick("""{"text/html":"https://www.gutenberg.org/ebooks/1.html"}"""))
        assertNull("no formats at all", Gutendex.epubUrl(null))
        assertNull("a link off gutenberg.org is never fetched", pick("""{"application/epub+zip":"https://example.com/1.epub"}"""))
        assertNull("plain http is never fetched", pick("""{"application/epub+zip":"http://www.gutenberg.org/ebooks/1.epub"}"""))
        assertNull("a look-alike host is not gutenberg.org", pick("""{"application/epub+zip":"https://evilgutenberg.org/1.epub"}"""))
    }

    @Test
    fun turnsFiltersIntoTheCatalogueRequest() {
        assertEquals("https://gutendex.com/books/?languages=en&sort=popular", Gutendex.url(Gutendex.Query()))
        assertEquals(
            "https://gutendex.com/books/?search=jules+verne&topic=science+fiction&languages=en&sort=popular&page=2",
            Gutendex.url(Gutendex.Query(" jules verne ", Gutendex.Genre.SCIENCE_FICTION, englishOnly = true, page = 2)),
        )
        assertEquals("https://gutendex.com/books/?topic=children&sort=popular", Gutendex.url(Gutendex.Query(genre = Gutendex.Genre.CHILDREN, englishOnly = false)))
        assertEquals("https://gutendex.com/books/?search=caf%C3%A9+%26+tea&languages=en&sort=popular", Gutendex.url(Gutendex.Query("café & tea")))
    }

    @Test
    fun everyGenreIsOneOfTheNineOffered() {
        assertEquals(
            listOf("Fiction", "Science fiction", "Mystery", "Adventure", "Poetry", "Drama", "History", "Philosophy", "Children's"),
            Gutendex.Genre.values().map { it.label },
        )
    }

    @Test
    fun popularityKeepsBooksDownloadedAtLeastTheLimitOften() {
        val entries = Gutendex.parse(sample).entries
        assertEquals(listOf(98, 1400), Gutendex.filter(entries, Gutendex.Popularity.VERY_POPULAR).map { it.id })
        assertEquals(entries.size, Gutendex.filter(entries, Gutendex.Popularity.ANY).size)
        assertEquals(listOf(98, 1400), Gutendex.filter(entries, Gutendex.Popularity.POPULAR).map { it.id })
        assertEquals("a book on the limit is kept", 1, Gutendex.filter(listOf(Gutendex.Entry(1, "t", "a", emptyList(), 20_000, null)), Gutendex.Popularity.VERY_POPULAR).size)
    }

    @Test
    fun offersMoreResultsOnlyWhileTheLastBookIsStillPopularEnough() {
        val page = Gutendex.parse(sample)
        assertTrue(Gutendex.hasMore(page, Gutendex.Popularity.ANY))
        assertFalse("the last book has fewer than 20,000 downloads and the list is sorted", Gutendex.hasMore(page, Gutendex.Popularity.VERY_POPULAR))
        assertFalse(Gutendex.hasMore(page.copy(hasNext = false), Gutendex.Popularity.ANY))
    }

    @Test
    fun theUserAgentNamesTheAppItsVersionAndWhereToAskAboutIt() {
        assertEquals("Screenplay/1.0 (+https://github.com/AlexHeavens/Screenplay)", Gutendex.userAgent("1.0"))
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
    fun cachesAnAnswerUntilItExpiresAndForgetsTheOldestPastItsCapacity() {
        val cache = ResponseCache<String>(ttlMs = 1_000, capacity = 2)
        cache.put("a", "A", now = 0)
        assertEquals("A", cache.get("a", now = 999))
        assertNull(cache.get("a", now = 1_000))
        cache.put("b", "B", 0); cache.put("c", "C", 0)
        assertNull("a was the least recently used", cache.get("a", 0))
        assertEquals("C", cache.get("c", 0))
    }

    @Test
    fun theClientAsksOnceForARepeatedSearchAndWaitsOutAFailureWithoutAsking() = runBlocking {
        var asked = 0
        var clock = 0L
        val saved = Engines.httpGet
        val client = CatalogueClient("Screenplay/test", now = { clock })
        try {
            Engines.httpGet = { _, agent, out ->
                assertEquals("Screenplay/test", agent)
                asked++
                out.write(sample.toByteArray())
            }
            client.search(Gutendex.Query("dickens"))
            client.search(Gutendex.Query("dickens"))
            assertEquals("the second search comes from the cache", 1, asked)

            Engines.httpGet = { _, _, _ -> asked++; throw CatalogueException("down") }
            clock = 400_000
            try { client.search(Gutendex.Query("poe")); error("expected a failure") } catch (e: CatalogueException) { assertEquals("down", e.message) }
            val before = asked
            try { client.search(Gutendex.Query("wells")); error("expected a wait") } catch (e: CatalogueException) { assertTrue(e.message!!, e.message!!.startsWith("Please wait 2 seconds")) }
            assertEquals("no request is sent while waiting", before, asked)
            clock += 2_000
            try { client.search(Gutendex.Query("wells")) } catch (_: CatalogueException) {}
            assertEquals("after the wait it asks again", before + 1, asked)
        } finally {
            Engines.httpGet = saved
        }
    }

    @Test
    fun aBookWithoutAnEpubIsNeverRequestedAndAFailedDownloadLeavesNoFile() = runBlocking {
        val saved = Engines.httpGet
        val client = CatalogueClient("Screenplay/test")
        val file = tmp.newFile("book.epub")
        try {
            Engines.httpGet = { _, _, _ -> error("no request expected") }
            try { client.download(Gutendex.Entry(1, "Lost", "a", emptyList(), 1, null), file); error("expected a refusal") } catch (e: CatalogueException) { assertTrue(e.message!!, "no EPUB" in e.message!!) }

            Engines.httpGet = { _, _, out -> out.write(1); throw CatalogueException("cut off") }
            try { client.download(Gutendex.Entry(1, "Cut", "a", emptyList(), 1, "https://www.gutenberg.org/ebooks/1.epub3.images"), file); error("expected a failure") } catch (e: CatalogueException) {}
            assertFalse(file.exists())
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
