/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Books are made one at a time, in the order the shelf lists them. A running book comes first; the rest wait as QUEUED,
 * in the order kept in each book's own details ([Book.queueOrder]), which the user can change. The order lives with the
 * book, so it survives the app being closed: a restart makes the book that was running again, then the next in line.
 */
object ImportQueue {
    private val running = setOf(Status.READING, Status.SPELLING, Status.SPEAKING, Status.ENCODING)
    private val gate = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val preempted = ConcurrentHashMap.newKeySet<String>()
    private val pausedFlow = MutableStateFlow(false)

    /** True while the user has paused the import (from the notification): the book being made keeps its stage and progress, and no book is made until [resume]. */
    val paused: StateFlow<Boolean> = pausedFlow

    /** Books waiting to be made, first in line first. */
    private fun waiting(books: List<Book>) = books.filter { it.status == Status.QUEUED }.sortedWith(compareBy({ it.queueOrder }, { it.created }, { it.id }))

    /** Every book whose import is not over, in the order it is made: the one running (or interrupted by a restart), then the waiting ones. */
    fun line(books: List<Book>): List<Book> = books.filter { it.status in running }.sortedBy { it.created } + waiting(books)

    /** The books the shelf lists as imported: the ones not in [line], a failed one with its error. */
    fun imported(books: List<Book>): List<Book> = books.filter { it.status == Status.READY || it.status == Status.FAILED }

    /** The next book to make, skipping those in [skip]. */
    fun next(books: List<Book>, skip: Set<String> = emptySet()): Book? = line(books).firstOrNull { it.id !in skip }

    /**
     * The next book to fetch: the highest in [line] whose file is not on disk, skipping [skip] (books whose fetch failed this run).
     * A book being made already has its file, so it is never chosen. Where the book stands decides, not when it was added: a reorder changes the answer.
     */
    fun nextDownload(books: List<Book>, skip: Set<String> = emptySet()): Book? = line(books).firstOrNull { it.awaitingDownload && it.id !in skip }

    /**
     * What the row says in words: the book's own label once its import is over, the error of a failed fetch for a waiting book, and nothing
     * else: a waiting book's place is its row, and [Stages] shows a book being fetched or made.
     */
    fun label(books: List<Book>, book: Book, downloading: Map<String, DownloadProgress> = emptyMap(), paused: Boolean = false): String {
        if (book.importing && book.status != Status.QUEUED) return if (paused) "Paused" else ""
        if (book.status != Status.QUEUED) return book.label()
        if (book.id in downloading) return ""
        if (line(books).none { it.id == book.id }) return book.label()
        return book.error?.let { "Download failed: $it" } ?: ""
    }

    /** The place in [line] that [id] takes if dropped [rows] rows from where it stands (negative is up); -1 when it is not in the line. */
    fun dropPlace(books: List<Book>, id: String, rows: Int): Int {
        val line = line(books)
        val at = line.indexOfFirst { it.id == id }
        return if (at < 0) -1 else (at + rows).coerceIn(0, line.lastIndex)
    }

    /** One row of the Importing section the reader can drag: a book in [line], or a picked file ([PendingImport]) that is not yet a book. */
    data class QueueRow(val id: String, val pending: Boolean)

    /**
     * The rows of the section in order: [line], with each picked file still being copied at its [PendingImport.place] (the number of books above it)
     * or after the last book when it has none. Files with the same place keep the order they were picked in.
     */
    fun rows(books: List<Book>, pending: List<PendingImport>): List<QueueRow> {
        val out = line(books).mapTo(ArrayList()) { QueueRow(it.id, false) }
        for (p in pending.filter { it.step != null && it.step != PendingStep.RESTORING }.sortedBy { it.place ?: Int.MAX_VALUE }) {
            val at = p.place?.let { place -> out.withIndex().filter { !it.value.pending }.getOrNull(place)?.index } ?: out.size
            out.add(at, QueueRow(p.key, true))
        }
        return out
    }

    /** The row [id] takes if dropped [rows] rows from where it stands in [all] (negative is up); -1 when it is not there. */
    fun dropRow(all: List<QueueRow>, id: String, rows: Int): Int {
        val at = all.indexOfFirst { it.id == id }
        return if (at < 0) -1 else (at + rows).coerceIn(0, all.lastIndex)
    }

    /** [all] with [id] moved to row [to]. */
    fun moveRow(all: List<QueueRow>, id: String, to: Int): List<QueueRow> {
        val at = all.indexOfFirst { it.id == id }
        return if (at < 0) all else all.toMutableList().also { it.add(to.coerceIn(0, it.lastIndex), it.removeAt(at)) }
    }

    /** How many books stand above [id] in [all]: the place in [line] a book (or a picked file once it is a book) with that row takes. */
    fun booksAbove(all: List<QueueRow>, id: String) = all.takeWhile { it.id != id }.count { !it.pending }

    /** Where [id] stands among the waiting books, 0 for the next to be made; -1 when it is not waiting. */
    fun waitingIndex(books: List<Book>, id: String) = waiting(books).indexOfFirst { it.id == id }

