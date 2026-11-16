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
        assertEquals(listOf(1, 2, 6, 4, 7, 5), ids(CatalogueQuery(language = "en", sort = Sort.DOWNLOADS)))
    }

    @Test
    fun aRowShowsTheTitleTheAuthorsInReadingOrderTheLanguagesAndTheDownloads() {
        val entries = data.search(CatalogueQuery(), 50).entries.associateBy { it.id }
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
        assertEquals(listOf(2, 1, 4), ids(CatalogueQuery(language = "en", genre = Genre.FICTION)))
        assertEquals(listOf(5), ids(CatalogueQuery(genre = Genre.POETRY)))
        assertEquals(listOf(6), ids(CatalogueQuery(genre = Genre.DRAMA)))
        assertEquals(listOf(6), ids(CatalogueQuery(genre = Genre.CHILDREN)))
        assertEquals(listOf(3, 1, 2, 4).sorted(), ids(CatalogueQuery(genre = Genre.FICTION)).sorted())
    }

    @Test
    fun everyGenreIsOneOfTheTwentyOneOffered() {
        assertEquals(
            listOf(
                "Fiction", "Science fiction", "Mystery", "Adventure", "Poetry", "Drama", "History", "Philosophy", "Children's",
                "Biography", "Essays and speeches", "Historical novels", "Humour", "Short stories", "Religion and spirituality", "Travel",
                "Mythology and folklore", "Warfare", "Romance", "How-to", "Nature",
            ),
            Genre.values().map { it.label },
        )
        assertTrue("one bit each in an Int", Genre.values().size <= Int.SIZE_BITS - 1)
    }

    /** Tags as the published catalogue spells them: each new genre is found by its category tag and by the subject tags next to it. */
    @Test
    fun eachNewGenreKeepsTheBooksOfItsCategoryAndItsSubjectsAndNoUnrelatedOnes() {
        val tags = listOf(
            "Category: Crime, Thrillers and Mystery", "Category: Biographies", "Biography", "Category: Essays, Letters & Speeches", "Category: Historical Novels",
            "Category: Humour", "Category: Short Stories", "Short stories", "Category: Religion/Spirituality", "Category: Travel Writing", "Description and travel",
            "Category: Mythology, Legends & Folklore", "Category: History - Warfare", "Category: Romance", "Category: How To ...", "Category: Nature/Gardening/Animals",
            "Category: Novels",
        )
        val books = listOf(
            "10\tA Crime\tA, A\ten\t1\t0", "11\tA Life\tA, A\ten\t1\t1", "12\tAnother Life\tA, A\ten\t1\t2", "13\tOn Things\tA, A\ten\t1\t3",
            "14\tOld Days\tA, A\ten\t1\t4", "15\tJokes\tA, A\ten\t1\t5", "16\tTales\tA, A\ten\t1\t6", "17\tMore Tales\tA, A\ten\t1\t7",
            "18\tFaith\tA, A\ten\t1\t8", "19\tAbroad\tA, A\ten\t1\t9", "20\tElsewhere\tA, A\ten\t1\t10", "21\tGods\tA, A\ten\t1\t11",
            "22\tBattles\tA, A\ten\t1\t12", "23\tLove\tA, A\ten\t1\t13", "24\tDo It\tA, A\ten\t1\t14", "25\tBirds\tA, A\ten\t1\t15",
            "26\tJust a Novel\tA, A\ten\t1\t16",
        )
        val file = (listOf("catalogue-format 1 build 2026-11-14", "tags\t" + tags.joinToString("\t")) + books).joinToString("\n", postfix = "\n")
        val catalogue = CatalogueData.parse(ByteArrayInputStream(xz(file)))
        fun of(genre: Genre) = catalogue.search(CatalogueQuery(genre = genre), 50).entries.map { it.id }.sorted()
        assertEquals("the Crime category is Mystery", listOf(10), of(Genre.MYSTERY))
        assertEquals(listOf(11, 12), of(Genre.BIOGRAPHY))
        assertEquals(listOf(13), of(Genre.ESSAYS))
        assertEquals(listOf(14), of(Genre.HISTORICAL_NOVELS))
        assertEquals(listOf(15), of(Genre.HUMOUR))
        assertEquals(listOf(16, 17), of(Genre.SHORT_STORIES))
        assertEquals(listOf(18), of(Genre.RELIGION))
        assertEquals(listOf(19, 20), of(Genre.TRAVEL))
        assertEquals(listOf(21), of(Genre.MYTHOLOGY))
        assertEquals(listOf(22), of(Genre.WARFARE))
        assertEquals(listOf(23), of(Genre.ROMANCE))
        assertEquals(listOf(24), of(Genre.HOW_TO))
        assertEquals(listOf(25), of(Genre.NATURE))
        assertEquals("a book of no genre in the list is in none", emptyList<Int>(), Genre.values().filter { 26 in of(it) })
    }

    @Test
    fun aLanguageKeepsTheBooksInItAndABookInTwoLanguagesIsInBoth() {
        assertEquals(listOf(7, 6, 2, 1, 5, 4), ids(CatalogueQuery(language = "en")))
        assertEquals(listOf(6, 3), ids(CatalogueQuery(language = "fr")))
        assertEquals("no language searches every language", 7, ids(CatalogueQuery()).size)
        assertEquals(emptyList<Int>(), ids(CatalogueQuery(language = "e")))
    }

    @Test
    fun theLanguagesAreCountedByBooksMostFirst() {
        assertEquals(listOf(LanguageCount("en", 6), LanguageCount("fr", 2)), data.languages)
    }

    @Test
    fun theLanguageIsAllUntilChosenAndAChoiceIsKeptWhileTheListHasIt() {
        assertEquals("nothing chosen is All", null, CatalogueLanguage.effective(null, listOf("en", "fr")))
        assertEquals("All is a choice", null, CatalogueLanguage.effective(CatalogueLanguage.ALL, listOf("en", "fr")))
        assertEquals("fr", CatalogueLanguage.effective("fr", listOf("en", "fr")))
        assertEquals("a chosen language the list lost falls back to All", null, CatalogueLanguage.effective("de", listOf("en", "fr")))
    }

    @Test
    fun theMenuOrdersLanguagesByBookCountThenNameAndNamesThemInTheSystemLanguage() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            assertEquals("French", CatalogueLanguage.name("fr"))
            assertEquals("XYZ", CatalogueLanguage.name("xyz"))
            assertEquals(listOf("fr", "de", "en"), CatalogueLanguage.ordered(listOf(LanguageCount("fr", 5), LanguageCount("en", 1), LanguageCount("de", 5))).map { it.code })
            java.util.Locale.setDefault(java.util.Locale.FRENCH)
            assertEquals("Anglais", CatalogueLanguage.name("en"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun theDefaultOrderIsByTitleAndTheDownloadsSortIsMostDownloadedFirst() {
        assertEquals("by title, ignoring case and accents", listOf(7, 6, 2, 1, 5, 4), ids(CatalogueQuery(language = "en")))
        assertEquals(listOf(1, 2, 6, 4, 7, 5), ids(CatalogueQuery(language = "en", sort = Sort.DOWNLOADS)))
        assertEquals("the sort applies to the books that match", listOf(1, 2, 4), ids(CatalogueQuery(language = "en", genre = Genre.FICTION, sort = Sort.DOWNLOADS)))
        assertEquals("equal downloads are in the order of their numbers", listOf(1, 2, 3, 6, 4, 7, 5), ids(CatalogueQuery(sort = Sort.DOWNLOADS)))
        assertEquals(listOf(7, 6, 2, 3, 1, 5, 4), ids(CatalogueQuery()))
    }

    @Test
    fun theResultCarriesTheFirstBooksAndHowManyMatchedInAll() {
        val first = data.search(CatalogueQuery(language = "en", sort = Sort.DOWNLOADS), 2)
        assertEquals(listOf(1, 2), first.entries.map { it.id })
        assertEquals(6, first.total)
        assertEquals(0, data.search(CatalogueQuery(text = "zzz"), 2).total)
    }

    @Test
    fun aFileInAnotherFormatIsRefusedWithAMessageForTheReader() {
        for (text in listOf("catalogue-format 3 build 2026-11-14\ntags\n", "<html>maintenance</html>\n", "catalogue-format 1 build 2026-11-14\ntags\tFiction\n", "catalogue-format 1 build 2026-11-14\ntags\tFiction\n1\ttoo\tfew\n")) {
            try { CatalogueData.parse(ByteArrayInputStream(xz(text))); fail("expected a refusal for: $text") } catch (e: CatalogueException) {
                assertEquals("The book list is not in a form this app can read. Updating the app may fix this.", e.message)
            }
        }
    }

    @Test
    fun aTagNumberOutsideTheTagLineIsRefused() {
        try { CatalogueData.parse(ByteArrayInputStream(xz("catalogue-format 1 build 2026-11-14\ntags\tFiction\n1\tT\tA, B\ten\t5\t9\n"))); fail("expected a refusal") } catch (e: CatalogueException) {}
    }

    /** Format 2 adds `rating x10` and `rating count` (both empty when unrated). 11 has 4.5 of 20, 12 has 3.8 of 100, 13 one 5-star vote, 14 three ratings, 15 none. */
    private val rated = listOf(
        "catalogue-format 2 build 2026-11-15",
        "tags\tFiction",
        "11\tAlpha\tA, A\ten\t100\t0\t45\t20",
        "12\tBravo\tB, B\ten\t200\t0\t38\t100",
        "13\tCharlie\tC, C\ten\t50\t0\t50\t1",
        "14\tDelta\tD, D\ten\t300\t0\t40\t3",
        "15\tEcho\tE, E\ten\t400\t0\t\t",
    ).joinToString("\n", postfix = "\n")

    private val ratedData get() = CatalogueData.parse(ByteArrayInputStream(xz(rated)))

    @Test
    fun aFormat2RowCarriesItsRatingAndAFormat1RowHasNone() {
        val entries = ratedData.search(CatalogueQuery(), 50).entries.associateBy { it.id }
        assertEquals(4.5, entries[11]!!.rating!!, 0.0)
        assertEquals(20, entries[11]!!.ratingCount)
        assertEquals(null, entries[15]!!.rating)
        assertTrue("the first file format is still read", data.search(CatalogueQuery(), 50).entries.all { it.rating == null })
    }

    @Test
    fun theRatingSortRanksByAverageAndLeavesBooksWithFewRatingsToTheEndByDownloads() {
        // 11 (4.5) before 12 (3.8). 13 (one 5-star vote), 14 (three ratings) and 15 (none) have under 5 ratings, so they follow by downloads.
        assertEquals(listOf(11, 12, 15, 14, 13), ratedData.search(CatalogueQuery(sort = Sort.RATING), 50).entries.map { it.id })
    }

    @Test
    fun aRatingOutsideOneToFiveStarsOrWithoutACountIsRefused() {
        for (row in listOf("1\tT\tA, B\ten\t5\t\t60\t2", "1\tT\tA, B\ten\t5\t\tx\t2", "1\tT\tA, B\ten\t5\t\t40\tmany", "1\tT\tA, B\ten\t5\t")) {
            try { CatalogueData.parse(ByteArrayInputStream(xz("catalogue-format 2 build 2026-11-15\ntags\tFiction\n$row\n"))); fail("expected a refusal: $row") } catch (e: CatalogueException) {}
        }
    }
}
