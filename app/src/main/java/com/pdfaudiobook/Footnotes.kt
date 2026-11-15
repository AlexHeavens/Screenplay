/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

/**
 * A footnote of a scanned page, kept as a read-only annotation of the paragraph that cites it. It is never spoken and is not in the transcript.
 * [anchor] is the text just before the citing marker (or the end of the page's last paragraph when no marker was found), which
 * [Footnotes.place] searches for in the transcript; an empty anchor means "the paragraph of the footnote before".
 */
data class Footnote(val anchor: String, val text: String)

/** One page's paragraphs to read, and the footnotes taken out of them. */
data class PageText(val paragraphs: List<String>, val footnotes: List<Footnote> = emptyList())

/**
 * Finds the footnotes of a page of OCR paragraphs and matches each to the paragraph that cites it. Geometry decides first where it can
 * ([Layout] returns small type at the foot of the page as notes); the text patterns here catch the rest, and guard against lists and captions.
 */
object Footnotes {
    private const val ANCHOR_CHARS = 40
    private const val MIN_SENTENCE_ANCHOR = 6

    /** How sure a leading label makes a footnote: a bracket or superscript number is a note on its own; a symbol or a plain number needs a citation in the body. */
    private enum class Label { STRONG, NEEDS_CITATION }

    private class Marker(val key: String, val label: Label)

    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    private val bracketLabel = Regex("^\\[(\\d{1,3})]\\s*")
    private val superscriptLabel = Regex("^([$SUPERSCRIPTS]{1,3})\\s*")
    private val symbolLabel = Regex("^([*†‡])\\s*")
    private val numberLabel = Regex("^(\\d{1,3})[.)]?\\s+")
    private val sentenceEnd = Regex("[.!?][\"”’)]*\\s+")
    private val spaces = Regex("\\s+")

    private fun superscriptToDigits(s: String) = s.map { SUPERSCRIPTS.indexOf(it).toString() }.joinToString("")

    /** The label a note opens with, or null when the text is not note-like: a label needs some text after it. */
    private fun marker(text: String): Marker? {
        val t = text.trimStart()
        fun rest(m: MatchResult) = t.length - m.value.length >= 3
        bracketLabel.find(t)?.takeIf(::rest)?.let { return Marker(it.groupValues[1], Label.STRONG) }
        superscriptLabel.find(t)?.takeIf(::rest)?.let { return Marker(superscriptToDigits(it.groupValues[1]), Label.STRONG) }
        symbolLabel.find(t)?.takeIf(::rest)?.let { return Marker(it.groupValues[1], Label.NEEDS_CITATION) }
        numberLabel.find(t)?.takeIf(::rest)?.let { return Marker(it.groupValues[1], Label.NEEDS_CITATION) }
        return null
    }

    /** Where a body paragraph cites [key]: `[3]`, a superscript, a number glued to the word or punctuation before it, or a symbol after a word. */
    private fun citation(key: String): Regex {
        return if (key[0].isDigit()) {
            val sup = key.map { SUPERSCRIPTS[it - '0'] }.joinToString("")
            Regex("\\[$key]|$sup|(?<=\\p{L}|[.,;:!?)\"”’'])$key(?![\\d\\p{L}])")
        } else {
            Regex("(?<=\\S)${Regex.escape(key)}(?=[\\s.,;:)\"”’']|$)")
        }
    }

    /**
     * Takes the footnotes out of a page's [paragraphs]. [noted] are blocks [Layout] found by their place and type size, which are notes whatever
     * they say. Of [paragraphs], only the last ones count, and only when each opens with a label and a body paragraph comes before them.
     */
    fun split(paragraphs: List<String>, noted: List<String> = emptyList()): PageText {
        val trailing = trailingNotes(paragraphs)
        val body = paragraphs.dropLast(trailing.size)
        val notes = group(trailing + noted)
        if (notes.isEmpty()) return PageText(paragraphs)
        val rewritten = body.toMutableList()
        val footnotes = notes.map { cite(rewritten, it) }
        return PageText(rewritten.filter { it.isNotBlank() }, footnotes)
    }

    /**
     * Matches [note] to the paragraph of [body] that cites it, takes the marker out of that paragraph, and returns the footnote. Only [body] up to
     * index [last], the paragraph the note follows, is searched: from the start, or from [last] backwards when [nearest]. A note with no marker
     * in that range goes with the paragraph at [last].
     */
    fun cite(body: MutableList<String>, note: String, last: Int = body.lastIndex, nearest: Boolean = false): Footnote {
        val regex = marker(note)?.let { citation(it.key) }
        val range = if (nearest) last downTo 0 else 0..last
        val at = regex?.let { r -> range.firstOrNull { r.containsMatchIn(body[it]) } }
        if (regex == null || at == null) return Footnote(body.getOrNull(last)?.let { anchorBefore(it) }.orEmpty(), note)
        val text = body[at]
        val cited = regex.find(text)!!
        body[at] = text.removeRange(cited.range).replace(Regex(" {2,}"), " ").trim()
        return Footnote(anchorBefore(text.substring(0, cited.range.first)), note)
    }

