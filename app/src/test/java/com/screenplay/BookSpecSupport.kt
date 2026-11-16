/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Fixtures and helpers for the per-book data specs (schemas/book, segments, notes, paragraph-notes, highlights, footnotes).
 * Every fixture is written by the app's own writer ([Library.writeMeta] and its siblings), never built as JSON by hand.
 */
internal object BookSpec {
    /** The repository root: the nearest folder upwards holding `schemas`. */
    val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "schemas").isDirectory }

    fun schema(name: String, version: Int = 1) = File(root, "schemas/$name/v$version.json")

    fun goldenDir(name: String, version: String) = File(root, "golden/$name/$version")

    /** Schema problems of [json] against `schemas/<name>/v1.json`, as found by the shared [SpecValidator]; empty when it is valid. */
    fun problems(name: String, json: String): List<String> = SpecValidator.validate(schema(name), json)

    val unicodeTitle = "Ünïcödé — 書名 «Ελληνικά» עברית 🎧📖 \"quoted\" back\\slash\ttab"
    val longTitle = "The Longest Plausible Title ".repeat(40).trim() // 1,119 characters
    private val longParagraph = "Sentence of a long paragraph, with commas; a semicolon, and “quotes”. ".repeat(25).trim() // about 1,700 characters

    private val rich = Book(
        id = "golden", title = "A Study in Scarlet", status = Status.READY, progress = 1f, error = null, durationMs = 28_463_120, created = 1_763_000_000_123,
        words = 43_210, wordsTotal = 43_210, pages = 188, pagesTotal = 188, wordsQueued = 43_210, rate = 24_000,
        contents = listOf("PART I", "Chapter 1. Mr. Sherlock Holmes", "Chapter 2. The Science of Deduction"), author = "Arthur Conan Doyle",
        estimates = listOf(
            ChapterEstimate("Mr. Sherlock Holmes", 1, 9, listOf("IN the year 1878 I took my degree")),
            ChapterEstimate("The Science of Deduction", null, null),
            ChapterEstimate("Preface", 3, null, emptyList()),
        ),
        chapters = listOf(ChapterMark(0, "Start"), ChapterMark(41, "Mr. Sherlock Holmes"), ChapterMark(97, "The Science of Deduction")),
        positionMs = 5_432_100, finished = true, queueOrder = 1_762_999_000_000, source = "gutenberg:244", downloadUrl = "https://example.org/ebooks/244.epub.noimages",
    )

    private fun status(s: Status, more: Book.() -> Book = { this }) = rich.copy(
        title = "State ${s.name}", status = s, progress = 0.35f, durationMs = 0, words = 120, wordsTotal = 0, pages = 7, pagesTotal = 0, wordsQueued = 400, rate = 0,
        contents = emptyList(), author = "", estimates = emptyList(), chapters = null, positionMs = null, finished = false, queueOrder = 0, source = "", downloadUrl = "",
    ).more()

    /** Books written by [Library.writeMeta], the writer behind every update. */
    val books: Map<String, Book> = linkedMapOf(
        "queued" to status(Status.QUEUED) { copy(progress = 0f, words = 0, pages = 0, wordsQueued = 0, queueOrder = 1_762_999_000_001) },
        "reading" to status(Status.READING) { copy(pagesTotal = 188, contents = listOf("Chapter 1"), estimates = listOf(ChapterEstimate("Chapter 1", 1, 9, listOf("It was"))) ) },
        "spelling" to status(Status.SPELLING) { copy(wordsTotal = 40_000, pagesTotal = 188, pages = 188) },
        "speaking" to status(Status.SPEAKING) { copy(wordsTotal = 40_000, pagesTotal = 188, pages = 188, rate = 24_000, durationMs = 61_000, progress = 0.5f) },
        "encoding" to status(Status.ENCODING) { copy(wordsTotal = 40_000, pagesTotal = 188, pages = 188, rate = 24_000, durationMs = 28_000_000, progress = 0.99f) },
        "ready-minimal" to status(Status.READY) { copy(progress = 1f, durationMs = 1_000, created = 1_763_000_000_000) },
        "ready-full" to rich,
        "ready-played" to rich.copy(finished = false, positionMs = 0, chapters = emptyList(), downloadUrl = "", source = ""),
        "failed" to status(Status.FAILED) { copy(error = "Could not read the PDF: Unable to open \"source.pdf\"\nline two") },
        "unicode-title" to status(Status.READY) { copy(title = unicodeTitle, author = "Žižek — Ôé 山田太郎", contents = listOf("第一章 🎧", "Глава 2"), progress = 1f) },
        "longest" to rich.copy(
            title = longTitle, author = "Author ".repeat(60).trim(), error = "e".repeat(2_000),
            contents = List(60) { "Chapter $it: $longTitle".take(200) },
            estimates = List(60) { ChapterEstimate("Estimate $it", it, it + 9, List(3) { a -> longParagraph.take(80 + a) }) },
            chapters = List(60) { ChapterMark(it * 10, "Chapter $it") },
            durationMs = Long.MAX_VALUE / 2, created = Long.MAX_VALUE / 4, positionMs = Long.MAX_VALUE / 8, queueOrder = Long.MAX_VALUE / 16,
            words = Int.MAX_VALUE, wordsTotal = Int.MAX_VALUE, pages = 100_000, pagesTotal = 100_000, wordsQueued = Int.MAX_VALUE, rate = 48_000,
            source = "gutenberg:" + "9".repeat(20), downloadUrl = "https://example.org/" + "a".repeat(1_500),
        ),
    )

    /** Written by the import path: [Library.importMeta] plus a status, as `import` does, and [Library.downloadMeta]. */
    val imports: Map<String, JSONObject> = linkedMapOf(
        "import-picked" to Library.importMeta("Picked file", 1_763_000_000_000, "").put("status", Status.QUEUED.name),
        "import-from-catalogue" to Library.importMeta("Found book", 1_763_000_000_001, "gutenberg:98").put("status", Status.QUEUED.name),
        "import-unreadable" to Library.importMeta("Unreadable ✗", 1_763_000_000_002, "").put("status", Status.FAILED.name).put("error", "Could not read the EPUB: permission denied"),
        "download-queued" to Library.downloadMeta("Queued download", 1_763_000_000_003, "gutenberg:1342", "https://example.org/1342.epub"),
    )

    /** Every book fixture as the text on disk. */
    fun metaTexts(): Map<String, String> = books.mapValues { Library.metaJson(it.value).toString() } + imports.mapValues { it.value.toString() }

    private val longSegments = List(20) { Segment(it * 90_000L, (it + 1) * 90_000L, longParagraph, it) }

    val segments: Map<String, List<Segment>> = linkedMapOf(
        "empty" to emptyList(),
        "plain" to listOf(Segment(0, 1020, "PREFACE"), Segment(1020, 9508, "PERHAPS the oldest subject")),
        "with-at" to listOf(Segment(0, 1020, "PREFACE", 0), Segment(1020, 9508, "PERHAPS the oldest subject", 1), Segment(9508, 9509, "", 2)),
        "unicode" to listOf(Segment(0, 5_000, "Ünïcödé — 書名 🎧 \"quoted\"\nsecond line\ttab", 0), Segment(5_000, 9_000, "Ελληνικά", 41)),
        "long" to longSegments,
        "huge-times" to listOf(Segment(0, Long.MAX_VALUE / 2, "A very long book", Int.MAX_VALUE)),
    )

    val notes: Map<String, List<Note>> = linkedMapOf(
        "empty" to emptyList(),
        "several" to listOf(Note("n3", 90_000, "Third"), Note("n1", 1_000, "First"), Note("n2", 5_000, "Second")), // the writer sorts by position
        "unicode" to listOf(Note("a1b2c3d4-0000-4000-8000-000000000001", 12_345, "Ünïcödé — 書名 🎧 \"quoted\"\nline two")),
        "long" to List(40) { Note("note-$it", it * 1_000L, longParagraph) },
    )

    val paragraphNotes: Map<String, List<ParagraphNote>> = linkedMapOf(
        "empty" to emptyList(),
        "several" to listOf(ParagraphNote(0, "PREFACE".hashCode(), "Why this"), ParagraphNote(41, -2_147_483_648, "Min hash"), ParagraphNote(97, 2_147_483_647, "Max hash")),
        "unicode" to listOf(ParagraphNote(3, ParagraphNotes.hash("書名"), "Ünïcödé 🎧\nline two")),
        "long" to List(40) { ParagraphNote(it, it * 31, longParagraph) },
    )

    val highlights: Map<String, List<ParagraphHighlight>> = linkedMapOf(
        "empty" to emptyList(),
        "every-colour" to HighlightColor.entries.mapIndexed { i, c -> ParagraphHighlight(i, ParagraphNotes.hash("p$i"), c) },
        "long" to List(120) { ParagraphHighlight(it, it * 17 - 4_000, HighlightColor.entries[it % 6]) },
    )

    val footnotes: Map<String, List<Footnote>> = linkedMapOf(
        "empty" to emptyList(),
        "several" to listOf(Footnote("1", "A footnote."), Footnote("contents", "Contents page text", true), Footnote("*", "Ünïcödé 書名 🎧\nline two")),
        "long" to List(40) { Footnote("$it", longParagraph, it % 7 == 0) },
    )

    /** One kind of file: its schema name, and how the read result is shown for comparison with an `*.expected.json`. */
    class Kind(val name: String, val show: (File) -> String)

    private fun array(items: List<JSONObject>) = JSONArray().also { a -> items.forEach { a.put(it) } }.toString()

    val kinds: Map<String, Kind> = listOf(
        Kind("book", { f -> showBook(Library.bookFromMeta("golden", JSONObject(f.readText()), hasEpub = false)).toString() }),
        Kind("segments", { f -> array(Library.readSegments(f)!!.map { JSONObject().put("start", it.start).put("end", it.end).put("text", it.text).put("at", it.at) }) }),
        Kind("notes", { f -> array(Library.readNotes(f).map { JSONObject().put("id", it.id).put("at", it.at).put("text", it.text) }) }),
        Kind("paragraph-notes", { f -> array(ParagraphNotes.read(f).map { JSONObject().put("index", it.index).put("hash", it.hash).put("text", it.text) }) }),
        Kind("highlights", { f -> array(Highlights.read(f).map { JSONObject().put("index", it.index).put("hash", it.hash).put("color", it.color.name) }) }),
        Kind("footnotes", { f -> array(Library.readFootnotes(f).map { JSONObject().put("anchor", it.anchor).put("text", it.text).put("contents", it.contents) }) }),
    ).associateBy { it.name }

    /** Every list fixture, written by the app's writer into [dir] as the text on disk, keyed by kind then name. */
    fun listTexts(dir: File): Map<String, Map<String, String>> {
        fun <T> written(items: Map<String, List<T>>, write: (File, List<T>) -> Unit) = items.mapValues { (k, v) -> File(dir, "w-$k.json").also { write(it, v) }.readText() }
        return mapOf(
            "segments" to written(segments, Library::writeSegments),
            "notes" to written(notes, Library::writeNotes),
            "paragraph-notes" to written(paragraphNotes, ParagraphNotes::write),
            "highlights" to written(highlights, Highlights::write),
            "footnotes" to written(footnotes, Library::writeFootnotes),
        )
    }

    /** Everything the reader gets from `meta.json`, one key per field of [Book] that is read from the file. */
    fun showBook(b: Book): JSONObject = JSONObject().put("id", b.id).put("title", b.title).put("author", b.author).put("status", b.status.name)
        .put("progress", b.progress.toDouble()).put("durationMs", b.durationMs).put("created", b.created).put("words", b.words).put("wordsTotal", b.wordsTotal)
        .put("pages", b.pages).put("pagesTotal", b.pagesTotal).put("wordsQueued", b.wordsQueued).put("rate", b.rate)
        .put("error", b.error ?: JSONObject.NULL).put("contents", JSONArray(b.contents))
        .put("estimates", JSONArray(b.estimates.map { JSONObject().put("title", it.title).put("printed", it.printed ?: JSONObject.NULL).put("page", it.page ?: JSONObject.NULL).put("anchors", JSONArray(it.anchors)) }))
        .put("chapters", b.chapters?.let { c -> JSONArray(c.map { JSONObject().put("index", it.index).put("title", it.title) }) } ?: JSONObject.NULL)
        .put("positionMs", b.positionMs ?: JSONObject.NULL).put("finished", b.finished).put("queueOrder", b.queueOrder).put("source", b.source)
        .put("downloadUrl", b.downloadUrl).put("awaitingDownload", b.awaitingDownload)
}
