/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import java.text.Normalizer

/** One hit of a search: the characters [start] until [end] of the text at index [paragraph] (the reader's row, spoken or still queued). */
data class SearchMatch(val paragraph: Int, val start: Int, val end: Int)

/**
 * Finds a plain-text query in the transcript. It is pure: no Android, no I/O, so a whole book is scanned in a few milliseconds.
 * Case and accents are ignored, and the query is a literal, never a pattern.
 */
object Search {
    /**
     * [text] in lower case without accents, character for character: the result is as long as [text], so a position found in it
     * is a position in the original. Plain ASCII, the common case, takes a fast path that never calls the normalizer.
     */
    fun fold(text: String): String {
        val folded = CharArray(text.length)
        for (i in text.indices) {
            val c = text[i]
            folded[i] = if (c.code < 0x80) c.lowercaseChar() else Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()
        }
        return String(folded)
    }

    /** Every place [query] occurs in [texts], in reading order. Matches may overlap: "aa" occurs twice in "aaa". A blank query matches nothing. */
    fun find(texts: List<String>, query: String): List<SearchMatch> {
        val needle = fold(query.trim())
        if (needle.isEmpty()) return emptyList()
        val matches = ArrayList<SearchMatch>()
        texts.forEachIndexed { paragraph, text ->
            if (text.length < needle.length) return@forEachIndexed
            val haystack = fold(text)
            var at = haystack.indexOf(needle)
            while (at >= 0) {
                matches += SearchMatch(paragraph, at, at + needle.length)
                at = haystack.indexOf(needle, at + 1)
            }
        }
        return matches
    }

    /** The match after [current] among [count], wrapping from the last to the first. */
    fun next(count: Int, current: Int) = if (count == 0) 0 else (current + 1) % count

    /** The match before [current] among [count], wrapping from the first to the last. */
    fun previous(count: Int, current: Int) = if (count == 0) 0 else (current - 1 + count) % count

    /** The line of the counter: "3 of 17", or "No matches" for a query that finds nothing, or nothing for an empty query. */
    fun label(query: String, count: Int, current: Int) = when {
        query.isBlank() -> ""
        count == 0 -> "No matches"
        else -> "${current + 1} of $count"
    }

    /** [text] with each of [ranges] marked; the one at [currentRange] (an index into [ranges]) stands out. */
    fun mark(text: String, ranges: List<SearchMatch>, currentRange: Int?, dark: Boolean): AnnotatedString = buildAnnotatedString {
        append(text)
        ranges.forEachIndexed { i, r ->
            val style = if (i == currentRange) SpanStyle(background = SearchColors.current(dark), fontWeight = FontWeight.Bold)
            else SpanStyle(background = SearchColors.match(dark))
            addStyle(style, r.start, r.end)
        }
    }
}

/** Teal, which no pen colour or the spoken sentence uses. Both stay readable under the theme's own text colour. */
object SearchColors {
    fun match(dark: Boolean) = if (dark) Color(0xFF00525C) else Color(0xFFB2EBF2)
    fun current(dark: Boolean) = if (dark) Color(0xFF00606B) else Color(0xFF4DD0E1)
}

/** How many search matches a row marks, on its semantics node, so a test can tell a marked row from a plain one. */
val SearchMarksKey = SemanticsPropertyKey<Int>("SearchMarks")
var SemanticsPropertyReceiver.searchMarks by SearchMarksKey
