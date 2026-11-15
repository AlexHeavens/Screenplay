/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

/**
 * Chapter titles printed in block capitals ("CHAPTER III", "THE PRINCE'S DUTIES") in standard capitalisation.
 * A title with any lower-case letter is left as it is: someone already chose its case.
 */
object TitleCase {
    private val small = setOf("a", "an", "the", "and", "but", "or", "nor", "for", "of", "in", "on", "at", "to", "by", "as", "up", "per", "via", "vs")
    private val acronyms = setOf(
        "USA", "UK", "EU", "UN", "NATO", "FBI", "CIA", "KGB", "BBC", "NASA", "UFO", "USSR", "DNA", "TV", "AI", "OK", "NHS", "GDP", "CEO",
        "HIV", "UNESCO", "OPEC", "NYC", "DVD", "PC", "WWI", "WWII",
    )
    private val roman = Regex("^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")
    /** Roman numerals that are more often words in a title. */
    private val notRoman = setOf("MIX", "MI", "DI", "LI")
    private val ordinal = Regex("^\\d+(ST|ND|RD|TH)$")
    private val dotted = Regex("^(\\p{Lu}\\.)+\\p{Lu}?$")
    private val label = setOf("chapter", "part", "book", "section", "appendix", "volume", "lecture", "act", "scene")

    fun of(title: String): String {
        if (title.none { it.isLetter() } || title.any { it.isLowerCase() }) return title
        val words = Regex("\\S+").findAll(title).toList()
        val out = StringBuilder()
        var last = 0
        var start = true
        for ((k, m) in words.withIndex()) {
            out.append(title, last, m.range.first)
            val prior = words.getOrNull(k - 1)?.value?.lowercase()?.trim { !it.isLetter() }
            out.append(word(m.value, start, k == words.lastIndex, prior in label))
            start = m.value.last() in ":.!?"
            last = m.range.last + 1
        }
        return out.append(title, last, title.length).toString()
    }

    private fun word(w: String, start: Boolean, end: Boolean, afterLabel: Boolean): String {
        val core = w.trim { !it.isLetterOrDigit() }
        if (core.isEmpty() || core in acronyms || dotted.matches(core) || isRoman(core)) return w
        if (ordinal.matches(core)) return w.lowercase()
        // Pieces after the first are small words or suffixes: MOTHER-IN-LAW, PRINCE'S, O'BRIEN.
        val out = StringBuilder()
        var first = true
        var prev = ' '
        for (piece in Regex("[\\p{L}\\p{N}.]+|[^\\p{L}\\p{N}.]+").findAll(w).map { it.value }) {
            if (!piece.first().isLetterOrDigit()) { out.append(piece); prev = piece.last(); continue }
            val lower = piece.lowercase()
            out.append(
                when {
                    first -> if (start || lower !in small || (lower == "a" && (end || afterLabel))) lower.cap() else lower
                    prev == '\'' || prev == '’' -> if (w.substringBefore(prev).count { it.isLetter() } == 1 && piece.length > 2) lower.cap() else lower
                    lower in small -> lower
                    else -> lower.cap()
                },
            )
            first = false
        }
        return out.toString()
    }

    private fun isRoman(s: String) = s.length <= 8 && s !in notRoman && s.all { it in "IVXLCDM" } && roman.matches(s)

    private fun String.cap() = replaceFirstChar { it.uppercase() }
}
