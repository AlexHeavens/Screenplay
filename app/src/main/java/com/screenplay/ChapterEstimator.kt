/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

/** One page as OCR returned it, before [Layout] drops anything: [page] counts from 1 in the PDF, [blocks] hold every line with its box. */
data class OcrPage(val page: Int, val height: Int, val blocks: List<List<OcrLine>>)

/**
 * A chapter as the contents page lists it. [page] is where the chapter opens in the PDF, from the printed number on the
 * contents page plus the book's offset; null when either is unknown. [anchors] are the first paragraphs the main OCR read
 * from that page, which [Chapters.find] looks for in the transcript.
 */
data class ChapterEstimate(val title: String, val printed: Int?, val page: Int?, val anchors: List<String> = emptyList())

/** What the pre-pass found: the chapters, the pages the contents list fills (kept out of the spoken text), and the offset used. */
data class ChapterPlan(val chapters: List<ChapterEstimate>, val contentsPages: Set<Int>, val offset: Int?) {
    /** The plan with the first paragraphs of PDF page [page] noted as the anchors of the chapters predicted to open there. */
    fun withAnchors(page: Int, paragraphs: List<String>): ChapterPlan =
        if (chapters.none { it.page == page }) this
        else copy(chapters = chapters.map { if (it.page == page) it.copy(anchors = paragraphs.take(ANCHORS).map { p -> p.take(80) }) else it })

    companion object {
        /** How many of a page's first paragraphs mark where a chapter opens: a heading, its number and the first line may be separate. */
        const val ANCHORS = 3

        val NONE = ChapterPlan(emptyList(), emptySet(), null)
    }
}

/**
 * The pre-pass: estimates a scanned book's chapters from its first pages, before the main OCR reads the whole book.
 * It finds the contents page, pairs each title with its printed page number, works out how far the printed numbering
 * is from the PDF's page numbers, and predicts the PDF page where each chapter opens.
 *
 * It reads lines by position, not by OCR block: OCR often returns the numbers, number words and titles of one contents
 * page as separate blocks in no useful order, but they still sit on one row.
 */
object ChapterEstimator {
    /** How many pages the pre-pass reads. */
    const val PAGES = 30

    private const val MIN_ENTRIES = 3
    private const val MAX_CONTENTS_PAGES = 4
    private const val DESCRIPTION_LENGTH = 45