    /** The trailing paragraphs that are notes: each opens with a label, a symbol or plain number is cited in the body, and a body paragraph precedes them. */
    private fun trailingNotes(paragraphs: List<String>): List<String> {
        var from = paragraphs.size
        while (from > 1 && marker(paragraphs[from - 1]) != null) from--
        if (from == paragraphs.size) return emptyList()
        val body = paragraphs.subList(0, from)
        // A page of nothing but labelled paragraphs is a list of notes or references, not a page with footnotes.
        if (body.none { marker(it) == null }) return emptyList()
        val run = paragraphs.subList(from, paragraphs.size)
        // From the end: a list item or caption that merely starts with a number is not a note, and nothing above it is either.
        var start = run.size
        while (start > 0 && isNote(run[start - 1], body)) start--
        return run.drop(start)
    }

    private fun isNote(text: String, body: List<String>): Boolean {
        val m = marker(text) ?: return false
        if (m.label == Label.STRONG) return true
        val regex = citation(m.key)
        return body.any { regex.containsMatchIn(it) }
    }

    /** Joins the blocks of a note that went on past a block (no label of its own) onto the note before it. */
    private fun group(blocks: List<String>): List<String> {
        val out = ArrayList<String>()
        for (b in blocks) {
            if (out.isEmpty() || marker(b) != null) out += b.trim() else out[out.lastIndex] = out.last() + " " + b.trim()
        }
        return out
    }

    /** The last few words before a marker: from the start of its sentence when that is long enough, else the last [ANCHOR_CHARS] characters. */
    private fun anchorBefore(before: String): String {
        val whole = before.replace(spaces, " ").trim()
        // Cut on a word, so the anchor does not start with the end of one.
        val tail = whole.takeLast(ANCHOR_CHARS).let { if (whole.length > ANCHOR_CHARS) it.substringAfter(' ', it) else it }
        val cut = sentenceEnd.findAll(tail).lastOrNull()?.range?.last?.plus(1)
        val sentence = cut?.let { tail.substring(it) }
        return (if (sentence != null && sentence.length >= MIN_SENTENCE_ANCHOR) sentence else tail).trim()
    }

    /**
     * Each footnote's paragraph, and where in it the marker was: the first paragraph at or after the previous footnote's that holds its anchor,
     * with the offset just after the anchor. One whose anchor is gone (the paragraph was edited, or cleaning dropped it) goes with the footnote
     * before it, at the end of that paragraph, so no note is lost. Notes of one paragraph keep their order.
     */
    private fun locate(footnotes: List<Footnote>, texts: List<String>): List<Triple<Int, Int, Footnote>> {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<Triple<Int, Int, Footnote>>()
        var from = 0
        for (f in footnotes) {
            val words = f.anchor.split(' ').filter { it.isNotEmpty() }
            val regex = if (words.isEmpty()) null else Regex(words.joinToString("\\s+") { Regex.escape(it) })
            var found: Pair<Int, Int>? = null
            if (regex != null) for (i in from until texts.size) { regex.find(texts[i])?.let { found = i to it.range.last + 1 }; if (found != null) break }
            val (at, offset) = found ?: (from to texts[from].length)
            from = at
            out += Triple(at, offset, f)
        }
        return out
    }

    /** Each paragraph's footnote texts, by paragraph index. */
    fun place(footnotes: List<Footnote>, texts: List<String>): Map<Int, List<String>> {
        val out = LinkedHashMap<Int, MutableList<String>>()
        for ((at, _, f) in locate(footnotes, texts)) out.getOrPut(at) { ArrayList() } += f.text
        return out
    }

    /** Where each paragraph shows its notes' labels, as (offset in the paragraph, label), in order; the transcript has no marker, so none is spoken. */
    fun markers(footnotes: List<Footnote>, texts: List<String>): Map<Int, List<Pair<Int, String>>> {
        val out = LinkedHashMap<Int, MutableList<Pair<Int, String>>>()
        for ((at, offset, f) in locate(footnotes, texts)) label(f.text)?.let { out.getOrPut(at) { ArrayList() } += offset to it }
        return out
    }

    /** The label a note opens with, as it is shown in the text: `[3]`, a symbol, or a plain number in brackets; null for an unlabelled note. */
    fun label(note: String): String? = marker(note)?.let { if (it.key[0].isDigit()) "[${it.key}]" else it.key }
}
