/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import java.text.BreakIterator
import java.util.Locale

object Text {
    internal val sentenceEnd = charArrayOf('.', '!', '?', ':', '"', '”', '’')

    private val pageNumber = Regex("^(?:[\\dIlO|.\\s]{1,6}|[ivxlc]{1,4}|\\p{L})$", RegexOption.IGNORE_CASE)
    private val leadingNumber = Regex("^\\d{1,3}\\s+(?=\\p{Ll})")
    /** OCR reads the pronoun I as T. Only before words that cannot follow a real T (as in "Model T was"). */
    private val misreadPronoun = Regex("(?<![\\p{L}\\d])T(?=\\s(?:am|have|think|believe|know|shall|must)\\b|['’](?:m|ve|ll|d)\\b)")

    /**
     * Drops what is not part of the spoken text: page numbers (alone, or glued to the start of a block),
     * stray one-letter blocks, and short blocks repeated verbatim, such as a title page printed three times.
     * A repeat that heads body text is kept: a chapter's number and title, which the contents page printed first.
     */
    fun wordCount(s: String): Int = s.split(Regex("\\s+")).count { it.isNotEmpty() }

    fun clean(blocks: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        val trimmed = blocks.map(::trimBlock)
        for ((i, b) in trimmed.withIndex()) {
            if (b.isEmpty() || pageNumber.matches(b)) continue
            if (b.length <= 60 && !seen.add(b.lowercase().filter { it.isLetterOrDigit() }) && !Chapters.bodyFollows(trimmed, i + 1)) continue
            out += b
        }
        return out
    }

    /** [clean] one block at a time: the block as [clean] trims it. */
    internal fun trimBlock(raw: String) = raw.trim().replace(leadingNumber, "").replace(misreadPronoun, "I")

    /** Whether [clean] drops the trimmed block [b] as empty or a page number. */
    internal fun isPageNumber(b: String) = b.isEmpty() || pageNumber.matches(b)

    /** The key under which [clean] counts a short block as repeated, or null for a block too long to repeat. */
    internal fun repeatKey(b: String): String? = if (b.length <= 60) b.lowercase().filter { it.isLetterOrDigit() } else null

    /** A PDF's OCR paragraphs, cleaned and with paragraphs cut by a page break joined. */
    fun fromOcr(paragraphs: List<String>): List<String> = mergeAcrossPages(clean(paragraphs))

    /** Joins a paragraph that a page break cut in two. */
    fun mergeAcrossPages(paragraphs: List<String>): List<String> {
        val out = ArrayList<String>()
        for (p in paragraphs.map { it.trim() }.filter { it.isNotEmpty() }) {
            val prev = out.lastOrNull()
            if (prev != null && continuesAcrossPage(prev, p)) out[out.lastIndex] = joinAcrossPage(prev, p) else out += p
        }
        return out
    }

    internal fun continuesAcrossPage(prev: String, p: String) = prev.last() !in sentenceEnd && p.first().isLowerCase()

    internal fun joinAcrossPage(prev: String, p: String): String {
        val hyphenated = prev.length > 1 && prev.last() == '-' && prev[prev.length - 2].isLowerCase()
        return if (hyphenated) prev.dropLast(1) + p else "$prev $p"
    }

