/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections

/**
 * The one background worker that makes the import queue: OCR and speech together, then AAC, one book after another
 * ([ImportQueue]), as a foreground job so a long book survives the app being closed. Unique work, so a second import
 * waits its turn behind this worker rather than running beside it.
 */
class GenerateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // A refused foreground start only costs the notification; the work still runs.
        try { setForeground(foregroundInfo()) } catch (_: Exception) {}
        val ctx = applicationContext
        coroutineScope {
            // Fetching is network bound: it runs beside the book being made and ends with the run.
            val prefetch = launch { QueueDownload.prefetch(ctx) }
            // The notification names the book being made and its stage, and follows the queue as it moves or is paused.
            val notice = launch { ImportNotice.keepUpdated(ctx, NOTIFICATION_ID) }
            try {
                do {
                    ImportQueue.drain(
                        pick = { skip -> if (ImportQueue.paused.value) null else { Library.refresh(ctx); ImportQueue.next(Library.books.value, skip)?.id } },
                        run = { id -> if (!ImportQueue.paused.value) make(id) },
                        // An Error too (out of memory): otherwise the job ends but the book shows its last stage for ever.
                        failed = { id, e -> Library.update(ctx, id) { it.copy(status = Status.FAILED, error = e.message ?: e.javaClass.simpleName) } },
                    )
                    // Paused: the worker stays, so the notification stays with its Resume button, until the import is resumed.
                } while (ImportQueue.awaitResume())
            } finally {
                prefetch.cancel()
                notice.cancel()
            }
        }
        return Result.success()
    }

    /** Makes one book. Deleting it stops only this book; the worker itself being stopped ends the whole run. */
    private suspend fun make(id: String) = supervisorScope {
        val job = async { generate(id) }
        ImportQueue.track(id, job)
        try {
            job.await()
        } catch (e: CancellationException) {
            coroutineContext.ensureActive()
        } finally {
            ImportQueue.untrack(id)
        }
        // Stopped to let another book go first: back in the queue once nothing of it is still running.
        if (ImportQueue.wasPreempted(id)) Library.requeue(applicationContext, id)
    }

    /**
     * Reading and speaking run together: each page's sentences go to the speech engines as soon as the text stages
     * ([SentenceStream]) are certain of them, and the audio is flushed as it is made, so the book can be played while
     * it is still being read. A failure in either half fails the book.
     */
    private suspend fun generate(id: String) {
        // A book from "Catalogue" whose turn comes before its file was fetched: fetch it now, or fail the book with the reason.
        QueueDownload.ensure(applicationContext, id)
        try {
            generate(id, resume = true)
        } catch (e: StaleResume) {
            // The book reads differently from what was spoken (edited, or read another way): the speech so far no longer fits.
            val dir = Library.dir(applicationContext, id)
            for (f in listOf(Files.TRANSCRIPT, "audio.pcm")) File(dir, f).delete()
            generate(id, resume = false)
        }
    }

    private class StaleResume : Exception("the speech made so far does not match the book")

    /** With [resume], speech a stopped book already made is kept: the book is read again, but its first sentences are not spoken again. */
    private suspend fun generate(id: String, resume: Boolean) = coroutineScope {
        val ctx = applicationContext
        val dir = Library.dir(ctx, id)
        File(dir, Files.QUEUE).delete()
        val options = Options.load(ctx)
        val before = Library.books.value.first { it.id == id }
        val pcm = File(dir, "audio.pcm")
        val kept = if (resume) Speech.kept(Library.segments(ctx, id), before.rate, pcm.length()) else Speech.Kept.NONE
        // A book stopped to let another go first, after its text was all read, carries on at the stage and progress it had: the pages are read
        // again from the first (the text is not saved), but the shelf must not go back to Reading for that.
        val carriesOn = kept.segments.isNotEmpty() && before.wordsTotal > 0
        if (carriesOn) Library.update(ctx, id) { it.copy(status = Status.SPEAKING) } else Library.update(ctx, id) { it.copy(status = Status.READING, progress = 0f) }
        val progress = if (carriesOn) Progress(ctx, id, words = before.words, wordsTotal = before.wordsTotal) else Progress(ctx, id)
        // Kept sentences have audio already, wherever in the book they are; everything else is spoken in the order [SpeakOrder] decides.
        val keptText = HashMap<Int, String>().also { m -> kept.segments.forEachIndexed { i, s -> m[if (s.at >= 0) s.at else i] = s.text } }
        val order = SpeakOrder(keptText.keys, Speaking.from(ctx, id))
        Speaking.register(id, order)
        var spokenWords = kept.segments.sumOf { Text.wordCount(it.text) }
        val wordsAfter = Collections.synchronizedList(ArrayList<Int>())
        if (kept.segments.isNotEmpty()) RandomAccessFile(pcm, "rw").use { it.setLength(kept.bytes) }

        val reader = launch {
            val stream = SentenceStream(options)
            val speller = if (options.spell) PlatformSpeller(ctx) else null
            val unchecked = ArrayList<String>()
            var contents = emptyList<String>()
            var isbn: String? = null
            suspend fun emit(batch: List<String>) {
                val checked = speller?.correct(batch) {} ?: batch
                Library.queue(ctx, id, checked)
                for (s in checked) {
                    val n = wordsAfter.size
                    wordsAfter += (wordsAfter.lastOrNull() ?: 0) + Text.wordCount(s)
                    if (keptText[n]?.let { it != s } == true) throw StaleResume()
                    order.add(s)
                    progress.queued(wordsAfter.last())
                }
            }
            suspend fun emitChecked(batch: List<String>, last: Boolean) {
                if (speller == null) return emit(batch)
                unchecked += batch
                while (unchecked.size >= SPELL_BATCH || (last && unchecked.isNotEmpty())) {
                    val n = minOf(SPELL_BATCH, unchecked.size)
                    emit(unchecked.subList(0, n).toList())
                    unchecked.subList(0, n).clear()
                }
            }
            // The pages are read again from the first, so footnotes found by an earlier run are found again.
            File(dir, Files.FOOTNOTES).delete()
            val footnotes = ArrayList<Footnote>()
            val epub = File(dir, Files.EPUB)
            if (epub.exists()) {
                // An EPUB has its text already: no OCR, no cleaning that would cut a real chapter, and the chapters come from its own contents.
                val book = withContext(Dispatchers.IO) { Epub.read(epub, options.footnotes, options.contents) { done, total -> progress.page(done, total) } }
                if (book.footnotes.isNotEmpty()) Library.saveFootnotes(ctx, id, book.footnotes)
                Library.update(ctx, id) { it.copy(title = book.title.ifBlank { it.title }, author = book.author, contents = book.chapters, chapters = book.marks.ifEmpty { null }) }
                emitChecked(Epub.speakable(book.paragraphs), last = true)
                progress.readingDone(wordsAfter.lastOrNull() ?: error("No text found in this EPUB"))
                order.finish()
                return@launch
            }
            // The pre-pass: chapters from the contents page, before the book is read, so the contents page stays out of the text.
            var plan = try {
                ChapterEstimator.plan(File(dir, Files.PDF).let { pdf -> Engines.surveyPages?.invoke(pdf) ?: PageSurvey.read(pdf) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChapterPlan.NONE
            }
            if (plan.chapters.isNotEmpty()) Library.update(ctx, id) { it.copy(estimates = plan.chapters) }
            var frontPages = 0
            var page = 0
            var afterContents = false
            PdfText.pages(File(dir, Files.PDF), options.footnotes, progress::page) { read ->
                val blocks = read.paragraphs
                page++
                // The ISBN sits on the copyright page, among the first pages; a failed or empty lookup keeps the file's title.
                if (isbn == null && frontPages++ < FRONT_PAGES) {
                    isbn = Isbn.find(blocks.joinToString("\n"))?.also { found ->
                        launch {
                            val info = try { withTimeout(LOOKUP_MS) { Engines.lookupBook(found) } } catch (e: CancellationException) { if (e is TimeoutCancellationException) null else throw e } catch (_: Exception) { null }
                            if (info != null) Library.update(ctx, id) { it.copy(title = info.title, author = info.author) }
                        }
                    }
                }
                val anchored = plan.withAnchors(page, blocks)
                if (anchored != plan) {
                    plan = anchored
                    Library.update(ctx, id) { it.copy(estimates = anchored.chapters) }
                }
                // A contents page is kept as a note at the start of the book and never spoken: the survey's pages, and any page whose lines have a contents list's shape.
                if (options.contents) {
                    val lines = read.lines.ifEmpty { blocks }
                    val shaped = page <= ContentsPages.FRONT && ContentsPages.isPage(lines, afterContents)
                    afterContents = shaped || page in plan.contentsPages
                    if (afterContents) {
                        if (shaped) stream.noteContents(blocks)
                        footnotes += Footnote("", lines.filter { it.isNotBlank() }.joinToString("\n"), contents = true)
                        Library.saveFootnotes(ctx, id, footnotes)
                        return@pages
                    }
                }
                if (read.footnotes.isNotEmpty()) {
                    footnotes += read.footnotes
                    Library.saveFootnotes(ctx, id, footnotes)
                }
                emitChecked(stream.add(blocks), last = false)
                if (stream.contents != contents) {
                    contents = stream.contents
                    Library.update(ctx, id) { it.copy(contents = contents) }
                }
            }
            emitChecked(stream.finish(), last = true)
            if (stream.contents != contents) Library.update(ctx, id) { it.copy(contents = stream.contents) }
            if (wordsAfter.isEmpty()) error("No text found in this PDF")
            // The text is whole: its chapters are known now, before any audio is finished, and speech and export reuse them.
            Library.detectChapters(ctx, id)
            progress.readingDone(wordsAfter.last())
            order.finish()
        }

        // The shelf's settings sheet downloads the voice when the switch is turned on; this covers a model removed since.
        if (options.voice.neural) Engines.prepareNeural(ctx, options.voice) {}
        val synths = List(options.voice.lanes) { if (options.voice.neural) Engines.neural(ctx, options.voice) else Engines.synthesizer(ctx) }
        val (segments, rate) = try {
            Speech.render(
                synths, order::take, File(dir, "sentence.wav"), pcm,
                onChunk = { done, rate ->
                    // The transcript is saved after the audio it describes, so a reader never shows text with no sound behind it.
                    Library.saveSegments(ctx, id, done)
                    Library.update(ctx, id) { it.copy(rate = rate, durationMs = done.last().end) }
                },
                resume = kept,
            ) { at, _ ->
                // Words spoken, wherever in the book: the speech may have jumped, so the running total is not the words before the sentence.
                spokenWords += wordsAfter[at] - (wordsAfter.getOrNull(at - 1) ?: 0)
                progress.spoken(spokenWords)
            }
        } finally {
            Speaking.unregister(id)
            synths.forEach { it.close() }
        }
        reader.join()
        if (keptText.keys.any { it >= wordsAfter.size }) throw StaleResume()
        Library.update(ctx, id) { it.copy(status = Status.ENCODING, progress = 0.9f) }
        // After a jump the audio was made out of order: lay it out in the order of the text before it is encoded.
        val ordered = if (Speech.inBookOrder(segments)) segments.map { it.copy(at = -1) } else {
            val laidOut = File(dir, "audio.ordered.pcm")
            Speech.inBookOrder(segments, rate, pcm, laidOut).also { pcm.delete(); laidOut.renameTo(pcm) }
        }
        AacEncoder.encode(pcm, rate, File(dir, Files.AUDIO)) { done ->
            Library.update(ctx, id) { it.copy(progress = 0.9f + 0.1f * done) }
        }
        pcm.delete()
        File(dir, Files.QUEUE).delete()
        File(dir, Files.JUMP).delete()
        Library.saveSegments(ctx, id, ordered)
        Library.update(ctx, id) { it.copy(status = Status.READY, progress = 1f, durationMs = ordered.last().end, positionMs = it.positionMs?.let { p -> Speech.moved(p, segments, ordered) }) }
    }

    /**
     * What the shelf shows while reading and speaking overlap: page progress (the first 40%) until the last page is read,
     * then words spoken out of the now known total (the next 50%). The second half can only start after the first.
     */
    private class Progress(private val ctx: Context, private val id: String, private var words: Int = 0, private var wordsTotal: Int = 0) {
        private var pages = 0
        private var pagesTotal = 0
        private var wordsQueued = 0

        @Synchronized
        fun page(done: Int, total: Int) { pages = done; pagesTotal = total; save() }

        @Synchronized
        fun queued(wordsRead: Int) { wordsQueued = wordsRead; save() }

        @Synchronized
        fun spoken(wordsDone: Int) { words = wordsDone; save() }

        @Synchronized
        fun readingDone(total: Int) { wordsTotal = total; save() }

        private fun save() = Library.update(ctx, id) {
            if (wordsTotal == 0) {
                it.copy(progress = 0.4f * pages / pagesTotal.coerceAtLeast(1), pages = pages, pagesTotal = pagesTotal, words = words, wordsQueued = wordsQueued)
            } else {
                it.copy(status = Status.SPEAKING, progress = 0.4f + 0.5f * words / wordsTotal, words = words, wordsTotal = wordsTotal)
            }
        }
    }

    private fun foregroundInfo() = ForegroundInfo(
        NOTIFICATION_ID,
        ImportNotice.build(applicationContext, ImportNotice.of(ImportQueue.showStoppingAsWaiting(Library.books.value), ImportQueue.paused.value)),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private companion object {
        const val NOTIFICATION_ID = 1

        /** Sentences sent to the spell checker at once. */
        const val SPELL_BATCH = 50

        /** Pages searched for an ISBN, counted from the first. */
        const val FRONT_PAGES = 12

        /** Longest wait for the catalogue; the audiobook is made regardless. */
        const val LOOKUP_MS = 15_000L
    }
}
