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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

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
    /** Sentences read so far, one per line, while a book is made; speech trails behind it. Gone once the book is ready. */
    const val QUEUE = "queue.txt"
    const val META = "meta.json"
}

object Library {
    private val state = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dir(ctx: Context, id: String) = File(ctx.filesDir, "books/$id")

    @Synchronized
    fun refresh(ctx: Context) {
        val dirs = File(ctx.filesDir, "books").listFiles() ?: emptyArray()
        state.value = dirs.mapNotNull { readBook(it) }.sortedByDescending { it.created }
    }

    private fun readBook(dir: File): Book? {
        val f = File(dir, Files.META)
        if (!f.exists()) return null
        val j = JSONObject(f.readText())
        return Book(
            id = dir.name,
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
        val j = JSONObject().put("title", b.title).put("author", b.author).put("status", b.status.name)
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
        writeAtomically(File(dir, Files.META), j.toString())
        refresh(ctx)
    }

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
        val spoken = segments(ctx, id)
        val marks = Chapters.detect(spoken.map { it.text } + queued(ctx, id).drop(spoken.size), book.contents, book.estimates)
        update(ctx, id) { it.copy(chapters = marks) }
        return marks
    }

    /** The stored chapters with their audio times; a book stored before chapters were kept gets them detected on this first call. */
    fun chapters(ctx: Context, id: String): List<Chapter> =
        Chapters.place(readBook(dir(ctx, id))?.chapters ?: detectChapters(ctx, id), segments(ctx, id))

    /** Where each paragraph starts, in ms. */
    fun segmentStarts(ctx: Context, id: String): List<Long> = segments(ctx, id).map { it.start }

    /**
     * Copies the PDF or EPUB into a new book and starts generating its audio. A download passes its [source] (kept with the book) and
     * [title] (the file has no display name), and a [temporary] file, deleted once copied.
     */
    fun import(ctx: Context, uri: Uri, source: String = "", title: String? = null, temporary: File? = null) {
        scope.launch {
            val id = UUID.randomUUID().toString().take(8)
            val dir = dir(ctx, id).apply { mkdirs() }
            val name = try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            } catch (_: Exception) { null } ?: uri.lastPathSegment // A file:// link has no display name.
            val epub = name?.endsWith(".epub", ignoreCase = true) == true || ctx.contentResolver.getType(uri) == "application/epub+zip"
            val sourceFile = if (epub) Files.EPUB else Files.PDF
            val meta = JSONObject().put("title", title ?: (name ?: "Untitled").removeSuffix(".pdf").removeSuffix(".epub"))
                .put("created", System.currentTimeMillis()).put("order", System.currentTimeMillis())
            if (source.isNotEmpty()) meta.put("source", source)
            File(dir, Files.NOTES).writeText("[]")
            try {
                ctx.contentResolver.openInputStream(uri)!!.use { i -> File(dir, sourceFile).outputStream().use { i.copyTo(it) } }
                temporary?.delete()
                File(dir, Files.META).writeText(meta.put("status", Status.QUEUED.name).toString())
            } catch (e: Exception) {
                // An unreadable file (revoked grant, no permission) shows as a failed book instead of crashing the app.
                File(dir, Files.META).writeText(meta.put("status", Status.FAILED.name).put("error", "Could not read the ${if (epub) "EPUB" else "PDF"}: ${e.message}").toString())
                refresh(ctx)
                return@launch
            }
            refresh(ctx)
            ImportQueue.start(ctx)
        }
    }

    /**
     * Moves a book to place [to] in the import queue (0 is first). A book being made that is no longer first stops, goes back to
     * the queue in its new place, and carries on later from the speech it had made.
     */
    @Synchronized
    fun moveTo(ctx: Context, id: String, to: Int) {
        refresh(ctx)
        val change = ImportQueue.reorder(state.value, id, to)
        change.orders.forEach { (bid, order) -> update(ctx, bid) { it.copy(queueOrder = order) } }
        // A book shown as running with no worker making it (after a restart) goes back to the queue at once.
        for (bid in change.stop) if (!ImportQueue.preempt(bid)) requeue(ctx, bid)
    }

    /** Puts a stopped book back in the queue. The speech it made stays in its folder, and [GenerateWorker] carries on from it. */
    fun requeue(ctx: Context, id: String) {
        File(dir(ctx, id), Files.QUEUE).delete()
        update(ctx, id) { it.copy(status = Status.QUEUED) }
    }

    /** Moves a waiting book one place up the import queue; the first waiting book stays behind the book being made. */
    fun moveUp(ctx: Context, id: String) {
        refresh(ctx)
        if (ImportQueue.canMoveUp(state.value, id)) moveTo(ctx, id, ImportQueue.lineIndex(state.value, id) - 1)
    }

    fun delete(ctx: Context, id: String) {
        ImportQueue.cancel(id)
        dir(ctx, id).deleteRecursively()
        refresh(ctx)
    }

    fun segments(ctx: Context, id: String): List<Segment> {
        val f = File(dir(ctx, id), Files.TRANSCRIPT)
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        return List(a.length()) { val o = a.getJSONObject(it); Segment(o.getLong("start"), o.getLong("end"), o.getString("text")) }
    }

    /** Sentences read but maybe not yet spoken. Only whole lines count, as the file can be mid-append. */
    fun queued(ctx: Context, id: String): List<String> {
        val f = File(dir(ctx, id), Files.QUEUE)
        if (!f.exists()) return emptyList()
        val text = f.readText()
        return text.substring(0, text.lastIndexOf('\n') + 1).lines().dropLast(1)
    }

    fun queue(ctx: Context, id: String, sentences: List<String>) {
        File(dir(ctx, id), Files.QUEUE).appendText(sentences.joinToString("") { it.replace('\n', ' ') + "\n" })
    }

    fun saveSegments(ctx: Context, id: String, segments: List<Segment>) {
        val a = JSONArray()
        segments.forEach { a.put(JSONObject().put("start", it.start).put("end", it.end).put("text", it.text)) }
        writeAtomically(File(dir(ctx, id), Files.TRANSCRIPT), a.toString())
    }

    fun notes(ctx: Context, id: String): List<Note> {
        val a = JSONArray(File(dir(ctx, id), Files.NOTES).readText())
        return List(a.length()) { val o = a.getJSONObject(it); Note(o.getString("id"), o.getLong("at"), o.getString("text")) }
    }

    /**
     * Applies an edit of one paragraph ([ParagraphEdit]) to the transcript and to everything that points into it: the paragraph notes,
     * the highlights and the stored chapters. Each file is replaced atomically; the transcript goes first, and notes and highlights find
     * their paragraph by text again if a kill lands between the files. Returns the new state, or null when the book is gone.
     * Only for a finished book: while one is being made the speech worker owns the transcript.
     */
    @Synchronized
    fun editTranscript(ctx: Context, id: String, change: (TranscriptState) -> TranscriptState): TranscriptState? {
        val book = readBook(dir(ctx, id)) ?: return null
        val edited = change(TranscriptState(segments(ctx, id), ParagraphNotes.load(ctx, id), Highlights.load(ctx, id), book.chapters))
        saveSegments(ctx, id, edited.segments)
        ParagraphNotes.save(ctx, id, edited.paragraphNotes)
        Highlights.save(ctx, id, edited.highlights)
        update(ctx, id) { it.copy(chapters = edited.chapters) }
        return edited
    }

    fun saveNotes(ctx: Context, id: String, notes: List<Note>) {
        val a = JSONArray()
        notes.sortedBy { it.at }.forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)) }
        writeAtomically(File(dir(ctx, id), Files.NOTES), a.toString())
    }
}
