/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

/**
 * Finds a contents page by the shape of its lines, from the text alone (a scan after OCR, or an EPUB's paragraphs).
 *
 * An entry is a short line that ends in a page number (after dot leaders, or a space), or opens with "Chapter", a number or a Roman numeral.
 * A contents page is found by one of two routes:
 *  - the heading "Contents" or "Table of Contents" and, after it, a list with at least [MIN_HEADED] entries; or
 *  - (off, see [HEADLESS]) no heading, but the page follows a contents page, opens with an entry, has at least [MIN_LINES] lines,
 *    [MIN_SHARE] of them entries, and rising numbers: the next page of a contents list that runs over.
 * The heading alone is not enough (an index or a list of illustrations has a similar one), and a share of "Chapter" words is not either:
 * "Chapter 3 The Duties of a Prince 41" is a small part Chapter, and a book that lists titles without the word scores nothing.
 */
object ContentsPages {
    /** Pages (or EPUB files) from the start of a book that are looked at: a contents page is in the front part. */
    const val FRONT = 15

    /**
     * The headingless route is built and tested but off: only a page with the heading counts. Turn it on, or pass `headless = true`, to also
     * take the next page of a long list that has no heading of its own.
     */
    const val HEADLESS = false

    const val MIN_HEADED = 3
    const val MIN_LINES = 6
    const val MIN_SHARE = 0.6

    /** How much of a page a contents list fills for the page to be one: a page that only starts with a list is a page of text. */
    private const val PAGE_SHARE = 0.8

    private const val MAX_ENTRY = 150
    private const val MAX_TITLE_WORDS = 12

    /** An entry in capitals can be long ("DESCRIPTION OF THE METHODS ADOPTED BY THE DUKE ...") and is still a title, never prose. */
    private const val MAX_CAPS = 250

    /**
     * Lines in a row that are neither entries nor titles before the list is over: the description a contents list gives under a chapter can run
     * to a dozen lines. A line longer than [MAX_ENTRY] is prose and ends the list at once.
     */
    private const val MAX_GAP = 15

    /** The heading, with the page number a running head adds on either side ("vi CONTENTS.", "CONTENTS ix") and what a title page adds after it ("OF THE FIRST VOLUME"). */
    private val heading = Regex("^[\\p{P}\\s]*(?:[ivxlc\\d]{1,7}\\s+)?(?:table\\s+of\\s+)?contents?\\b[\\p{P}\\s]*(?:of\\s+[^.]{0,40})?(?:[ivxlc\\d]{1,7}[\\p{P}\\s]*)?$", RegexOption.IGNORE_CASE)
    private val spacedOut = Regex("^(?:\\p{L}\\s){2,}\\p{L}$")
    /** A page, or the first of a range of pages ("7-43"), after leader dots or a space. */
    private val trailingNumber = Regex("^.{2,}?(?:\\s*[.·…_]+\\s*|\\s+)(\\d{1,4})(?:\\s*[-–—]\\s*\\d{1,4})?\\.?$")
    private val trailingRoman = Regex("^.{2,}?\\s*[.·…_]+\\s*([ivxlcdm]{1,7})\\.?$", RegexOption.IGNORE_CASE)
    /** A range of pages alone on its line: the end of an entry whose title and description ran over several lines. */
    private val pageRange = Regex("^(\\d{1,4})\\s*[-–—]\\s*\\d{1,4}$")
    private val chapterLabel = Regex("^(?:chapter|chap\\.)\\s+(\\S+)", RegexOption.IGNORE_CASE)
    private val numberLabel = Regex("^(\\d{1,3})[.)]?\\s+\\p{L}")
    private val romanLabel = Regex("^([IVXLC]{1,6})[.)]\\s+\\p{L}")
    private val sentenceEnd = Regex("[.!?][\"”’)]*$")

    /** True for a line that is a contents heading: "Contents", "TABLE OF CONTENTS.", "C O N T E N T S", "Contents of Volume I". */
    fun isHeading(line: String): Boolean {
        val t = line.trim().let { if (spacedOut.matches(it)) it.replace(" ", "") else it }
        return t.length <= 40 && heading.matches(t)
    }

