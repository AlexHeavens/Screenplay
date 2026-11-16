/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Each book is a folder under filesDir/books holding exactly the files a backup carries. */
object Files {
    const val PDF = "source.pdf"
    /** An EPUB import keeps the EPUB here instead of a PDF. */
    const val EPUB = "source.epub"
    const val AUDIO = "audio.m4a"
    const val TRANSCRIPT = "transcript.json"
    const val NOTES = "notes.json"
    const val PARAGRAPH_NOTES = "paragraph-notes.json"
    const val HIGHLIGHTS = "highlights.json"
    /** A scanned book's footnotes, as annotations of its paragraphs ([Footnotes]). A book without the file has none. */
    const val FOOTNOTES = "footnotes.json"
    /** Sentences read so far, one per line, while a book is made; speech trails behind it. Gone once the book is ready. */
    const val QUEUE = "queue.txt"
    /** Where speech of a book being made goes on from, after a jump ([Speaking]). */
    const val JUMP = "jump.txt"
    const val META = "meta.json"
}

object Library {
    private val state = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Saves moves one at a time, in the order they were dropped, off the main thread. */
    private val moveWrites = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { Thread(it, "library-move").apply { isDaemon = true } }.asCoroutineDispatcher())
    /** Guards [state] and [unwritten]: held only for in-memory work, so the main thread never waits on disk through it. */
    private val stateLock = Any()
    /** Moves shown on the shelf that are not yet written to the books' folders; a refresh from disk keeps showing them. */
    private val unwritten = ArrayList<ImportQueue.Reorder>()
    private val moveFailed = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Why a move could not be saved, one message each; the shelf has already shown the old order again. */
    val moveFailures: SharedFlow<String> = moveFailed

    fun dir(ctx: Context, id: String) = File(ctx.filesDir, "books/$id")

    private val pendingState = MutableStateFlow<List<PendingImport>>(emptyList())

    /** Picked files not yet in the queue, oldest first; the shelf lists them under "Importing" after the books in line. */
    val pending: StateFlow<List<PendingImport>> = pendingState

    /** The title a file gets: its display name without the file type. */
    private fun titleOf(name: String?) = (name ?: "Untitled").removeSuffix(".pdf").removeSuffix(".epub").removeSuffix(".zip")

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null } ?: uri.lastPathSegment // A file:// link has no display name.

    /**
     * Lists [uri] as a file being checked at once, before anything is read, and returns its key for [step], [fail] and [finish]. The title is
     * the file's name, read off the main thread: until then the last part of the link stands in for it.
     */
    fun begin(ctx: Context, uri: Uri): String {
        val key = UUID.randomUUID().toString()
        val first = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        pendingState.update { it + PendingImport(key, titleOf(first), PendingStep.CHECKING) }
        scope.launch { displayName(ctx, uri)?.let { name -> changePending(key) { it.copy(title = titleOf(name)) } } }
        return key
    }

    private fun changePending(key: String, change: (PendingImport) -> PendingImport) = pendingState.update { l -> l.map { if (it.key == key) change(it) else it } }

    /** Moves the pending file to [step], under [title] when the real one is known. */
    fun step(key: String, step: PendingStep, title: String? = null) = changePending(key) { it.copy(step = step, title = title ?: it.title) }

    /** Remembers where the reader dropped the pending file: [place] books above it. The file takes that place in the queue once it is a book. */
    fun placePending(key: String, place: Int) = changePending(key) { it.copy(place = place) }

    /** Keeps the pending file's row with [error] until [dismiss]; the file did not become a book. */
    fun fail(key: String, error: String) = changePending(key) { it.copy(step = null, error = error) }

    /** Drops the row of a file that is now a book (or whose failure the reader has seen). */
    fun finish(key: String) = pendingState.update { l -> l.filter { it.key != key } }

    @Synchronized
    fun refresh(ctx: Context) {
        val dirs = File(ctx.filesDir, "books").listFiles() ?: emptyArray()
        val read = dirs.mapNotNull { readBook(it) }.sortedByDescending { it.created }
        synchronized(stateLock) {
            state.value = unwritten.fold(ImportQueue.showStoppingAsWaiting(read)) { books, move -> move.applyTo(books) }
        }
    }

    /** The version `meta.json` is written with (schemas/book/v1.json). A file without it is version 1; a reader ignores fields it does not know. */
    const val META_VERSION = 1

    internal fun readBook(dir: File): Book? {
        val f = File(dir, Files.META)
        if (!f.exists()) return null
        return bookFromMeta(dir.name, JSONObject(f.readText()), File(dir, Files.EPUB).exists())
    }

    /** The book [j] describes. A field it lacks gets the default here: that default is part of the stored format (docs/data-specs.md). */
    internal fun bookFromMeta(id: String, j: JSONObject, hasEpub: Boolean): Book {
        return Book(
            id = id,
            title = j.getString("title"),
            author = j.optString("author"),
            status = Status.valueOf(j.getString("status")),
            progress = j.optDouble("progress", 0.0).toFloat(),
            error = if (j.has("error")) j.getString("error") else null,
            durationMs = j.optLong("duration"),
            created = j.optLong("created"),
            words = j.optInt("words"),
            wordsTotal = j.optInt("wordsTotal"),
            pages = j.optInt("pages"),
            pagesTotal = j.optInt("pagesTotal"),
            wordsQueued = j.optInt("wordsQueued"),
            rate = j.optInt("rate"),
            contents = j.optJSONArray("contents")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            estimates = j.optJSONArray("estimates")?.let { a -> List(a.length()) { estimate(a.getJSONObject(it)) } } ?: emptyList(),
            chapters = j.optJSONArray("chapters")?.let { a -> List(a.length()) { a.getJSONObject(it).let { o -> ChapterMark(o.getInt("index"), o.getString("title")) } } },
            positionMs = if (j.has("position")) j.getLong("position") else null,
            finished = j.optBoolean("finished"),
            queueOrder = j.optLong("order"),
            source = j.optString("source"),
            downloadUrl = j.optString("download"),
            awaitingDownload = j.optString("download").isNotEmpty() && !hasEpub,
        )
    }

    private fun estimate(o: JSONObject) = ChapterEstimate(
        o.getString("title"),
        if (o.isNull("printed")) null else o.getInt("printed"),
        if (o.isNull("page")) null else o.getInt("page"),
        o.optJSONArray("anchors")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
    )

    @Synchronized
    fun update(ctx: Context, id: String, change: (Book) -> Book) {
        val dir = dir(ctx, id)
        val book = readBook(dir) ?: return
        val b = change(book)
        writeMeta(dir, b)
        refresh(ctx)
    }

    /** Writes [b] as the book's `meta.json` in [dir]. */
    internal fun writeMeta(dir: File, b: Book) = writeAtomically(File(dir, Files.META), metaJson(b).toString())

    internal fun metaJson(b: Book): JSONObject {
        val j = JSONObject().put("version", META_VERSION).put("title", b.title).put("author", b.author).put("status", b.status.name)
            .put("progress", b.progress.toDouble()).put("duration", b.durationMs).put("created", b.created)
            .put("words", b.words).put("wordsTotal", b.wordsTotal).put("pages", b.pages).put("pagesTotal", b.pagesTotal).put("wordsQueued", b.wordsQueued).put("rate", b.rate)
            .put("contents", JSONArray(b.contents))
            .put("estimates", JSONArray(b.estimates.map { e ->
                JSONObject().put("title", e.title).put("printed", e.printed ?: JSONObject.NULL).put("page", e.page ?: JSONObject.NULL)
                    .put("anchors", JSONArray(e.anchors))
            }))
        b.chapters?.let { c -> j.put("chapters", JSONArray(c.map { JSONObject().put("index", it.index).put("title", it.title) })) }
        b.error?.let { j.put("error", it) }
        b.positionMs?.let { j.put("position", it) }
        if (b.finished) j.put("finished", true)
        if (b.queueOrder != 0L) j.put("order", b.queueOrder)
        if (b.source.isNotEmpty()) j.put("source", b.source)
        if (b.downloadUrl.isNotEmpty()) j.put("download", b.downloadUrl)
        return j
    }

    /** What a book's `meta.json` holds from the moment its file is picked, before it is read; [status] and any `error` follow. */
    internal fun importMeta(title: String, now: Long, source: String): JSONObject =
        JSONObject().put("version", META_VERSION).put("title", title).put("created", now).put("order", now).also { if (source.isNotEmpty()) it.put("source", source) }

    /** The same for a catalogue book added to the queue without its file. */
    internal fun downloadMeta(title: String, now: Long, source: String, url: String): JSONObject =
        JSONObject().put("version", META_VERSION).put("title", title).put("created", now).put("order", now)
            .put("source", source).put("download", url).put("status", Status.QUEUED.name)

    /** Written beside and renamed over, so a kill mid-write leaves the old file, never half of the new one. */
    fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Remembers the listening place: the paragraph start [ms], or the end of a [finished] book. */
    fun savePosition(ctx: Context, id: String, ms: Long, finished: Boolean) = update(ctx, id) { it.copy(positionMs = ms, finished = finished) }

    /**
     * Finds the book's chapters in its text, spoken or not, and stores them. Speech and export read the stored list instead of detecting
     * their own, so every view of the book names the same chapters. Slow on a long book: call it off the main thread.
     */
    fun detectChapters(ctx: Context, id: String): List<ChapterMark> {
        val book = readBook(dir(ctx, id)) ?: return emptyList()
        val marks = Chapters.detect(bookSegments(ctx, id).map { it.text }, book.contents, book.estimates)
        update(ctx, id) { it.copy(chapters = marks) }
        return marks
    }

    /** The stored chapters with their audio times; a book stored before chapters were kept gets them detected on this first call. */
    fun chapters(ctx: Context, id: String, segments: List<Segment> = bookSegments(ctx, id)): List<Chapter> =
        Chapters.place(readBook(dir(ctx, id))?.chapters ?: detectChapters(ctx, id), segments)

    /** The title of the chapter [book] is at, from where listening stopped ("Start" before the first heading); null for a book never played or with no transcript. Reads files: call it off the main thread. */
    fun currentChapter(ctx: Context, book: Book): String? {
        if (book.positionMs == null && !book.finished) return null
        val segments = bookSegments(ctx, book.id)
        if (segments.isEmpty()) return null
        val index = if (book.finished) segments.lastIndex else Timeline.indexAt(segments, book.positionMs ?: 0L)
        return Chapters.at(chapters(ctx, book.id, segments), index)?.title ?: "Start"
    }

    /** Where each paragraph starts, in ms. */
    fun segmentStarts(ctx: Context, id: String): List<Long> = segments(ctx, id).map { it.start }

    /**
     * Copies the PDF or EPUB into a new book and starts generating its audio. A download passes its [source] (kept with the book) and
     * [title] (the file has no display name), and a [temporary] file, deleted once copied.
     */
    fun import(ctx: Context, uri: Uri, source: String = "", title: String? = null, temporary: File? = null, pendingKey: String? = null) {
        // A file the reader picked (not a download) is listed from here on; a caller that listed it already passes its [pendingKey].
        val key = pendingKey ?: if (source.isEmpty()) begin(ctx, uri) else null
        scope.launch {
            val id = UUID.randomUUID().toString().take(8)
            val dir = dir(ctx, id).apply { mkdirs() }
            val name = displayName(ctx, uri)
            val epub = name?.endsWith(".epub", ignoreCase = true) == true || ctx.contentResolver.getType(uri) == "application/epub+zip"
            val sourceFile = if (epub) Files.EPUB else Files.PDF
            val meta = importMeta(title ?: titleOf(name), System.currentTimeMillis(), source)
            key?.let { step(it, PendingStep.SAVING, meta.getString("title")) }
            Engines.beforeCopy()
            File(dir, Files.NOTES).writeText("[]")
            try {
                ctx.contentResolver.openInputStream(uri)!!.use { i -> File(dir, sourceFile).outputStream().use { i.copyTo(it) } }
                temporary?.delete()
                File(dir, Files.META).writeText(meta.put("status", Status.QUEUED.name).toString())
            } catch (e: Exception) {
                // An unreadable file (revoked grant, no permission) shows as a failed book instead of crashing the app.
                File(dir, Files.META).writeText(meta.put("status", Status.FAILED.name).put("error", "Could not read the ${if (epub) "EPUB" else "PDF"}: ${e.message}").toString())
                refresh(ctx)
                key?.let(::finish)
                return@launch
            }
            refresh(ctx)
            // The book is on the shelf now: it takes the place the reader dropped the file at, and then the pending row can go without a gap.
            key?.let { k ->
                pendingState.value.firstOrNull { it.key == k }?.place?.let { moveTo(ctx, id, it) }
                finish(k)
            }
            ImportQueue.start(ctx)
        }
    }

    /**
     * Adds a catalogue book to the end of the import queue at once, without its file: [QueueDownload] fetches the EPUB from [url] when
     * the book is the highest in line still to fetch, or when its turn comes. The file is not part of the book until it is whole.
     */
    fun enqueueDownload(ctx: Context, title: String, source: String, url: String) {
        scope.launch {
            val id = UUID.randomUUID().toString().take(8)
            val dir = dir(ctx, id).apply { mkdirs() }
            File(dir, Files.NOTES).writeText("[]")
            val meta = downloadMeta(title, System.currentTimeMillis(), source, url)
            writeAtomically(File(dir, Files.META), meta.toString())
            refresh(ctx)
            ImportQueue.start(ctx)
        }
    }

    /**
     * Moves a book to place [to] in the import queue (0 is first). A book being made that is no longer first stops, goes back to
     * the queue in its new place, and carries on later from the speech it had made.
     * The shelf shows the new order from this call on: the list changes in memory first, on the calling thread, and the books' folders
     * are written afterwards on a background thread. If that fails the old order is put back and [moveFailures] says why.
     */
    fun moveTo(ctx: Context, id: String, to: Int) {
        val (change, previous) = synchronized(stateLock) {
            val books = state.value
            val change = ImportQueue.reorder(books, id, to)
            if (change.isEmpty) return
            unwritten += change
            state.value = change.applyTo(books)
            change to books.filter { it.id in change.orders }.associate { it.id to it.queueOrder }
        }
        moveWrites.launch { writeMove(ctx, change, previous) }
    }

    private fun writeMove(ctx: Context, change: ImportQueue.Reorder, previous: Map<String, Long>) {
        try {
            Engines.beforeMoveWrite()
            synchronized(this) {
                try {
                    change.orders.forEach { (bid, order) -> update(ctx, bid) { it.copy(queueOrder = order) } }
                } catch (e: Exception) {
                    // Put back what was already written, so a half-saved move does not leave the order in two minds.
                    try { previous.forEach { (bid, order) -> update(ctx, bid) { it.copy(queueOrder = order) } } } catch (_: Exception) {}
                    throw e
                }
                // A book shown as running with no worker making it (after a restart) goes back to the queue at once.
                for (bid in change.stop) if (!ImportQueue.preempt(bid)) requeue(ctx, bid)
            }
        } catch (e: Exception) {
            moveFailed.tryEmit("Could not move the book: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            synchronized(stateLock) { unwritten -= change }
            try { refresh(ctx) } catch (_: Exception) {}
        }
    }

    /** Puts a stopped book back in the queue. The speech it made stays in its folder, and [GenerateWorker] carries on from it. */
    fun requeue(ctx: Context, id: String) {
        File(dir(ctx, id), Files.QUEUE).delete()
        update(ctx, id) { it.copy(status = Status.QUEUED) }
    }

    fun delete(ctx: Context, id: String) {
        ImportQueue.cancel(id)
        dir(ctx, id).deleteRecursively()
        refresh(ctx)
    }

    fun segments(ctx: Context, id: String): List<Segment> = readSegments(File(dir(ctx, id), Files.TRANSCRIPT)) ?: emptyList()

    /**
     * The transcript in [f], or null when it is missing or unreadable. Never throws on bad content: a transcript from an older build
     * could be cut short mid-write, and a reader that crashes on it makes the book impossible to open.
     */
    fun readSegments(f: File): List<Segment>? {
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            List(a.length()) { val o = a.getJSONObject(it); Segment(o.getLong("start"), o.getLong("end"), o.getString("text"), o.optInt("at", -1)) }
        } catch (e: org.json.JSONException) {
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** The book's text in reading order with the audio times of what is spoken, and the rest pending ([BookText.merge]). A finished book has no queue: its transcript is the text. */
    fun bookSegments(ctx: Context, id: String): List<Segment> = BookText.merge(segments(ctx, id), queued(ctx, id))

    /** Sentences read so far, in text order, spoken or not. A book not being made has none. Only whole lines count, as the file can be mid-append. */
    fun queued(ctx: Context, id: String): List<String> {
        val f = File(dir(ctx, id), Files.QUEUE)
        if (!f.exists()) return emptyList()
        val text = f.readText()
        return text.substring(0, text.lastIndexOf('\n') + 1).lines().dropLast(1)
    }

    fun queue(ctx: Context, id: String, sentences: List<String>) {
        File(dir(ctx, id), Files.QUEUE).appendText(sentences.joinToString("") { it.replace('\n', ' ') + "\n" })
    }

    fun saveSegments(ctx: Context, id: String, segments: List<Segment>) = writeSegments(File(dir(ctx, id), Files.TRANSCRIPT), segments)

    internal fun writeSegments(f: File, segments: List<Segment>) {
        val a = JSONArray()
        segments.forEach { a.put(JSONObject().put("start", it.start).put("end", it.end).put("text", it.text).also { o -> if (it.at >= 0) o.put("at", it.at) }) }
        writeAtomically(f, a.toString())
    }

    fun notes(ctx: Context, id: String): List<Note> = readNotes(File(dir(ctx, id), Files.NOTES))

    internal fun readNotes(f: File): List<Note> {
        val a = JSONArray(f.readText())
        return List(a.length()) { val o = a.getJSONObject(it); Note(o.getString("id"), o.getLong("at"), o.getString("text")) }
    }

    /**
     * Applies an edit of one paragraph ([ParagraphEdit]) to the transcript and to everything that points into it: the paragraph notes,
     * the highlights and the stored chapters. Each file is replaced atomically; the transcript goes first, and notes and highlights find
     * their paragraph by text again if a kill lands between the files. Returns the new state, or null when the book is gone or its transcript
     * is unreadable (an edit would otherwise overwrite it with an empty one).
     * Only for a finished book: while one is being made the speech worker owns the transcript.
     */
    @Synchronized
    fun editTranscript(ctx: Context, id: String, change: (TranscriptState) -> TranscriptState): TranscriptState? {
        val book = readBook(dir(ctx, id)) ?: return null
        val edited = change(TranscriptState(readSegments(File(dir(ctx, id), Files.TRANSCRIPT)) ?: return null, ParagraphNotes.load(ctx, id), Highlights.load(ctx, id), book.chapters))
        saveSegments(ctx, id, edited.segments)
        ParagraphNotes.save(ctx, id, edited.paragraphNotes)
        Highlights.save(ctx, id, edited.highlights)
        update(ctx, id) { it.copy(chapters = edited.chapters) }
        return edited
    }

    /** The book's footnotes; none for a book made without them, or when the file cannot be read. */
    fun footnotes(ctx: Context, id: String): List<Footnote> = readFootnotes(File(dir(ctx, id), Files.FOOTNOTES))

    internal fun readFootnotes(f: File): List<Footnote> {
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            List(a.length()) { val o = a.getJSONObject(it); Footnote(o.getString("anchor"), o.getString("text"), o.optBoolean("contents")) }
        } catch (e: org.json.JSONException) {
            emptyList()
        } catch (e: java.io.IOException) {
            emptyList()
        }
    }

    fun saveFootnotes(ctx: Context, id: String, footnotes: List<Footnote>) = writeFootnotes(File(dir(ctx, id), Files.FOOTNOTES), footnotes)

    internal fun writeFootnotes(f: File, footnotes: List<Footnote>) {
        val a = JSONArray()
        footnotes.forEach { a.put(JSONObject().put("anchor", it.anchor).put("text", it.text).apply { if (it.contents) put("contents", true) }) }
        writeAtomically(f, a.toString())
    }

    fun saveNotes(ctx: Context, id: String, notes: List<Note>) = writeNotes(File(dir(ctx, id), Files.NOTES), notes)

    internal fun writeNotes(f: File, notes: List<Note>) {
        val a = JSONArray()
        notes.sortedBy { it.at }.forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)) }
        writeAtomically(f, a.toString())
    }
}
