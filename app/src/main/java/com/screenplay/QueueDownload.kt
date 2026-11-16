/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

/**
 * How far one fetch has come: [received] bytes of [expected], which is 0 or less when the server did not announce a length.
 * The bar fills with [fraction] (an indeterminate bar when it is null) and the row says [label].
 */
data class DownloadProgress(val received: Long, val expected: Long) {
    /** Share received, 0..1; null when the length is unknown. */
    val fraction: Float? get() = if (expected > 0) (received.toFloat() / expected).coerceIn(0f, 1f) else null

    /** "Downloading 42% · 3.1 of 7.4 MB"; with no length "Downloading 3.1 MB"; "Downloading…" before the first byte. */
    val label: String get() = when {
        expected > 0 -> "Downloading ${percent}% · ${size(received, expected)} of ${size(expected, expected)} ${unit(expected)}"
        received > 0 -> "Downloading ${size(received, received)} ${unit(received)}"
        else -> "Downloading…"
    }

    /** The stage's text: "42% · 3.1/7.4 MB"; with no length "3.1 MB"; "0%" before the first byte. */
    val counts: String get() = when {
        expected > 0 -> "$percent% · ${size(received, expected)}/${size(expected, expected)} ${unit(expected)}"
        received > 0 -> "${size(received, received)} ${unit(received)}"
        else -> "0%"
    }

    private val percent: Int get() = (received * 100 / expected).coerceIn(0, 100).toInt()

    /**
     * Passes on a report only when the reader would see a change: a whole percent with a known length, a tenth of a megabyte without.
     * A download makes at most about a hundred updates however many chunks it has, and one that has stalled has already shown its last.
     */
    class Throttle {
        private var shown: Long? = null

        fun accept(progress: DownloadProgress): Boolean {
            val step = if (progress.expected > 0) progress.percent.toLong() else progress.received / (MEGABYTE / 10)
            if (step == shown) return false
            shown = step
            return true
        }
    }

    private fun unit(reference: Long) = if (reference < MEGABYTE) "KB" else "MB"

    /** [bytes] in the unit of [reference], so a pair reads "3.1 of 7.4 MB". */
    private fun size(bytes: Long, reference: Long): String =
        if (reference < MEGABYTE) (bytes / 1024).toString() else String.format(Locale.ROOT, "%.1f", bytes / MEGABYTE.toDouble())

    private companion object {
        const val MEGABYTE = 1024L * 1024L
    }
}

/**
 * Fetches the EPUBs of books added from "Catalogue" for the import queue. Fetching is network bound, so while one book is made
 * (OCR and speech, compute bound) [prefetch] fetches the highest book in line that has no file yet ([ImportQueue.nextDownload]), and
 * it is ready when its turn comes. One fetch runs at a time, which also keeps to Project Gutenberg's request that clients do not
 * make parallel requests. A book's turn fetches its own file through [ensure] if nothing did before.
 */
object QueueDownload {
    private val one = Mutex()
    private val activeProgress = MutableStateFlow(emptyMap<String, DownloadProgress>())

    /**
     * The books being fetched now, each with how far it has come; the shelf row shows a bar and the progress for each. Kept in memory
     * only: a fetch that is cut short starts again from the beginning, so progress on disk would only be wrong after a restart.
     */
    val active: StateFlow<Map<String, DownloadProgress>> = activeProgress

    /**
     * Makes sure [id]'s EPUB is on disk. Does nothing when it is (so a book is never fetched twice, and a turn that comes while
     * [prefetch] is fetching the same book waits for that fetch). A transient failure ([CatalogueException.transient]) is tried again up to
     * [ATTEMPTS] times in all, each after the client's back-off; any other failure, or the last one, is thrown for the book to show.
     */
    suspend fun ensure(ctx: Context, id: String) = retryTransient(ATTEMPTS) { fetchOnce(ctx, id) }

    /** Runs [attempt] up to [attempts] times in all, again only after a [CatalogueException.transient] failure; any other failure, or the last, is thrown. */
    internal suspend fun retryTransient(attempts: Int, attempt: suspend () -> Unit) {
        var tried = 1
        while (true) {
            try {
                return attempt()
            } catch (e: CatalogueException) {
                if (!e.transient || tried++ >= attempts) throw e
            }
        }
    }

    /** One try: waits out a failure's back-off first, outside the lock. Throws when the fetch fails. */
    private suspend fun fetchOnce(ctx: Context, id: String) {
        if (!awaiting(id)) return
        val client = Gutenberg.client(ctx)
        delay(client.backoffMs())
        one.withLock {
            val book = Library.books.value.firstOrNull { it.id == id }?.takeIf { it.awaitingDownload } ?: return
            val throttle = DownloadProgress.Throttle()
            DownloadProgress(0, -1).also { throttle.accept(it); activeProgress.update { all -> all + (id to it) } }
            try {
                client.download(book.downloadUrl, File(Library.dir(ctx, id), Files.EPUB)) { received, expected ->
                    val progress = DownloadProgress(received, expected)
                    if (throttle.accept(progress)) activeProgress.update { it + (id to progress) }
                }
                Library.update(ctx, id) { it.copy(error = null) }
            } finally {
                activeProgress.update { it - id }
            }
        }
    }

    /** Tries of one fetch for a transient failure, the first included; the client's back-off (2 s, then 4 s) spaces them. */
    private const val ATTEMPTS = 3

    private fun awaiting(id: String) = Library.books.value.firstOrNull { it.id == id }?.awaitingDownload == true

    /**
     * Runs until cancelled. Each time it fetches the book [ImportQueue.nextDownload] names, then asks again, so a reorder, an addition, a
     * removal or a finished book is taken into account at the next fetch. A fetch in progress is stopped if its book is deleted. A book
     * whose fetch fails shows the error on its row, is not tried again here (until the reader taps Retry), and is left for its own turn, which retries it; the next book is fetched.
     */
    suspend fun prefetch(ctx: Context) {
        val failed = HashSet<String>()
        while (true) {
            // A book whose error was cleared (the reader tapped Retry) is no longer skipped.
            val id = Library.books.map { books -> failed.removeAll { f -> books.firstOrNull { it.id == f }?.error == null }; ImportQueue.nextDownload(books, failed)?.id }.filterNotNull().first()
            try {
                fetchUnlessDeleted(ctx, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed += id
                Library.update(ctx, id) { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private suspend fun fetchUnlessDeleted(ctx: Context, id: String) = coroutineScope {
        val fetch = launch { ensure(ctx, id) }
        val deleted = launch {
            Library.books.first { books -> books.none { it.id == id } }
            fetch.cancel()
        }
        try { fetch.join() } finally { deleted.cancel() }
    }
}
