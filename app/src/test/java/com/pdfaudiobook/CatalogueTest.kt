/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.tukaani.xz.XZOutputStream

/** The book list file (see scripts/build-catalogue.py for the format) and searching it. */
class CatalogueTest {
    private fun xz(text: String) = ByteArrayOutputStream().also { out -> XZOutputStream(out, org.tukaani.xz.LZMA2Options()).use { it.write(text.toByteArray()) } }.toByteArray()

    /** Tags: 0 Science Fiction, 1 Mystery Fiction, 2 Detective and mystery stories, 3 Fiction, 4 Poetry, 5 Children's Literature, 6 Plays/Films/Dramas. */
    private val sample = listOf(
        "catalogue-format 1 build 2026-11-14",
        "tags\tScience Fiction\tMystery Fiction\tDetective and mystery stories\tFiction\tPoetry\tChildren's Literature\tPlays/Films/Dramas",
        "1\tPride and Prejudice\tAusten, Jane\ten\t208430\t3",
        "2\tFrankenstein; Or, The Modern Prometheus\tShelley, Mary Wollstonecraft\ten\t90000\t0,3",
        "3\tLe Comte de Monte-Cristo\tDumas, Alexandre\tfr\t30000\t3",
        "4\tThe Moonstone\tCollins, Wilkie\ten\t25000\t1,2",
        "5\tQuiet Shore\tExample, Ada; Other, Bob\ten\t3000\t4",
        "6\tCafé Notes\tMüller, Zoë\ten,fr\t30000\t5,6",
        "7\tA Tale Without Tags\t\ten\t6000\t",
    ).joinToString("\n", postfix = "\n")

    private val data get() = CatalogueData.parse(ByteArrayInputStream(xz(sample)))

    private fun ids(query: CatalogueQuery, limit: Int = 50) = data.search(query, limit).entries.map { it.id }

    @Test
    fun readsTheVersionAndEveryBookMostDownloadedFirst() {
        assertEquals("2026-11-14", data.version)
        assertEquals(7, data.size)
        assertEquals(listOf(1, 2, 6, 4, 7, 5), ids(CatalogueQuery()))
    }

    @Test
    fun aRowShowsTheTitleTheAuthorsInReadingOrderTheLanguagesAndTheDownloads() {
        val entries = data.search(CatalogueQuery(englishOnly = false), 50).entries.associateBy { it.id }
        assertEquals(CatalogueEntry(1, "Pride and Prejudice", "Jane Austen", listOf("en"), 208430), entries[1])
        assertEquals("Ada Example & Bob Other", entries[5]!!.author)
        assertEquals(listOf("en", "fr"), entries[6]!!.languages)
        assertEquals("Unknown author", entries[7]!!.author)
        assertEquals("https://www.gutenberg.org/ebooks/1.epub3.images", entries[1]!!.epubUrl)
    }

    @Test
    fun everyWordMustMatchTheTitleOrAnAuthorIgnoringCaseAndAccents() {
        assertEquals(listOf(2), ids(CatalogueQuery(text = "shelley frankenstein")))
        assertEquals(listOf(2), ids(CatalogueQuery(text = "MARY prometheus")))
        assertEquals("a word that matches only the title and one that matches only the author", emptyList<Int>(), ids(CatalogueQuery(text = "pride collins")))
        assertEquals("accents are ignored in the list and in the words", listOf(6), ids(CatalogueQuery(text = "cafe muller zoe")))
        assertEquals(listOf(6), ids(CatalogueQuery(text = "Café")))
    }

    @Test
    fun aGenreMatchesTheBooksBookshelvesAndSubjects() {
        assertEquals(listOf(2), ids(CatalogueQuery(genre = Genre.SCIENCE_FICTION)))
        assertEquals("a subject, not only a bookshelf", listOf(4), ids(CatalogueQuery(genre = Genre.MYSTERY)))
        assertEquals(listOf(1, 2, 4), ids(CatalogueQuery(genre = Genre.FICTION)))
        assertEquals(listOf(5), ids(CatalogueQuery(genre = Genre.POETRY)))
        assertEquals(listOf(6), ids(CatalogueQuery(genre = Genre.DRAMA)))
        assertEquals(listOf(6), ids(CatalogueQuery(genre = Genre.CHILDREN)))
        assertEquals(listOf(3, 1, 2, 4).sorted(), ids(CatalogueQuery(genre = Genre.FICTION, englishOnly = false)).sorted())
    }

    @Test
    fun everyGenreIsOneOfTheNineOffered() {
        assertEquals(
            listOf("Fiction", "Science fiction", "Mystery", "Adventure", "Poetry", "Drama", "History", "Philosophy", "Children's"),
            Genre.values().map { it.label },
        )
    }

    @Test
    fun englishOnlyLeavesOutABookWithNoEnglishAndTheSwitchTurnsItOff() {
        assertTrue(3 !in ids(CatalogueQuery()))
        assertEquals("a book in English and French is English", true, 6 in ids(CatalogueQuery()))
        assertTrue(3 in ids(CatalogueQuery(englishOnly = false)))
    }

    @Test
    fun popularityKeepsBooksDownloadedAtLeastTheLimitOften() {
        assertEquals(listOf(1, 2, 6, 4, 7), ids(CatalogueQuery(popularity = Popularity.POPULAR)))
        assertEquals(listOf(1, 2, 6, 4), ids(CatalogueQuery(popularity = Popularity.VERY_POPULAR)))
        assertEquals("a book on the limit is kept", listOf(1, 2, 3, 6, 4).sorted(), ids(CatalogueQuery(popularity = Popularity.VERY_POPULAR, englishOnly = false)).sorted())
    }

    @Test
    fun theResultCarriesTheFirstBooksAndHowManyMatchedInAll() {
        val first = data.search(CatalogueQuery(), 2)
        assertEquals(listOf(1, 2), first.entries.map { it.id })
        assertEquals(6, first.total)
        assertEquals(0, data.search(CatalogueQuery(text = "zzz"), 2).total)
    }

    @Test
    fun aFileInAnotherFormatIsRefusedWithAMessageForTheReader() {
        for (text in listOf("catalogue-format 2 build 2026-11-14\ntags\n", "<html>maintenance</html>\n", "catalogue-format 1 build 2026-11-14\ntags\tFiction\n", "catalogue-format 1 build 2026-11-14\ntags\tFiction\n1\ttoo\tfew\n")) {
            try { CatalogueData.parse(ByteArrayInputStream(xz(text))); fail("expected a refusal for: $text") } catch (e: CatalogueException) {
                assertEquals("The book list is not in a form this app can read. Updating the app may fix this.", e.message)
            }
        }
    }

    @Test
    fun aTagNumberOutsideTheTagLineIsRefused() {
        try { CatalogueData.parse(ByteArrayInputStream(xz("catalogue-format 1 build 2026-11-14\ntags\tFiction\n1\tT\tA, B\ten\t5\t9\n"))); fail("expected a refusal") } catch (e: CatalogueException) {}
    }
}
