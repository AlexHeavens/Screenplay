/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContentsPagesTest {
    private val headed = listOf(
        "CONTENTS",
        "Introduction ........ 1",
        "Chapter 1 The Duties of a Prince 41",
        "Chapter 2 On Hereditary Principalities 57",
        "Chapter 3 Mixed Principalities 63",
    )

    /** The next page of a list that ran over: no heading, entries all the way down, page numbers rising. */
    private val continued = listOf(
        "Chapter 12 On Mercenaries 120",
        "Chapter 13 On Auxiliaries 131",
        "Chapter 14 What a Prince Owes to War 140",
        "Chapter 15 On Praise and Blame 147",
        "Chapter 16 On Liberality 152",
        "Chapter 17 On Cruelty and Clemency 158",
        "Chapter 18 On Keeping Faith 165",
    )

    private val prose = listOf(
        "The Venetians had called the French into Italy to win half of Lombardy for themselves, and King Louis was the first to answer.",
        "He made five mistakes in Italy: he destroyed the weaker powers, enlarged the strength of one already strong, and brought in a foreign prince.",
        "A little later he divided the kingdom of Naples with the King of Spain, and so created a rival where he had held the field alone.",
        "Had he kept to the pact, the Pope would have found no cause to turn against him.",
        "For these reasons the French King lost Lombardy, and a ruler who follows the same road will meet the same end.",
        "It is a natural thing that those who can will acquire, and when they do so they will be praised, not blamed.",
    )

    @Test fun aHeadingAndEntriesWithPageNumbersIsAContentsPage() = assertTrue(ContentsPages.isPage(headed))

    @Test fun theHeadingIsReadThroughOcrPunctuationCaseAndARunningHeadsPageNumber() {
        for (h in listOf("Table of Contents", "CONTENTS.", "- Contents -", "C O N T E N T S", "vi cONTENTS.", "CONTENTS ix", "CONTENTS OF THE FIRST VOLUME")) {
            assertTrue(h, ContentsPages.isHeading(h))
        }
        for (h in listOf("Contented", "Index", "List of Illustrations", "Contents of a prince's heart were never known to any man living")) assertFalse(h, ContentsPages.isHeading(h))
    }

    @Test fun entriesHaveTheShapeOfALineThatEndsInAPageOrOpensWithALabel() {
        for (e in listOf("Introduction ........ 1", "Preface . . . . . vii", "The Duties of a Prince 41", "Chapter III", "3 The Duties of a Prince", "IV. Of Mercenaries", "Variation under nature, 44-59")) {
            assertTrue(e, ContentsPages.isEntry(e))
        }
        for (e in listOf("He paid the army and the army served him.", "A civil", "CONTENTS", "x".repeat(200) + " 41")) assertFalse(e, ContentsPages.isEntry(e))
    }

    @Test fun aHeadingWithTooFewEntriesIsNotEnough() {
        assertNull(ContentsPages.find(listOf("Contents", "Introduction ... 1", "Chapter 1 The Duties of a Prince 41") + prose))
    }

    @Test fun anIndexOrAListOfIllustrationsHasNoContentsHeading() {
        val illustrations = listOf("List of Illustrations", "The Castle ........ 12", "The Bridge ........ 19", "The Tower ........ 24", "The Gate ........ 31")
        assertFalse(ContentsPages.isPage(illustrations))
        val index = listOf("Index", "Abbot, 14, 67", "Alva, 202", "Anjou, 33, 91", "Aragon, 120")
        assertFalse(ContentsPages.isPage(index))
    }

    @Test fun anIndexPageContinuedIsNotAContentsPageEvenAfterOne() {
        // Entries by shape, but the numbers do not rise: an index, not a list in page order.
        val index = listOf("Abbot, 140", "Alva, 202", "Anjou, 33", "Aragon, 120", "Armada, 17", "Austria, 91", "Aversa, 5")
        assertFalse(ContentsPages.isPage(index, continuation = true, headless = true))
    }

    @Test fun aPageWithoutAHeadingThatFollowsAContentsPageIsItsContinuation() {
        assertTrue(ContentsPages.isPage(continued, continuation = true, headless = true))
    }

    @Test fun theHeadinglessRouteIsOffByDefault() {
        assertFalse(ContentsPages.HEADLESS)
        assertFalse(ContentsPages.isPage(continued, continuation = true))
    }

    @Test fun aPageWithoutAHeadingIsNotContentsUnlessThePageBeforeWas() = assertFalse(ContentsPages.isPage(continued, continuation = false, headless = true))

    @Test fun aListThatFollowsAContentsPageButIsHeadedByAnotherTitleIsNotItsContinuation() {
        val next = listOf("List of Illustrations") + continued
        assertFalse(ContentsPages.isPage(next, continuation = true, headless = true))
    }

    @Test fun aNormalChapterPageIsNotContents() {
        assertFalse(ContentsPages.isPage(prose))
        assertFalse(ContentsPages.isPage(listOf("Chapter 3 The Duties of a Prince") + prose))
        assertFalse(ContentsPages.isPage(prose, continuation = true, headless = true))
    }

    @Test fun aPageThatOnlyStartsWithAContentsListIsAPageOfText() {
        assertFalse(ContentsPages.isPage(headed + prose + prose))
    }

    @Test fun aListInAFileOfProseEndsWhereTheProseStarts() {
        val range = ContentsPages.find(listOf("Title page") + headed + prose)
        assertEquals(1..headed.size, range)
    }

    @Test fun entriesWithoutPageNumbersAreFoundByTheirChapterLabelsAndTheTitlesAmongThem() {
        val gutenberg = listOf("The Prince", "Contents", "INTRODUCTION", "DEDICATION", "THE PRINCE", "CHAPTER I. HOW MANY KINDS OF PRINCIPALITIES THERE ARE", "CHAPTER II. CONCERNING HEREDITARY PRINCIPALITIES", "CHAPTER III. CONCERNING MIXED PRINCIPALITIES", "THE LIFE OF CASTRUCCIO CASTRACANI OF LUCCA")
        assertEquals(1..gutenberg.lastIndex, ContentsPages.find(gutenberg, maxHeading = 40))
    }

    @Test fun findsEveryContentsPageOfTwoRealScansAndNoOtherFrontPage() {
        // OCR saved from real scans: every page the contents list fills is found, and no other front page is.
        val dir = File("src/androidTest/assets/ocr")
        val expected = mapOf("origin-contents" to (5..9).toSet(), "varieties-contents" to (11..15).toSet())
        for ((name, contents) in expected) {
            var previous = false
            val found = mutableSetOf<Int>()
            for (p in ChapterFixtures.parsePages(File(dir, "$name.pages.json").readText())) {
                previous = p.page <= ContentsPages.FRONT && ContentsPages.isPage(ChapterEstimator.lines(p.blocks), previous, headless = true)
                if (previous) found += p.page
            }
            assertEquals(name, contents, found)
        }
    }

    @Test fun noBodyPageOfTheSavedScansIsContents() {
        val dir = File("src/androidTest/assets/ocr")
        for (name in listOf("bell", "gibbon", "james", "origin", "varieties-preface")) {
            val page = OcrFixtures.parseDump(File(dir, "$name.ocr.json").readText())
            assertFalse(name, ContentsPages.isPage(page))
            assertFalse("$name after a contents page", ContentsPages.isPage(page, continuation = true, headless = true))
        }
    }
}