    private val numberWords = listOf(
        "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN", "ELEVEN", "TWELVE", "THIRTEEN",
        "FOURTEEN", "FIFTEEN", "SIXTEEN", "SEVENTEEN", "EIGHTEEN", "NINETEEN", "TWENTY",
    )
    private val contentsHeading = Regex("^(?:table of )?contents?\\b[\\p{P}\\s]*(?:of\\s+(?:vol(?:ume)?\\.?\\s*)?[ivx\\d]+[\\p{P}\\s]*)?$", RegexOption.IGNORE_CASE)
    private val columnHeading = Regex("^(?:chap(?:ter)?s?\\.?|page|pages|p\\.|contents)(?:\\s+(?:page|pages))?\\.?$", RegexOption.IGNORE_CASE)
    private val runningContents = Regex("^(?:[ivxlc\\d]{1,7}\\s+)?contents?\\W*(?:[ivxlc\\d]{1,7})?$", RegexOption.IGNORE_CASE)
    /** "LECTURES IV AND V": one heading over several numbers. */
    private val pluralLabel = Regex("^((?:lectures|chapters|parts|books)\\s+(?-i:[IVXLC]+)(?:\\s*,\\s*(?-i:[IVXLC]+))*(?:,?\\s+and\\s+(?-i:[IVXLC]+))?)\\b[.:]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val pageRef = Regex(",\\s\\d{1,3}[.;]")
    private val keywordLabel = Regex("^((?:chap\\s?ter|chap\\.?|lecture|part|book|section)\\s+(?:[IVXLC]+|\\d{1,3}|[A-Z][a-z]+))\\b[.:]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val wordLabel = Regex("^(${numberWords.joinToString("|")})\\b[.:]?\\s*(.*)$")
    private val romanLabel = Regex("^([IVXLC]{1,6})[.:]\\s+(.+)$")
    private val digitLabel = Regex("^(\\d{1,2})[.)]\\s+(.+)$")
    private val trailingPage = Regex("^(.*?[\\p{L}\\p{N}?!\"'”’)])[\\s.·…•_\\-–—]*?[\\s.·…•_\\-–—](\\d{1,4})\\.?$")
    /** A lower-case Roman page ("vii") counts only after a leader dot, so "A civil" does not lose its last word. */
    private val romanPage = Regex("^(.*?[\\p{L}\\p{N}?!\"'”’)])\\s*[.·…_]\\s*[.·…_\\s]*([ivxlc]{1,7})\\.?$")
    /** A page range ("7-43", "Page 60-79") closes a chapter whose description spans several rows: its first number is where the chapter opens. */
    private val rangePage = Regex("^(.*?)\\s*(?:pages?\\s+)?(\\d{1,4})\\s*[-–—]\\s*(\\d{1,4})$", RegexOption.IGNORE_CASE)
    private val pageWord = Regex("^pages?\\s+(\\d{1,4})$", RegexOption.IGNORE_CASE)
    private val trailingPageWord = Regex("\\s+pages?$", RegexOption.IGNORE_CASE)
    private val bareNumber = Regex("^\\W*(\\d{1,4})\\W*$")
    private val headNumber = Regex("^\\W*(\\d{1,4})\\s+\\p{L}.*$")
    private val tailNumber = Regex("^\\p{L}.*\\s(\\d{1,4})\\W*$")
    private val wordConnector = Regex("\\b(?:of|the|and|to|in|on|or|a|an|for|with|from|by|at)$", RegexOption.IGNORE_CASE)
    private val connector = Regex("(?:,|-|\\b(?:of|the|and|to|in|on|or|a|an|for|with|from|by|at))$", RegexOption.IGNORE_CASE)

    /** A line of the contents page as read off one row: an optional label ("CHAPTER I", "ONE"), a title, and a page. */
    private data class Entry(val label: String?, val title: String, val printed: Int?, val front: Boolean = false)

    /** The lines that sit on one row of the page, left to right, as one line of text. */
    private data class Row(val top: Int, val text: String)

    fun plan(pages: List<OcrPage>): ChapterPlan {
        val sorted = pages.sortedBy { it.page }
        val first = sorted.firstOrNull { p -> rows(p).any { contentsHeading.matches(clean(it.text)) } } ?: return ChapterPlan.NONE
        val contentsPages = mutableSetOf(first.page)
        val entries = ArrayList<Entry>()
        val headingTop = rows(first).first { contentsHeading.matches(clean(it.text)) }.top
        entries += parse(rows(first).filter { it.top > headingTop })
        if (select(entries).isNotEmpty()) {
            var next = first.page + 1
            while (contentsPages.size <= MAX_CONTENTS_PAGES) {
                val p = sorted.firstOrNull { it.page == next } ?: break
                val more = select(parse(rows(p)))
                if (more.count { it.printed != null } < 2 || lastPaged(select(entries))?.let { l -> (more.first { it.printed != null }.printed ?: 0) < l } == true) break
                contentsPages += p.page
                entries += more
                next++
            }
        }
        val chapters = monotone(select(entries))
        if (chapters.count { it.printed != null } < 2 && chapters.size < MIN_ENTRIES) return ChapterPlan.NONE
        val body = sorted.filter { it.page !in contentsPages && it.page > contentsPages.max() }
        val offset = offset(body, chapters)
        return ChapterPlan(
            chapters.map { e ->
                val p = when {
                    e.printed != null -> offset?.let { (e.printed + it).takeIf { p -> p >= 1 } }
                    // OCR lost this entry's page number: the page where its title stands on a line of its own opens it.
                    else -> opening(body, e)
                }
                ChapterEstimate(listOfNotNull(e.label, e.title).joinToString(" "), e.printed ?: p?.let { q -> offset?.let { q - it } }, p)
            },
            contentsPages, offset,
        )
    }

    private fun lastPaged(entries: List<Entry>) = entries.lastOrNull { it.printed != null }?.printed

    /** The printed number of a page, read from a line at its top or bottom edge, or null where there is none ("2 INTRODUCTION." counts). */
    fun pageNumber(p: OcrPage): Int? {
        val h = p.height.toDouble()
        val all = rows(p)
        // A running head sits near the top edge but can be a tenth of the page down: only the first two rows and the last two count.
        val edge = all.take(2).filter { it.top < h * 0.13 } + all.takeLast(2).filter { it.top > h * 0.9 }
        for (r in edge) {
            val t = r.text.trim()
            if (t.length > 70) continue
            val n = (bareNumber.matchEntire(t) ?: headNumber.matchEntire(t) ?: tailNumber.matchEntire(t))?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && n in 1..2500) return n
        }
        return null
    }

    /** The lines of [p] grouped by the row they sit on, top to bottom; the lines of a row are ordered by their left edge. */
    private fun rows(p: OcrPage): List<Row> = rows(p.blocks)

    /** A page's lines as text, one per row of the page, top to bottom: the label, title and number of one entry that OCR split into blocks come back as one line. */
    fun lines(blocks: List<List<OcrLine>>): List<String> = rows(blocks).map { clean(it.text) }

    private fun rows(blocks: List<List<OcrLine>>): List<Row> {
        val lines = blocks.flatten().filter { it.text.isNotBlank() }.sortedBy { it.top + it.bottom }
        if (lines.isEmpty()) return emptyList()
        val tolerance = lines.map { it.height }.sorted()[lines.size / 2] * 0.6
        val out = ArrayList<Row>()
        var group = ArrayList<OcrLine>()
        var centre = 0.0
        fun flush() {
            if (group.isNotEmpty()) out += Row(group.minOf { it.top }, group.sortedBy { it.left }.joinToString(" ") { it.text.trim() })
        }
        for (l in lines) {
            val c = (l.top + l.bottom) / 2.0
            if (group.isNotEmpty() && c - centre > tolerance) { flush(); group = ArrayList() }
            group += l
            centre = group.map { (it.top + it.bottom) / 2.0 }.average()
        }
        flush()
        return out
    }

    private fun clean(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private fun parse(rows: List<Row>): List<Entry> {
        val raw = ArrayList<Entry>()
        var pendingLabel: String? = null
        var pendingTitle: String? = null
        for (row in rows) {
            var text = clean(row.text)
            if (text.length < 2 || runningContents.matches(text) || columnHeading.matches(text) || text.uppercase().trim { !it.isLetter() } == "CONTENTS") continue
            // A row of appendix sub-entries ("A. The Political ..., 327. B. Internal ..., 332.") is not a chapter.
            if (Regex(",\\s\\d{1,3}\\.").findAll(text).count() >= 2) continue
            // A range or "Page N" row, alone or after a description line, gives the open chapter its page.
            val range = rangePage.matchEntire(text)?.takeIf { it.groupValues[3].toInt() >= it.groupValues[2].toInt() && it.groupValues[1].count { c -> c.isLetter() } < 80 }
            val pageOnly = pageWord.matchEntire(text)
            val given = range?.groupValues?.get(2)?.toIntOrNull() ?: pageOnly?.groupValues?.get(1)?.toIntOrNull()
            val open = raw.indexOfLast { it.label != null }
            if (given != null && open >= 0 && raw[open].printed == null && !raw[open].front) {
                raw[open] = raw[open].copy(printed = given); continue
            }
            var label: String? = null
            var rest = text
            var wrapped = false
            for (re in listOf(pluralLabel, keywordLabel, wordLabel, romanLabel, digitLabel)) {
                val m = re.matchEntire(rest) ?: continue
                label = m.groupValues[1].trim().trimEnd('.', ':'); rest = m.groupValues[2].trim()
                // "28. We confine ourselves ..., 29. Definition" is a wrapped description that starts with a page reference.
                if (re === digitLabel && pageRef.containsMatchIn(rest)) { label = null; rest = text; wrapped = true }
                break
            }
            if (wrapped) continue
            // A row of a label alone ("CHAPTER I.") waits for the row with its title.
            if (label != null) pendingTitle = null
            if (label != null && rest.isEmpty()) { pendingLabel = label; continue }
            if (label == null) { label = pendingLabel; pendingLabel = null } else pendingLabel = null
            var printed: Int? = null
            var front = false
            val m = trailingPage.matchEntire(rest)
            if (m != null) {
                printed = m.groupValues[2].toInt()
                rest = m.groupValues[1]
            } else romanPage.matchEntire(rest)?.let { rest = it.groupValues[1]; front = true }
            val title = (pendingTitle?.let { "$it " } ?: "") + rest.trim().trim('.', ',', ':', ' ', '·', '…').replace(trailingPageWord, "")
            pendingTitle = null
            if (title.count { it.isLetter() } < 2) continue
            // A title that wraps: its first row ends mid-phrase and has no page, so it waits for the next row.
            if (printed == null && !front && (if (label != null) wordConnector else connector).containsMatchIn(rest.trim())) { pendingTitle = title; pendingLabel = label; continue }
            raw += Entry(label, title, printed, front)
        }
        return raw
    }

    /**
     * The rows that are entries. Rows without a page count only on a contents page that has no page numbers at all, or
     * when they carry a label. Where chapters carry labels, an unlabelled row that is long or starts in lower case is the
     * description of the chapter above it, whatever it ends in.
     */
    private fun select(raw: List<Entry>): List<Entry> {
        val described = raw.count { it.label != null } >= MIN_ENTRIES
        val entries = if (described) raw.filter { it.label != null || (it.title.length <= DESCRIPTION_LENGTH && it.title.first().isUpperCase()) } else raw
        return if (described || raw.count { it.printed != null } >= MIN_ENTRIES) entries.filter { it.printed != null || it.label != null || it.front }
        else raw.filter { it.title.length in 3..70 || it.label != null }
    }

    /** Page numbers rise through a book; a number that falls back is a misread, so that entry keeps its title and loses its page. */
    private fun monotone(entries: List<Entry>): List<Entry> {
        var last = 0
        return entries.map { e ->
            val p = e.printed
            if (p == null) e else if (p >= last) { last = p; e } else e.copy(printed = null)
        }
    }

    /**
     * PDF page minus printed page. Pages whose running number OCR reads vote for their difference; where no number is
     * read (front matter in Roman numerals, opening pages that print none), the page where the first chapter's title
     * stands alone on a line votes instead. The most common difference wins; a lone vote counts.
     */
    private fun offset(pages: List<OcrPage>, entries: List<Entry>): Int? {
        val numbered = pages.mapNotNull { p -> pageNumber(p)?.let { p.page - it } }
        val votes = numbered.groupingBy { it }.eachCount()
        val best = votes.maxByOrNull { it.value }
        if (best != null && (best.value >= 2 || votes.size == 1)) return best.key
        val e = entries.firstOrNull { it.printed != null } ?: return best?.key
        return opening(pages, e)?.let { it - e.printed!! } ?: best?.key
    }

    /** The first page whose body holds [e]'s title on a line of its own: where the chapter opens, not its running head at the top. */
    private fun opening(pages: List<OcrPage>, e: Entry): Int? {
        val want = norm(e.title)
        if (want.length < 4) return null
        val full = norm(listOfNotNull(e.label, e.title).joinToString(" "))
        return pages.firstOrNull { p ->
            rows(p).any { r -> r.top > p.height * 0.08 && r.top < p.height * 0.92 && norm(r.text).let { n -> n == want || n == full } }
        }?.page
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