    /** Sentences short enough for a speech engine's input limit. */
    fun sentences(paragraphs: List<String>, max: Int = 300): List<String> {
        val out = ArrayList<String>()
        for (p in paragraphs) {
            val it = BreakIterator.getSentenceInstance(Locale.US)
            it.setText(p)
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                var s = p.substring(start, end).trim()
                while (s.length > max) {
                    val cut = s.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
                    out += s.substring(0, cut).trim()
                    s = s.substring(cut).trim()
                }
                if (s.isNotEmpty()) out += s
                start = end
                end = it.next()
            }
        }
        return out
    }

    internal val opening = Regex(
        "^(preface|foreword|prologue|introduction|chapter\\s+(1|one|i)\\b.*|part\\s+(1|one|i)\\b.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val imprint = Regex("isbn|all rights reserved|©|copyright|library of congress|printed in", RegexOption.IGNORE_CASE)
    private val abbreviation = Regex("(?:\\b(?:Mr|Mrs|Ms|Dr|Prof|St|Mt|Jr|Sr|vs|No|Vol|Fig|Co|Inc|Ltd|cf)|\\be\\.g|\\bi\\.e|\\b\\p{Lu})\\.$")

    internal fun isProse(p: String) = p.length >= 150 && p.last() in sentenceEnd

    /**
     * Drops the front matter: everything before the first opening heading (preface, chapter 1 and so on)
     * that is followed by real prose, so a contents page does not count. With no such heading, drops
     * everything up to the last imprint block (ISBN, copyright) near the start. Only the first quarter is searched.
     */
    fun trimFront(paragraphs: List<String>): List<String> {
        val window = maxOf(20, paragraphs.size / 4).coerceAtMost(paragraphs.size)
        val heading = (0 until window).firstOrNull { i ->
            paragraphs[i].length <= 60 && opening.matches(paragraphs[i]) &&
                paragraphs.subList(i + 1, minOf(i + 4, paragraphs.size)).any(::isProse)
        }
        if (heading != null) return paragraphs.drop(heading)
        val imprintAt = (0 until window).lastOrNull { imprint.containsMatchIn(paragraphs[it]) && paragraphs[it].length < 400 }
        return if (imprintAt != null) paragraphs.drop(imprintAt + 1) else paragraphs
    }

    internal val backMatter = Regex(
        "^(?:notes|bibliography|references|index|appendix(?:es)?|appendices|appendix\\s+[\\p{Lu}\\d])$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Drops the back matter: everything from the first notes, appendix, bibliography or index heading in the
     * second half of the text, by characters. A contents page lists the same words near the start, so the first half is not searched.
     */
    fun trimBack(paragraphs: List<String>): List<String> {
        val half = paragraphs.sumOf { it.length } / 2
        var seen = 0
        for ((i, p) in paragraphs.withIndex()) {
            if (seen >= half && backMatter.matches(p.trim().trimEnd('.', ':'))) return paragraphs.take(i)
            seen += p.length
        }
        return paragraphs
    }

    internal val dropCap = Regex("^\\p{Lu}[^\\p{L}\\d\\s]?$")
    internal val smallCaps = Regex("^\\p{Lu}(?:\\p{Lu}|\\s\\p{Lu}{2})")
    /**
     * A footnote number (or asterisk) glued to the punctuation ending a word or a quotation: `fast.12`, `"Inc."19`,
     * `"(26 percent)."6`, `ourselves.'"0`. After a digit only a closing quote marks it, so `3.25` stays. A symbol marker
     * also follows a capital, as in a title set in capitals (`ENERGY.*`), where a number could be part of a name.
     */
    private val noteMarker = Regex(
        "(?:(?<=\\p{Ll}[.,;:!?)\"”’']{1,3})|(?<=\\d[.,;:!?)]{0,2}[\"”’][.,;:!?)\"”’']{0,2}))(?:[*†‡]?\\d{1,3}|[*†‡])(?=\\s|$)" +
            "|(?<=\\p{Lu}[.,;:!?)\"”’']{1,3})[*†‡](?=\\s|$)"
    )

    /**
     * A footnote number that OCR reads a space after a closing quote: `death." 2 In the vision`. Only when a capital or
     * an opening quote follows, so `"three or 4," 5 times` and `"9." 4 years later` stay.
     */
    private val spacedNoteMarker = Regex("(?<=[.,;:!?][)\"”’']{0,2}[\"”’'])\\s+\\d{1,3}(?=\\s+[\\p{Lu}\"“‘'«])")

    /** A footnote number after the closing quote that ends a paragraph: `"Port Ltd." 19`. */
    private val trailingNoteMarker = Regex("(?<=[.,;:!?][)\"”’']{0,2}[\"”’'])\\s+\\d{1,3}$")

    /** A footnote number OCR reads as a second closing quote: `budget.""`. */
    private val doubledQuote = Regex("(?<=[.!?][\"”])[\"”](?=\\s|$)")
    private val spokenEnd = Regex("[.?!:;,)\"”’]$")

    private fun isNumeric(p: String): Boolean {
        val alnum = p.count { it.isLetterOrDigit() }
        return alnum == 0 || (p.length < 60 && p.count { it.isDigit() } > 0.2 * alnum)
    }

    private val pageReference = Regex("[,\\s]\\d{1,3}\\.?$")
    private val statementEnd = Regex("[.!][\"”’)]?$")
    private val punctuation = Regex("[.,;:]")
    private val entryEnd = Regex(",\\s\\d{1,3}\\.(\\s|$)")

    /**
     * A contents or abbreviation entry: mostly numbers, short and unpunctuated, ending in a page number
     * ("Ministry, 1925-1973, 332."), longer entries OCR joined with no punctuation at all, or three or more
     * entries OCR joined into one paragraph, which a page break can cut mid-entry ("... unreality, 63. Sense").
     * A long chapter subtitle has a comma ("Of the Union ... Empire, in the Age of the Antonines") and is kept.
     */
    private fun isListLine(p: String) =
        isNumeric(p) || (p.length < 60 && !spokenEnd.containsMatchIn(p)) || pageReference.containsMatchIn(p) ||
            (p.length < 120 && !punctuation.containsMatchIn(p) && !spokenEnd.containsMatchIn(p)) ||
            entryEnd.findAll(p).count() >= 3

    /** A short heading inside a list that ends in a question mark or quote ("A Coastal Model?"), but not a short sentence. */
    private fun isBridge(p: String) = p.length < 60 && !statementEnd.containsMatchIn(p)

    /**
     * Drops what is not prose: lines that are mostly numbers (page numbers, table figures), and runs of three
     * or more list lines (contents, abbreviation lists, table cells), across single short headings between them.
     * Rejoins a chapter's large first letter that OCR split off ("B:" + "Y COMMON" → "BY COMMON") and strips
     * footnote numbers glued to punctuation.
     * Chapter headings ("Chapter 2", or "ONE" with its title under it) are always kept.
     */
    fun dropNonProse(paragraphs: List<String>): List<String> {
        val joined = ArrayList<String>()
        for (raw in paragraphs) joinDropCap(joined, raw)
        return dropRuns(joined)
    }

    /** Adds one paragraph to [joined], stripping footnote numbers and rejoining a dropped first letter with the paragraph after it. */
    internal fun joinDropCap(joined: MutableList<String>, raw: String) {
        val p = raw.trim().replace(noteMarker, "").replace(spacedNoteMarker, "").replace(trailingNoteMarker, "").replace(doubledQuote, "")
        val prev = joined.lastOrNull()
        if (prev != null && dropCap.matches(prev)) {
            // A first letter is followed by small capitals ("Y COMMON"); anything else is OCR noise ("G$" before "I cannot").
            if (smallCaps.containsMatchIn(p)) joined[joined.lastIndex] = prev.first() + p
            else joined[joined.lastIndex] = p
        } else {
            joined += p
        }
    }

    /**
     * A paragraph no list run can cross: neither a list line nor a bridge. Everything before one is decided
     * whatever follows it, so a reader of a growing text can release it.
     */
    internal fun endsRuns(p: String) = !isListLine(p) && !isBridge(p)

    /** The list-dropping half of [dropNonProse], over paragraphs already passed through [joinDropCap]. */
    internal fun dropRuns(joined: List<String>): List<String> {
        val drop = BooleanArray(joined.size) { isNumeric(joined[it]) }
        var i = 0
        while (i < joined.size) {
            var j = i
            var lines = 0
            while (j < joined.size) {
                if (isListLine(joined[j])) lines++
                else if (!(lines > 0 && isBridge(joined[j]) && j + 1 < joined.size && isListLine(joined[j + 1]))) break
                j++
            }
            if (lines >= 3) for (k in i until j) drop[k] = true
            i = maxOf(j, i + 1)
        }
        return joined.filterIndexed { k, p -> p.isNotEmpty() && (!drop[k] || Chapters.isHeading(p) || Chapters.isOpening(joined, k) || Chapters.isChapterTitle(joined, k)) }
    }

    /** The sentences to speak from a book's OCR paragraphs, after the cleaning the options ask for. Spelling is separate. */
    fun prepare(paragraphs: List<String>, options: Options): List<String> {
        var body = if (options.trimFront) trimFront(paragraphs) else paragraphs
        if (options.cleanText) body = dropNonProse(trimBack(body))
        val sentences = sentences(body, if (options.mergeFragments) 1000 else 300)
        return if (options.mergeFragments) mergeFragments(sentences) else sentences
    }

    /** Initials set alone, such as the "C.J." that signs a preface. */
    private val signOff = Regex("^(?:\\p{Lu}\\.){2,}$")

    /** A sign-off is a paragraph of its own, not the start of a sentence, so the next paragraph ("ONE") does not join it. */
    internal fun joinsFragment(prev: String, s: String, max: Int) =
        prev.length + 1 + s.length <= max && (abbreviation.containsMatchIn(prev) && !signOff.matches(prev) || s.first().isLowerCase())

    /** Joins a sentence the splitter cut in two: after an abbreviation, or before a lowercase start. */
    fun mergeFragments(sentences: List<String>, max: Int = 1000): List<String> {
        val out = ArrayList<String>()
        for (s in sentences) {
            val prev = out.lastOrNull()
            if (prev != null && joinsFragment(prev, s, max)) {
                out[out.lastIndex] = "$prev $s"
            } else {
                out += s
            }
        }
        return out
    }
}
