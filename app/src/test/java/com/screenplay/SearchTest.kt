/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun spans(text: String, query: String) = Search.find(listOf(text), query).map { it.start to it.end }

    @Test
    fun ignoresCaseAndAccents() {
        assertEquals(listOf(4 to 8), spans("The Café is open", "CAFE"))
        assertEquals(listOf(4 to 8), spans("The cafe is open", "café"))
        // A found position is a position in the original text, accents and all.
        assertEquals(listOf(0 to 3), spans("Zoë's", "ZOE"))
    }

    @Test
    fun findsEveryMatchInOneParagraph() {
        assertEquals(listOf(4 to 7, 16 to 19), spans("the cat and the cat sat", "cat"))
    }

    @Test
    fun findsMatchesAcrossParagraphsInReadingOrder() {
        val found = Search.find(listOf("one fox", "none", "a fox, a fox"), "fox")
        assertEquals(listOf(SearchMatch(0, 4, 7), SearchMatch(2, 2, 5), SearchMatch(2, 9, 12)), found)
    }

    @Test
    fun noMatchGivesAnEmptyList() {
        assertTrue(Search.find(listOf("The quick brown fox."), "zebra").isEmpty())
    }

    @Test
    fun anEmptyOrBlankQueryMatchesNothing() {
        assertTrue(Search.find(listOf("The quick brown fox."), "").isEmpty())
        assertTrue(Search.find(listOf("The quick brown fox."), "   ").isEmpty())
    }

    @Test
    fun aQueryLongerThanTheTextMatchesNothing() {
        assertTrue(Search.find(listOf("fox"), "the fox jumps").isEmpty())
    }

    @Test
    fun overlappingMatchesAreAllFound() {
        assertEquals(listOf(0 to 2, 1 to 3), spans("aaa", "aa"))
    }

    @Test
    fun aQueryIsLiteralTextNotAPattern() {
        assertEquals(listOf(1 to 3), spans("a.b a+b", ".b"))
        assertTrue(spans("axb", "a.b").isEmpty())
    }

    @Test
    fun nextAndPreviousWrapAtTheEnds() {
        assertEquals(1, Search.next(3, 0))
        assertEquals(0, Search.next(3, 2))
        assertEquals(2, Search.previous(3, 0))
        assertEquals(1, Search.previous(3, 2))
        assertEquals(0, Search.next(1, 0))
        assertEquals(0, Search.previous(1, 0))
        assertEquals(0, Search.next(0, 0))
    }

    @Test
    fun theCounterReadsLikeThis() {
        assertEquals("3 of 17", Search.label("fox", 17, 2))
        assertEquals("No matches", Search.label("zebra", 0, 0))
        assertEquals("", Search.label("", 0, 0))
    }

    @Test
    fun aWholeBookIsScannedInAFewMilliseconds() {
        val paragraph = "The quick brown fox jumps over the lazy dog, and Café owners wonder why. ".repeat(4)
        val book = List(1_000) { paragraph } // about 50,000 words
        val start = System.nanoTime()
        val found = Search.find(book, "e")
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(found.size > 10_000)
        assertTrue("scan took $millis ms", millis < 500)
    }

    @Test
    fun matchColoursStayReadableUnderTheThemeText() {
        fun contrast(a: Color, b: Color): Double {
            val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
            return (hi + 0.05) / (lo + 0.05)
        }
        for (shade in listOf(SearchColors.match(false), SearchColors.current(false))) assertTrue(contrast(LightColors.onSurface, shade.compositeOver(LightColors.background)) >= 4.5)
        for (shade in listOf(SearchColors.match(true), SearchColors.current(true))) assertTrue(contrast(DarkColors.onSurface, shade.compositeOver(DarkColors.background)) >= 4.5)
        for (c in HighlightColor.entries) {
            assertTrue(SearchColors.match(false) != c.background(false) && SearchColors.current(false) != c.background(false))
            assertTrue(SearchColors.match(true) != c.background(true) && SearchColors.current(true) != c.background(true))
        }
    }
}
