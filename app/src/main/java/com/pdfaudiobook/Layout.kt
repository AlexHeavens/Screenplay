/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

/** One OCR line: its box's top and bottom in page pixels, and its text; [left] is the box's left edge (0 where not read). */
data class OcrLine(val top: Int, val bottom: Int, val text: String, val left: Int = 0) {
    val height get() = bottom - top
}

/**
 * Picks the text to read out of one page's OCR blocks, using where they sit and how big their type is.
 * Leaves out running heads and page numbers at the edges, footnotes, and index or contents pages.
 */
object Layout {
    private const val TOP = 0.06
    private const val BOTTOM = 0.95
    /** A running head can sit this far down a page with a deep top margin. */
    private const val HEAD_BAND = 0.12
    /** Type smaller than this share of the body's is a footnote. */
    private const val SMALL = 0.85
    /** A page where this share of lines end in a page number is an index or a table of contents. */
    private const val REFERENCES = 0.4
    private const val MIN_INDEX_LINES = 10

    private val softHyphen = Regex("(?<=\\p{Ll})-\\s+(?=\\p{Ll})")
    private val endsInPageNumber = Regex("\\d{1,3}[.,:;]?$")
    private val noteNumber = Regex("^\\d{1,3}\\s?\\S")

    fun paragraphs(blocks: List<List<OcrLine>>, pageHeight: Int): List<String> =
        joinDropCaps(body(blocks, pageHeight)).map(::text)

    private fun text(block: List<OcrLine>) = block.joinToString(" ") { it.text }.replace(softHyphen, "")

    /**
     * The page's paragraphs to read. With [footnotes], the notes at the foot of the page are kept as annotations ([Footnotes]) instead of
     * dropped, and a note the type size and place missed is found by its label; without, the page reads as [paragraphs] returns it.
     */
    fun page(blocks: List<List<OcrLine>>, pageHeight: Int, footnotes: Boolean): PageText {
        val (body, notes) = split(blocks, pageHeight)
        val paragraphs = joinDropCaps(body).map(::text)
        val page = if (footnotes) Footnotes.split(paragraphs, notes.map(::text)) else PageText(paragraphs)
        return page.copy(lines = ChapterEstimator.lines(blocks))
    }

    /**
     * Puts a chapter's large first letter back on its word. OCR returns the letter as its own block, in any order
     * ("P" after "ERHAPS the oldest"), so it is matched by position: level with the first line of a block that
     * opens in small capitals.
     */
    fun joinDropCaps(blocks: List<List<OcrLine>>): List<List<OcrLine>> {
        val out = blocks.toMutableList()
        for (cap in blocks) {
            val letter = cap.singleOrNull()?.takeIf { Text.dropCap.matches(it.text.trim()) } ?: continue
            val i = out.indexOfFirst { b ->
                b !== cap && b.first().let { it.top <= letter.top && letter.top < it.bottom } &&
                    Text.smallCaps.containsMatchIn(b.first().text)
            }
            if (i < 0) continue
            val b = out[i]
            out[i] = listOf(b.first().copy(text = letter.text.trim().first() + b.first().text)) + b.drop(1)
            out.removeAt(out.indexOfFirst { it === cap })
        }
        return out
    }

    fun body(blocks: List<List<OcrLine>>, pageHeight: Int): List<List<OcrLine>> = split(blocks, pageHeight).first

    /** The blocks to read, and the footnote blocks below them. */
    fun split(blocks: List<List<OcrLine>>, pageHeight: Int): Pair<List<List<OcrLine>>, List<List<OcrLine>>> {
        val lines = blocks.flatten()
        if (lines.isEmpty() || isIndex(lines)) return emptyList<List<OcrLine>>() to emptyList()
        val h = pageHeight.toDouble()
        // The body's type size: the longest block is almost always body text.
        val bodyHeight = median(blocks.maxBy { it.size })
        val pageTop = lines.minOf { it.top }

        fun isEdge(b: List<OcrLine>) = b.first().top < h * TOP || b.last().bottom > h * BOTTOM
        // A running head is one short line level with the topmost line on the page; it is never the lowercase end of a paragraph.
        fun isRunningHead(b: List<OcrLine>) = b.size == 1 && b[0].top < h * HEAD_BAND &&
            b[0].top - pageTop < bodyHeight && b[0].text.firstOrNull { it.isLetterOrDigit() }?.isLowerCase() != true
        fun isSmall(b: List<OcrLine>) = median(b) < bodyHeight * SMALL

        val bodyBottom = blocks.filter { it.size > 1 && !isSmall(it) }.maxOfOrNull { it.last().bottom } ?: 0
        // Footnotes sit below the body in smaller type, and the first one starts with its number; any block below it is a note too.
        val notesTop = blocks.filter {
            it.first().top > h / 2 && it.first().top >= bodyBottom && isSmall(it) &&
                (it.size > 1 || noteNumber.containsMatchIn(it[0].text))
        }.minOfOrNull { it.first().top }

        val read = blocks.filter { b -> !isEdge(b) && !isRunningHead(b) }
        return read.filter { notesTop == null || it.first().top < notesTop } to read.filter { notesTop != null && it.first().top >= notesTop }
    }

    private fun isIndex(lines: List<OcrLine>) =
        lines.size >= MIN_INDEX_LINES && lines.count { endsInPageNumber.containsMatchIn(it.text.trim()) } >= lines.size * REFERENCES

    private fun median(block: List<OcrLine>) = block.map { it.height }.sorted()[block.size / 2]
}
