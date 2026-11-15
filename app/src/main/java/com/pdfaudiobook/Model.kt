/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

/**
 * One spoken sentence: where it sits in the audio, in milliseconds. [at] is the sentence's place in the book's text; -1 means its place in
 * the list. A book being made speaks out of order after a jump ([SpeakOrder]), so its stored list follows the audio, not the text.
 * A [pending] one is not in the audio yet: the reader shows it in its place, and its times are 0.
 */
data class Segment(val start: Long, val end: Long, val text: String, val at: Int = -1, val pending: Boolean = false)

data class Note(val id: String, val at: Long, val text: String)

enum class Status { QUEUED, READING, SPELLING, SPEAKING, ENCODING, READY, FAILED }

data class Book(
    val id: String,
    val title: String,
    val status: Status,
    val progress: Float,
    val error: String?,
    val durationMs: Long,
    val created: Long,
    val words: Int = 0,
    val wordsTotal: Int = 0,
    val pages: Int = 0,
    val pagesTotal: Int = 0,
    /** Words handed to the speech engines so far, which is what [words] spoken is a share of while the book is still being read. */
    val wordsQueued: Int = 0,
    /** Sample rate of the audio so far; set once speaking starts, so a book still being made can be played. */
    val rate: Int = 0,
    /** Chapter titles from the contents page, kept because cleaning drops the page itself. */
    val contents: List<String> = emptyList(),
    /** From the book's ISBN lookup; empty when there was none. */
    val author: String = "",
    /** Chapters estimated from the contents page before the main OCR ([ChapterEstimator]), each with the text that opens its page. */
    val estimates: List<ChapterEstimate> = emptyList(),
    /** The chapters found in the text, stored once so reading, speech and export agree; null until detected (a book stored before chapters were kept). */
    val chapters: List<ChapterMark>? = null,
    /** Where listening stopped (the start of the last paragraph played, in ms); null for a book never played. */
    val positionMs: Long? = null,
    /** Played to the end; the next paragraph played from anywhere clears it. */
    val finished: Boolean = false,
    /** Place in the import queue while QUEUED: the smaller goes first ([ImportQueue]). */
    val queueOrder: Long = 0,
    /** Where the book came from when it was not a file the reader picked, e.g. `gutenberg:98`; empty otherwise. */
    val source: String = "",
    /** Where the book's EPUB is fetched from when it was added from "Find books"; empty for a book whose file the reader picked. */
    val downloadUrl: String = "",
    /** True while [downloadUrl] is set and the EPUB is not on disk yet. Read from the book's folder, never stored: a half-written file does not count. */
    val awaitingDownload: Boolean = false,
) {
    /** Share of the book heard, as the library shows it; null for a book never played. */
    val playedPercent: Int? get() = Played.percent(positionMs, finished, durationMs, status == Status.READY, speechProgress)

    /** Audio exists to play, though generation may still be running. */
    val playable: Boolean get() = status == Status.READY || ((status == Status.READING || status == Status.SPEAKING || status == Status.ENCODING) && rate > 0 && durationMs > 0)

    /** The shelf lets the reader open a book once it is being read, even before any audio exists. */
    val openable: Boolean get() = status == Status.READY || (importing && status != Status.QUEUED)

    /** The import is running, so the book is not yet whole: the reader shows how much of it is read and spoken. */
    val importing: Boolean get() = status != Status.READY && status != Status.FAILED

    /** Share of the whole book whose pages have been read by OCR. */
    val readProgress: Float get() = when (status) {
        Status.QUEUED -> 0f
        Status.READING -> (pages.toFloat() / pagesTotal.coerceAtLeast(1)).coerceIn(0f, 1f)
        else -> 1f
    }

    /**
     * Share of the whole book that has audio. The total number of words is known only once the last page is read, so
     * until then it is estimated: the share of queued words already spoken, scaled by the share of pages read.
     */
    val speechProgress: Float get() = when {
        status == Status.QUEUED -> 0f
        wordsTotal > 0 -> (words.toFloat() / wordsTotal).coerceIn(0f, 1f)
        else -> (readProgress * words / wordsQueued.coerceAtLeast(1)).coerceIn(0f, readProgress)
    }

    /** Each phase shows its own percentage; a finished phase reads 100%. Reading and speaking overlap, so both can run at once. */
    fun label(): String = base() + (if (status == Status.FAILED) "" else playedPercent?.let { " · $it% played" }.orEmpty())

    private fun base(): String = when (status) {
        Status.QUEUED -> "Queued"
        Status.READING -> "Reading ${pages * 100 / pagesTotal.coerceAtLeast(1)}% · $pages of $pagesTotal pages" +
            (if (words > 0) " · Speaking ${"%,d".format(words)} words so far" else "") + if (playable) " · ready to play" else ""
        Status.SPELLING -> "Reading 100% · Checking spelling ${(progress * 100).toInt()}%"
        Status.SPEAKING -> "Reading 100% · Speaking ${words * 100 / wordsTotal.coerceAtLeast(1)}% · ${"%,d".format(words)} of ${"%,d".format(wordsTotal)} words"
        Status.ENCODING -> "Reading 100% · Speaking 100% · Encoding ${((progress - 0.9f) * 1000).toInt().coerceIn(0, 100)}%"
        Status.READY -> "Ready · ${clock(durationMs)}"
        Status.FAILED -> "Failed: $error"
    }
}

fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

object Timeline {
    /** The sentence being spoken at [ms]. */
    fun indexAt(segments: List<Segment>, ms: Long): Int {
        // The spoken sentence that starts last at or before [ms]; a book spoken in one run is in time order, one with jumps is not, and pending ones have no time.
        var best = -1
        for (i in segments.indices) {
            val s = segments[i]
            if (!s.pending && s.start <= ms && (best < 0 || s.start >= segments[best].start)) best = i
        }
        return best.coerceAtLeast(0)
    }
}

/** A chapter heading found in the transcript, as the sentence index and audio time where it starts. */
data class Chapter(val index: Int, val start: Long, val title: String)

/** A chapter as stored with the book: the sentence its heading is (an index speech never changes) and its title. Audio time is looked up from the transcript. */
data class ChapterMark(val index: Int, val title: String)

object Chapters {
    /** The chapters of the text alone, with no audio needed: [texts] are the sentences in reading order, spoken or not. */
    fun detect(texts: List<String>, saved: List<String> = emptyList(), estimates: List<ChapterEstimate> = emptyList()): List<ChapterMark> =
        find(texts.map { Segment(0, 0, it) }, saved, estimates).map { ChapterMark(it.index, it.title) }

    /** The marks with the audio time of each heading; 0 for one not spoken yet. */
    fun place(marks: List<ChapterMark>, segments: List<Segment>): List<Chapter> =
        marks.map { Chapter(it.index, segments.getOrNull(it.index)?.start ?: 0L, it.title) }

    private val heading = Regex(
        "^(chapter|part|book|section|preface|foreword|prologue|introduction|epilogue|afterword|conclusion|appendix|bibliography|glossary)(?:\\s+[\\p{Lu}\\d].{0,60})?$",
        RegexOption.IGNORE_CASE,
    )

    fun isHeading(t: String) = t.length <= 70 && heading.matches(t)

    private val numberWord = Regex(
        "^(?:ONE|TWO|THREE|FOUR|FIVE|SIX|SEVEN|EIGHT|NINE|TEN|ELEVEN|TWELVE|THIRTEEN|FOURTEEN|FIFTEEN|SIXTEEN|SEVENTEEN|EIGHTEEN|NINETEEN|TWENTY|LECTURE\\s+[IVXL]+)$",
    )

    /**
     * A chapter's number set as a word on its own line ("ONE" or "LECTURE I", then the title on the next line).
     * Two in a row are a contents list, not a heading.
     */
    fun isChapterNumber(lines: List<String>, i: Int) =
        numberWord.matches(lines[i].trim()) && lines.getOrNull(i - 1)?.trim()?.let(numberWord::matches) != true &&
            lines.getOrNull(i + 1)?.trim()?.let(numberWord::matches) != true

    private val pageReference = Regex(",\\s\\d{1,3}\\.")
    private val sentenceEnd = Regex("[.!?][\"”’]?\\s*$")

    /**
     * Whether only short lines (a title, a stray letter) separate line [from] from a paragraph of body text, not a contents entry.
     * A line of 61 to 99 characters is body text when it ends a sentence: the first sentence of a chapter can be that short,
     * while contents entries OCR joined into one line end in a title or a page number.
     */
    fun bodyFollows(lines: List<String>, from: Int): Boolean {
        for (j in from..minOf(from + 2, lines.lastIndex)) {
            val n = lines[j].trim().length
            if (n > 60) {
                val refs = pageReference.findAll(lines[j]).count()
                return if (n >= 100) refs < 3 else refs == 0 && sentenceEnd.containsMatchIn(lines[j])
            }
        }
        return false
    }

    /** A chapter number that opens body text, as opposed to a contents entry. */
    fun isOpening(lines: List<String>, i: Int) = isChapterNumber(lines, i) && bodyFollows(lines, i + 1)

    /** The title line under a chapter number: the next line, when it is short and not a sentence. */
    fun isChapterTitle(lines: List<String>, i: Int) =
        i > 0 && isOpening(lines, i - 1) && lines[i].length <= 70 && !lines[i].trimEnd().endsWith('.')

