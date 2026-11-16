/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

/**
 * What the voice is given to say, as opposed to what the transcript shows. Today it writes years out the way people
 * say them, because the neural voices read "1969" as a quantity ("one thousand nine hundred sixty-nine").
 * The sentence stored in the transcript, the notes and the library keeps its digits.
 */
object SpokenText {
    fun of(text: String): String = years(text)

    private val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val tensPlural = listOf("", "", "twenties", "thirties", "forties", "fifties", "sixties", "seventies", "eighties", "nineties")

    /** 0..99 as words: 69 → "sixty-nine". */
    private fun below100(n: Int): String =
        if (n < 20) ones[n] else tens[n / 10] + if (n % 10 == 0) "" else "-" + ones[n % 10]

    /** The two-digit tail of a year: 5 → "oh five", 69 → "sixty-nine". */
    private fun tail(n: Int): String = if (n in 1..9) "oh ${ones[n]}" else below100(n)

    /** A year from 1100 to 2099: 1969 → "nineteen sixty-nine", 1900 → "nineteen hundred", 2007 → "two thousand seven". */
    private fun year(n: Int): String {
        if (n in 2000..2009) return if (n == 2000) "two thousand" else "two thousand ${ones[n - 2000]}"
        val hi = below100(n / 100)
        val lo = n % 100
        return if (lo == 0) "$hi hundred" else "$hi ${tail(lo)}"
    }

    /** A decade: 1960 → "nineteen sixties", 1900 → "nineteen hundreds", 2010 → "twenty tens". */
    private fun decade(n: Int): String {
        if (n == 2000) return "two thousands"
        val hi = below100(n / 100)
        val lo = n % 100
        return when {
            lo == 0 -> "$hi hundreds"
            lo == 10 -> "$hi tens"
            else -> "$hi ${tensPlural[lo / 10]}"
        }
    }

    // A four-digit token from 1100 to 2099, optionally a decade ("1960s", "1960's") or the start of a range ("1969–72", "1969-1972").
    private val candidate = Regex("""(?<![\w$£€¥#§%.,/-])(1[1-9]\d\d|20\d\d)(?:('?s)|[-–](\d\d|1[1-9]\d\d|20\d\d))?(?![\w%/])""")

    private val cueBefore = Regex(
        """(?i)\b(in|since|by|until|till|of|from|during|circa|c\.|ca\.|before|after|through|between|early|late|mid|january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\.?\s*$""",
    )
    private val labelBefore = Regex(
        """(?i)\b(page|pages|p|pp|no|nos|number|vol|volume|chapter|ch|section|sec|table|figure|fig|plate|line|lines|item|code|room|route|highway|flight|channel|rule|article|art|paragraph|note|n|chap|ref|id|isbn|issue)\.?\s*$""",
    )
    private val unitsAfter = setOf(
        "people", "persons", "men", "women", "children", "soldiers", "workers", "dollars", "pounds", "euros", "cents", "francs",
        "years", "months", "days", "weeks", "hours", "minutes", "seconds", "miles", "kilometres", "kilometers", "km", "metres",
        "meters", "m", "feet", "ft", "acres", "tons", "tonnes", "kg", "kilograms", "grams", "words", "copies", "votes", "troops",
        "deaths", "casualties", "houses", "ships", "men's", "employees", "citizens", "inhabitants", "families", "cases", "units",
        "square", "per", "lb", "lbs", "mph", "mm", "cm", "ml", "gallons", "barrels", "bushels", "shares", "percent", "per-cent",
    )
    private val nonPlural = setOf("does", "this", "thus", "perhaps", "always", "sometimes", "towards", "afterwards", "besides", "less", "across", "unless")

    private fun nextWord(text: String, from: Int): String? =
        Regex("""^\s+([\p{L}'-]+)""").find(text.substring(from))?.groupValues?.get(1)

    /** Writes the years in [text] out in words; amounts, page numbers and other numbers are left as digits. */
    fun years(text: String): String {
        if (text.none { it.isDigit() }) return text
        return candidate.replace(text) { m ->
            val n = m.groupValues[1].toInt()
            val before = text.substring(0, m.range.first)
            val after = m.range.last + 1
            val trailing = text.substring(after)
            // "1,969" and "1969.5" are amounts; "In 1969, he" and "in 1969." are a year before punctuation.
            if (Regex("""^[.,]\d""").containsMatchIn(trailing)) return@replace m.value
            if (labelBefore.containsMatchIn(before)) return@replace m.value
            val cued = cueBefore.containsMatchIn(before)
            val next = nextWord(text, after)
            if (next != null) {
                val lower = next.lowercase()
                if (lower in unitsAfter) return@replace m.value
                if (!cued && next[0].isLowerCase() && lower.endsWith("s") && lower.length > 3 && lower !in nonPlural) return@replace m.value
            }
            val suffix = m.groupValues[2]
            val end = m.groupValues[3]
            when {
                suffix.isNotEmpty() && n % 10 == 0 -> decade(n)
                suffix == "'s" -> year(n) + "'s"
                suffix == "s" -> m.value
                end.length == 4 -> if (end.toInt() > n) "${year(n)} to ${year(end.toInt())}" else m.value
                end.length == 2 -> if (end.toInt() > n % 100) "${year(n)} to ${tail(end.toInt())}" else m.value
                else -> year(n)
            }
        }
    }
}
