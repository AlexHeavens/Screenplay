/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Fetches the EPUBs of books added from "Find books" for the import queue. Fetching is network bound, so while one book is made
 * (OCR and speech, compute bound) [prefetch] fetches the highest book in line that has no file yet ([ImportQueue.nextDownload]), and
 * it is ready when its turn comes. One fetch runs at a time, which also keeps to Project Gutenberg's request that clients do not
 * make parallel requests. A book's turn fetches its own file through [ensure] if nothing did before.
 */
object QueueDownload {
    private val one = Mutex()
    private val activeIds = MutableStateFlow(emptySet<String>())

    /** The books being fetched now; the shelf row says "Downloading" for each. */
    val active: StateFlow<Set<String>> = activeIds

    /**
     * Makes sure [id]'s EPUB is on disk. Does nothing when it is (so a book is never fetched twice, and a turn that comes while
     * [prefetch] is fetching the same book waits for that fetch). Waits out a failure's back-off first, outside the lock. Throws when the fetch fails.
     */
    suspend fun ensure(ctx: Context, id: String) {
        if (!awaiting(id)) return
        val client = Gutendex.client(ctx)
        delay(client.backoffMs())
        one.withLock {
            val book = Library.books.value.firstOrNull { it.id == id }?.takeIf { it.awaitingDownload } ?: return
            activeIds.value += id
            try {
                client.download(book.downloadUrl, File(Library.dir(ctx, id), Files.EPUB))
                Library.update(ctx, id) { it.copy(error = null) }
            } finally {
                activeIds.value -= id
            }
        }
    }

    private fun awaiting(id: String) = Library.books.value.firstOrNull { it.id == id }?.awaitingDownload == true

    /**
     * Runs until cancelled. Each time it fetches the book [ImportQueue.nextDownload] names, then asks again, so a reorder, an addition, a
     * removal or a finished book is taken into account at the next fetch. A fetch in progress is stopped if its book is deleted. A book
     * whose fetch fails shows the error on its row, is not tried again here, and is left for its own turn, which retries it; the next book is fetched.
     */
    suspend fun prefetch(ctx: Context) {
        val failed = HashSet<String>()
        while (true) {
            val id = Library.books.map { ImportQueue.nextDownload(it, failed)?.id }.filterNotNull().first()
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