    /** Where [id] stands in [line], 0 for the book being made; -1 when its import is over. */
    fun lineIndex(books: List<Book>, id: String) = line(books).indexOfFirst { it.id == id }

    /** What a reorder changes: the new [Book.queueOrder] of the books whose place moves, and the books being made that must stop. */
    class Reorder(val orders: Map<String, Long>, val stop: List<String>) {
        val isEmpty get() = orders.isEmpty() && stop.isEmpty()

        /** [books] as they stand once this reorder is done: the new orders, and the books to stop shown as waiting again, so [line] lists the new order at once. */
        fun applyTo(books: List<Book>): List<Book> = books.map { b ->
            val moved = orders[b.id]?.let { b.copy(queueOrder = it) } ?: b
            if (b.id in stop) moved.copy(status = Status.QUEUED) else moved
        }
    }

    /**
     * [books] with every book that has been told to stop shown as waiting again. Such a book keeps its running status on disk until its worker has
     * stopped and put it back ([Library.requeue]); without this the shelf would list it first again for that time.
     */
    fun showStoppingAsWaiting(books: List<Book>): List<Book> =
        if (preempted.isEmpty()) books else books.map { if (it.id in preempted && it.status in running) it.copy(status = Status.QUEUED) else it }

    /**
     * Moves [id] to place [to] in [line] (0 is the book made first; a place outside the line is the nearest end of it).
     * Whatever ends up first is made first, so a book being made that is no longer first is in [Reorder.stop]: [Library.moveTo]
     * stops it and puts it back among the waiting books, in the place given here (the shelf shows that at once, see [Library.moveTo]). Empty when [id] is not in the line or stays put.
     */
    fun reorder(books: List<Book>, id: String, to: Int): Reorder {
        val line = line(books)
        val at = line.indexOfFirst { it.id == id }
        val place = to.coerceIn(0, maxOf(line.lastIndex, 0))
        if (at < 0 || place == at) return Reorder(emptyMap(), emptyList())
        val moved = line.toMutableList().also { it.add(place, it.removeAt(at)) }
        val stop = line.filter { it.status in running && it.id != moved[0].id }.map { it.id }
        // A book that keeps running needs no place: running books come before the waiting ones.
        val orders = moved.withIndex().filter { (i, b) -> (b.status !in running || b.id in stop) && b.queueOrder != i.toLong() }.associate { (i, b) -> b.id to i.toLong() }
        return Reorder(orders, stop)
    }

    /**
     * Makes books one after another until none is left to pick, whichever workers call it: the lock lets only one run.
     * A book that fails is passed to [failed] and the next is made. A book is tried once per call, so one that ends
     * still unfinished cannot loop for ever.
     */
    suspend fun drain(pick: (skip: Set<String>) -> String?, run: suspend (String) -> Unit, failed: (String, Throwable) -> Unit) = gate.withLock {
        val tried = HashSet<String>()
        while (true) {
            val id = pick(tried) ?: break
            tried += id
            try {
                run(id)
                // A book stopped to let another go first is made again later, so it may be picked once more.
                if (preempted.remove(id)) tried -= id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed(id, e)
            }
        }
    }

    /** Lets [job] be stopped by [cancel] while it makes [id]. */
    fun track(id: String, job: Job) { jobs[id] = job }

    fun untrack(id: String) { jobs.remove(id) }

    /** Stops [id] if it is the book being made, as when it is deleted. */
    fun cancel(id: String) { jobs[id]?.cancel() }

    /** Stops [id] to be made again later. False when it is not being made by a worker now. */
    fun preempt(id: String): Boolean {
        val job = jobs[id] ?: return false
        preempted += id
        job.cancel()
        return true
    }

    /**
     * Stops the book being made where it is, for the user to carry on later. Unlike [preempt] the book is not put back among the waiting books:
     * it keeps its running status, stage and progress, so it stays first in [line] and [resume] makes it again as a restart would, from the speech it kept.
     */
    fun pause() {
        pausedFlow.value = true
        jobs.values.forEach { it.cancel() }
    }

    /** Lets the paused import carry on with the book it stopped. */
    fun resume() { pausedFlow.value = false }

    /**
     * Called by the worker when [drain] has ended: while the import is paused it waits for [resume] and returns true, so the worker drains again.
     * False when it was not paused, or when the paused queue emptied (every book deleted), which also clears the pause.
     */
    suspend fun awaitResume(): Boolean {
        if (!pausedFlow.value) return false
        val resumed = combine(pausedFlow, Library.books) { p, books -> !p || line(books).isEmpty() }.first { it }
        if (pausedFlow.value) { pausedFlow.value = false; return false }
        return resumed
    }

    /** True from [preempt] until [drain] has seen the book stop. */
    fun wasPreempted(id: String) = id in preempted

    /** Starts the one background worker that makes the queue, after the one running now if there is one; with nothing queued it ends at once. */
    fun start(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("import-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<GenerateWorker>().build())
    }
}
