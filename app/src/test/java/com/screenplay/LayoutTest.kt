/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** Page geometry is taken from real OCR of the fixture scans (DumpLayout output), trimmed to a few lines per block. */
class LayoutTest {
    private fun line(top: Int, height: Int, text: String) = OcrLine(top, top + height, text)
    private fun body(top: Int, n: Int, height: Int = 44, text: String = "body text of the page") =
        List(n) { line(top + it * 48, height, text) }

    @Test fun dropsRunningHeadsBelowTheEdgeBand() {
        // Origin of Species: heads sit about 10% down a 2319 px page, under a deep top margin.
        val page = listOf(
            listOf(line(247, 32, "8")),
            listOf(line(256, 23, "VARIATION")),
            listOf(line(251, 27, "CHAP, I.")),
            body(333, 4, text = "No case is on record"),
            body(581, 10, text = "It has been disputed"),
        )
        assertEquals(listOf("No case is on record", "It has been disputed"), Layout.body(page, 2319).map { it[0].text })
    }

    @Test fun keepsALowercaseLineCarriedOverToThePageTop() {
        val page = listOf(listOf(line(250, 40, "evolved.")), body(340, 10))
        assertEquals(2, Layout.body(page, 2327).size)
    }

    @Test fun keepsAChapterHeadingLowerDown() {
        val page = listOf(listOf(line(247, 27, "7")), listOf(line(495, 35, "CHAPTER I.")), body(656, 20))
        assertEquals(listOf("CHAPTER I.", "body text of the page"), Layout.body(page, 2327).map { it[0].text })
    }

    @Test fun dropsFootnotesInSmallerTypeBelowTheBody() {
        // Decline and Fall: notes at about 34 px against a 45 px body; the second note is one line.
        val page = listOf(
            body(684, 17, text = "IT is not alone"),
            body(1872, 4, text = "The superstition of the people"),
            listOf(line(2097, 33, "1They were erected about the midway between Lahor and Dehli."),
                line(2131, 34, "quests of Alexander"), line(2162, 41, "by the five great streams")),
            listOf(line(2197, 37, "2 See M. de Guignes")),
        )
        assertEquals(listOf("IT is not alone", "The superstition of the people"), Layout.body(page, 2475).map { it[0].text })
    }

    @Test fun keepsAShortLastLineOfBody() {
        val page = listOf(body(300, 20), listOf(line(1300, 36, "evolved.")))
        assertEquals(2, Layout.body(page, 2327).size)
    }

    @Test fun dropsIndexPages() {
        val entries = listOf("ABERRANT groups, 429.", "Abyssinia, plants of, 375.", "Acclimatisation, 139.",
            "of organic beings, 411.", "on groups of species suddenly", "appearing, 302, 305.")
        val page = List(12) { i -> listOf(line(600 + i * 30, 30, entries[i % entries.size])) }
        assertEquals(emptyList<List<OcrLine>>(), Layout.body(page, 2308))
    }

    @Test fun joinsLinesAndHyphenatedWords() {
        val page = listOf(listOf(line(300, 44, "our domestic productions having been raised under con-"),
            line(348, 44, "ditions of life.")))
        assertEquals(listOf("our domestic productions having been raised under conditions of life."), Layout.paragraphs(page, 2327))
    }

    @Test fun joinsADropCapReturnedAfterItsParagraph() {
        // A history book's preface: OCR returns the large "P" as its own block after the paragraph it opens.
        val page = listOf(
            listOf(line(450, 33, "PREFACE")),
            listOf(line(905, 42, "ERHAPS the oldest and most useful trade on the coast is the keeping of lamps and"),
                line(953, 43, "charts along the shore at the harbour mouth,")),
            listOf(line(917, 111, "P")),
            listOf(line(1559, 37, "The focus of this book is on the harbour office,")),
        )
        assertEquals(
            listOf(
                "PREFACE",
                "PERHAPS the oldest and most useful trade on the coast is the keeping of lamps and charts along the shore at the harbour mouth,",
                "The focus of this book is on the harbour office,",
            ),
            Layout.paragraphs(page, 2468),
        )
    }

    @Test fun leavesALoneLetterBesideOrdinaryText() {
        // Not level with a small-capitals opening: left for Text to drop.
        val page = listOf(listOf(line(905, 42, "The oldest subject")), listOf(line(917, 111, "P")))
        assertEquals(listOf("The oldest subject", "P"), Layout.paragraphs(page, 2468))
    }
}
