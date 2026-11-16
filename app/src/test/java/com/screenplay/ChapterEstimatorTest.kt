/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterEstimatorTest {
    /** A line at row [row] (rows are 50 px apart on a 2000 px page) with its left edge at [x]. */
    private fun line(row: Int, text: String, x: Int = 200) = OcrLine(100 + row * 50, 140 + row * 50, text, x)

    private fun page(n: Int, vararg lines: OcrLine) = OcrPage(n, 2000, lines.map { listOf(it) })

    /** A body page with its running number at the bottom, and a paragraph so it is not empty. */
    private fun numbered(pdf: Int, printed: Int) = page(pdf, line(10, "Some body text of the book that goes on."), OcrLine(1900, 1940, "$printed", 900))

    private fun plain(pdf: Int) = page(pdf, line(10, "Some body text of the book that goes on."))

    private fun titles(plan: ChapterPlan) = plan.chapters.map { it.title to it.page }

    @Test fun contentsRowsWithLeadersGiveTitlesAndPredictedPages() {
        val contents = page(
            7, line(2, "CONTENTS"),
            line(5, "CHAPTER I. The Beginning . . . . . . 1"),
            line(6, "CHAPTER II. The Middle . . . . . . 25"),
            line(7, "CHAPTER III. The End . . . . . . 61"),
            line(8, "Index . . . . . . 99"),
        )
        val plan = ChapterEstimator.plan(listOf(contents, plain(8), numbered(11, 1), numbered(12, 2), numbered(13, 3)))
        assertEquals(
            listOf("CHAPTER I The Beginning" to 11, "CHAPTER II The Middle" to 35, "CHAPTER III The End" to 71, "Index" to 109),
            titles(plan),
        )
        assertEquals(setOf(7), plan.contentsPages)
        assertEquals(10, plan.offset)
    }

    @Test fun numbersLabelsAndTitlesInSeparateBlocksAreMatchedByRow() {
        // OCR returned the three columns as separate blocks, the titles last and out of order, as in some real books.
        val contents = OcrPage(
            11, 2000,
            listOf(
                listOf(line(3, "TWO", 160), line(5, "THREE", 160), line(7, "FOUR", 160)),
                listOf(line(3, "35", 1300), line(5, "83", 1300), line(7, "116", 1300)),
                listOf(line(7, "The Pilots' Guild", 320), line(3, "The Harbour Office", 320)),
                listOf(line(5, "The Rise of the Salt Trade", 320)),
                listOf(line(1, "CONTENTS", 600)),
            ),
        )
        val plan = ChapterEstimator.plan(listOf(contents, numbered(30, 14), numbered(31, 15)))
        assertEquals(
            listOf("TWO The Harbour Office" to 51, "THREE The Rise of the Salt Trade" to 99, "FOUR The Pilots' Guild" to 132),
            titles(plan),
        )
    }

    @Test fun aChapterWithADescriptionAndAPageRangeTakesTheRangesFirstPage() {
        // The Origin layout: "CHAPTER I." and its title, several rows of description, then "7-43" at the right.
        val contents = page(
            5, line(1, "CONTENTS."),
            line(3, "CHAPTER I.", 600), line(4, "VARIATION UNDER DOMESTICATION.", 500),
            line(5, "Causes of Variability - Effects of Habit - Difficulty of", 290),
            line(6, "distinguishing between Varieties and", 340), line(6, "7-43", 1200),
            line(8, "CHAP TER II.", 600), line(9, "VARIATION UNDER NATURE.", 550),
            line(10, "Variability - Individual differences - relation of organism to", 290),
            line(11, "each other", 340), line(11, "Page 44-59", 1100),
            line(13, "CHAPTER III.", 600), line(14, "STRUGGLE FOR EXISTENCE.", 550),
            line(15, "Bears on natural selection", 290), line(16, "60-79", 1200),
        )
        val running = (11..14).map { page(it, line(1, "${it - 10} CHAP. I.", 300), line(10, "Some body text of the book that goes on.")) }
        val plan = ChapterEstimator.plan(listOf(contents) + running)
        assertEquals(
            listOf("CHAPTER I VARIATION UNDER DOMESTICATION" to 17, "CHAP TER II VARIATION UNDER NATURE" to 54, "CHAPTER III STRUGGLE FOR EXISTENCE" to 70),
            titles(plan),
        )
        assertEquals(10, plan.offset)
    }

    @Test fun anEntryWhoseNumberOcrLostIsFoundOnItsOpeningPage() {
        val contents = page(
            5, line(1, "CONTENTS"),
            line(3, "ONE The Opening", 160), line(5, "TWO The Second . . . 35"), line(7, "THREE The Third . . . 83"),
        )
        // The running head repeats the title at the top of later pages; only the page where it stands in the body opens the chapter.
        val opening = page(19, line(4, "ONE", 600), line(8, "The Opening", 500))
        val head = page(24, OcrLine(80, 120, "The Opening", 500), line(10, "Text of the book goes on and on."))
        val plan = ChapterEstimator.plan(listOf(contents, opening, head, numbered(25, 9), numbered(26, 10)))
        assertEquals(listOf("ONE The Opening" to 19, "TWO The Second" to 51, "THREE The Third" to 99), titles(plan))
    }

    @Test fun withoutRunningNumbersTheFirstChapterTitleGivesTheOffset() {
        val contents = page(5, line(1, "Contents"), line(3, "I. First Things . . . 1"), line(4, "II. Second Things . . . 20"), line(5, "III. Third Things . . . 44"))
        val opening = page(17, line(5, "FIRST THINGS", 500), line(9, "Text follows."))
        val plan = ChapterEstimator.plan(listOf(contents, plain(16), opening, plain(18)))
        assertEquals(16, plan.offset)
        assertEquals(listOf(17, 36, 60), plan.chapters.map { it.page })
    }

    @Test fun aRomanPageGivesNoPdfPageAndAWrappedTitleIsJoined() {
        val contents = page(
            5, line(1, "CONTENTS"),
            line(3, "Preface . . . . . vii"), line(4, "1. Of the Nature of Things and of the"), line(5, "Ways of Men . . . 3"),
            line(6, "2. The Second . . . 20"), line(7, "3. The Third . . . 44"),
        )
        val plan = ChapterEstimator.plan(listOf(contents, numbered(10, 4), numbered(11, 5)))
        assertEquals(
            listOf("Preface" to null, "1 Of the Nature of Things and of the Ways of Men" to 9, "2 The Second" to 26, "3 The Third" to 50),
            titles(plan),
        )
    }

    @Test fun aTitleEndingInRomanLookingLettersKeepsItsLastWord() {
        val contents = page(5, line(1, "CONTENTS"), line(3, "I. A Civil War . . . 3"), line(4, "II. The Lily 20"), line(5, "III. Third . . . 44"))
        assertEquals(listOf("I A Civil War", "II The Lily", "III Third"), ChapterEstimator.plan(listOf(contents, numbered(9, 2), numbered(10, 3))).chapters.map { it.title })
    }

    @Test fun aContentsPageWithoutPageNumbersListsTitlesOnly() {
        val contents = page(5, line(1, "CONTENTS"), line(3, "The Journey Begins"), line(4, "A Stranger Arrives"), line(5, "The Storm"), line(6, "Home Again"))
        val plan = ChapterEstimator.plan(listOf(contents, plain(6)))
        assertEquals(listOf("The Journey Begins", "A Stranger Arrives", "The Storm", "Home Again"), plan.chapters.map { it.title })
        assertEquals(listOf<Int?>(null, null, null, null), plan.chapters.map { it.page })
    }

    @Test fun contentsContinuingOnTheNextPageIsRead() {
        val one = page(5, line(1, "CONTENTS"), line(3, "I. First . . . 1"), line(4, "II. Second . . . 20"), line(5, "III. Third . . . 44"))
        val two = page(6, line(1, "IV. Fourth . . . 60"), line(2, "V. Fifth . . . 77"), line(3, "VI. Sixth . . . 90"))
        val plan = ChapterEstimator.plan(listOf(one, two, numbered(9, 1), numbered(10, 2)))
        assertEquals(setOf(5, 6), plan.contentsPages)
        assertEquals(listOf(1, 20, 44, 60, 77, 90).map { it + 8 }, plan.chapters.map { it.page })
    }

    @Test fun aMisreadPageNumberThatFallsBackLosesItsPageOnly() {
        val contents = page(5, line(1, "CONTENTS"), line(3, "I. First . . . 10"), line(4, "II. Second . . . 4"), line(5, "III. Third . . . 44"))
        val plan = ChapterEstimator.plan(listOf(contents, numbered(9, 1), numbered(10, 2)))
        assertEquals(listOf<Int?>(18, null, 52), plan.chapters.map { it.page })
    }

    @Test fun noContentsPageGivesNoPlan() {
        assertEquals(ChapterPlan.NONE, ChapterEstimator.plan(listOf(plain(1), plain(2), numbered(3, 1))))
    }

    @Test fun aPageNumberIsReadFromAnEdgeLineBesideARunningHead() {
        assertEquals(9, ChapterEstimator.pageNumber(page(25, OcrLine(129, 157, "9", 1319), OcrLine(120, 171, "The Harbour \"Lights\"", 531), line(10, "Body text."))))
        assertEquals(14, ChapterEstimator.pageNumber(page(30, OcrLine(153, 181, "14", 311), OcrLine(139, 189, "The harbour \"Lights\"", 712), line(10, "Body text."))))
        assertNull(ChapterEstimator.pageNumber(page(3, line(10, "1966 was a good year for the firm."))))
    }

    @Test fun anchorsAreNotedOnlyOnThePagePredictedForAChapter() {
        val plan = ChapterPlan(listOf(ChapterEstimate("ONE Opening", 3, 19), ChapterEstimate("TWO Next", 35, 51)), setOf(11), 16)
        val anchored = plan.withAnchors(19, listOf("ONE", "The Opening", "BY COMMON agreement", "fourth"))
        assertEquals(listOf("ONE", "The Opening", "BY COMMON agreement"), anchored.chapters[0].anchors)
        assertEquals(emptyList<String>(), anchored.chapters[1].anchors)
        assertEquals(plan, plan.withAnchors(20, listOf("x")))
    }
}