    /** Short unpunctuated lines after a "Contents" line: the chapter titles, and the index where they end. */
    fun contents(lines: List<String>): Pair<List<String>, Int> {
        val at = lines.indexOfFirst { it.trim().lowercase().removeSuffix(":") in setOf("contents", "table of contents") }
        if (at < 0) return emptyList<String>() to -1
        var end = at + 1
        while (end < lines.size && end <= at + 40) {
            val t = lines[end].trim()
            if (t.length > 70 || t.endsWith('.')) break
            end++
        }
        val titles = lines.subList(at + 1, end).map { it.trim() }.filter { it.length >= 8 && !isHeading(it) }
        return titles to end
    }

    /**
     * Headings are keyword lines ("Chapter 2", "Appendix A") plus titles listed on the contents page.
     * OCR often glues a title page to the paragraph after it, so a multi-word title also matches a sentence that starts with it.
     * A chapter number on its own line ("ONE") plus the title line under it is one heading.
     * [saved] is the contents read before cleaning, used when the transcript no longer has a contents page.
     * [estimates] are the chapters the pre-pass predicted ([ChapterEstimator]): one whose page opens with a heading the
     * scan above found keeps that heading; one whose heading was garbled or merged into a paragraph is marked at the
     * first paragraph of its page, which is how the estimate's anchors were taken.
     */
    fun find(segments: List<Segment>, saved: List<String> = emptyList(), estimates: List<ChapterEstimate> = emptyList()): List<Chapter> =
        withEstimates(scan(segments, saved), segments, estimates).map { it.copy(title = TitleCase.of(it.title)) }

    /** Whether [text] is the chapter's own heading, as opposed to a sentence that merely opens the chapter. */
    fun isTitleOf(chapter: Chapter, text: String) = norm(chapter.title) == norm(text)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The first segment at or after [from] that starts the way one of the [anchors] does. */
    private fun locate(segments: List<Segment>, anchors: List<String>, from: Int): Int? {
        val keys = anchors.map(::norm).filter { it.length >= 3 }
        for (i in from until segments.size) {
            val n = norm(segments[i].text)
            if (n.isEmpty()) continue
            if (keys.any { k -> n.startsWith(k.take(ANCHOR)) || (n.length >= 4 && k.startsWith(n)) || n == k }) return i
        }
        return null
    }

    private const val ANCHOR = 30

    private fun withEstimates(found: List<Chapter>, segments: List<Segment>, estimates: List<ChapterEstimate>): List<Chapter> {
        if (estimates.none { it.anchors.isNotEmpty() }) return found
        val out = found.toMutableList()
        var from = 0
        val at = ArrayList<Pair<ChapterEstimate, Int>>()
        for (e in estimates) {
            if (e.anchors.isEmpty()) continue
            val i = locate(segments, e.anchors, from) ?: continue
            at += e to i
            from = i
        }
        for ((k, p) in at.withIndex()) {
            val (e, i) = p
            val end = minOf(i + HEADING_WINDOW, (at.getOrNull(k + 1)?.second ?: Int.MAX_VALUE) - 1)
            // The heading, when the text has it, sits on the page's first paragraphs.
            if (out.none { it.index in i..end }) out += Chapter(i, segments[i].start, e.title)
        }
        return out.sortedBy { it.index }
    }

    /** A heading this many sentences after the page's first one still belongs to that page's chapter. */
    private const val HEADING_WINDOW = 3

    private fun scan(segments: List<Segment>, saved: List<String>): List<Chapter> {
        val (listed, tocEnd) = contents(segments.map { it.text })
        val (titles, from) = if (listed.isNotEmpty()) listed to tocEnd else saved to 0
        val texts = segments.map { it.text.trim() }
        val out = ArrayList<Chapter>()
        var i = 0
        while (i < segments.size) {
            val t = texts[i]
            val s = segments[i]
            when {
                isHeading(t) -> out += Chapter(i, s.start, t.trimEnd('.'))
                isOpening(texts, i) -> {
                    val title = if (i + 1 < texts.size && isChapterTitle(texts, i + 1)) texts[i + 1] else null
                    out += Chapter(i, s.start, listOfNotNull(t, title).joinToString(" "))
                    if (title != null) i++
                }
                titles.isNotEmpty() && i >= from -> titles.firstOrNull {
                    t.equals(it, true) || (it.count { c -> c == ' ' } >= 2 && t.startsWith("$it ", true))
                }?.let { out += Chapter(i, s.start, it) }
            }
            i++
        }
        return out
    }

    /** The chapter being read at sentence [index], or null before the first heading. */
    fun at(chapters: List<Chapter>, index: Int): Chapter? = chapters.lastOrNull { it.index <= index }
}