    /** True when [line] has the shape of a contents entry. */
    fun isEntry(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3 || t.length > MAX_ENTRY || isHeading(t)) return false
        return trailingNumber.matches(t) || pageRange.matches(t) || trailingRoman.matches(t) || chapterLabel.containsMatchIn(t) || numberLabel.containsMatchIn(t) || romanLabel.containsMatchIn(t)
    }

    /** A short title with no sentence in it ("Introduction", "THE LIFE OF CASTRUCCIO"): it can sit in a list of entries without being one. */
    private fun isCaps(t: String) = t.length <= MAX_CAPS && t.any { it.isLetter() } && t.none { it.isLowerCase() }

    private fun isTitle(t: String) = t.length in 2..MAX_ENTRY && t.split(' ').size <= MAX_TITLE_WORDS && !sentenceEnd.containsMatchIn(t)

    /**
     * How far the list that starts at [from] runs: through its entries, its titles, and up to [MAX_GAP] lines in a row of the description
     * that goes with an entry. A line of prose, or the end of the lines, ends it, and trailing description is not part of it.
     */
    private fun listEnd(lines: List<String>, from: Int): Int {
        var end = from - 1
        var gap = 0
        for (i in from until lines.size) {
            val t = lines[i].trim()
            when {
                isEntry(t) -> { end = i; gap = 0 }
                isCaps(t) -> { end = i; gap = 0 }
                isTitle(t) -> gap = 0
                t.length > MAX_ENTRY || ++gap > MAX_GAP -> break
            }
        }
        return end
    }

    /** The number an entry gives: its page, else the number of its label; null for a title that has neither. */
    private fun number(line: String): Int? {
        val t = line.trim()
        (trailingNumber.matchEntire(t) ?: pageRange.matchEntire(t))?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        trailingRoman.matchEntire(t)?.groupValues?.get(1)?.let { return roman(it) }
        chapterLabel.find(t)?.groupValues?.get(1)?.trimEnd('.', ':')?.let { return it.toIntOrNull() ?: roman(it) }
        numberLabel.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return romanLabel.find(t)?.groupValues?.get(1)?.let(::roman)
    }

    private fun roman(s: String): Int? {
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        val v = s.uppercase().map { values[it] ?: return null }
        return v.indices.sumOf { if (it + 1 < v.size && v[it] < v[it + 1]) -v[it] else v[it] }
    }

    /** True when at least four in five of the numbers do not fall: a list in page order, not an index. */
    private fun rising(lines: List<String>): Boolean {
        val n = lines.mapNotNull(::number)
        if (n.size < MIN_HEADED) return false
        return n.zipWithNext().count { (a, b) -> b >= a } >= 0.8 * (n.size - 1)
    }

    /**
     * Which of [lines] are a contents list, as a range of indexes, or null when none. The list is one contents heading, the entries and titles
     * that follow it; its heading must be among the first [maxHeading] + 1 lines (a scan page has a running head, a file of an EPUB has more
     * before it). [continuation] says the page before was a contents page: only then can a page without a heading be one.
     */
    fun find(lines: List<String>, continuation: Boolean = false, maxHeading: Int = 1, headless: Boolean = HEADLESS): IntRange? {
        val at = lines.take(maxHeading + 1).indexOfFirst(::isHeading)
        if (at >= 0) {
            val end = listEnd(lines, at + 1)
            return if (lines.subList(at + 1, end + 1).count(::isEntry) >= MIN_HEADED) at..end else null
        }
        if (!headless || !continuation || lines.size < MIN_LINES || !isEntry(lines.first())) return null
        val share = lines.count(::isEntry).toDouble() / lines.size
        return if (share >= MIN_SHARE && rising(lines.filter(::isEntry))) lines.indices else null
    }

    /**
     * True when [lines], the text of one scanned page, are a contents page: [find] covers the page, but for a footer or a description under the last entry.
     * [continuation] says the page before was one too.
     */
    fun isPage(lines: List<String>, continuation: Boolean = false, headless: Boolean = HEADLESS): Boolean {
        val all = lines.filter { it.isNotBlank() }
        val found = find(all, continuation, headless = headless) ?: return false
        return found.count() >= PAGE_SHARE * all.size
    }
}
