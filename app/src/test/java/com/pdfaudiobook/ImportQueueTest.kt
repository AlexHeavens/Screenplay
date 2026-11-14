/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ImportQueueTest {
    private fun book(id: String, status: Status = Status.QUEUED, order: Long = 0, created: Long = 0) =
        Book(id, id, status, 0f, null, 0, created, queueOrder = order)

    private fun ids(books: List<Book>) = books.map { it.id }

    @Test
    fun theBookBeingMadeComesFirstThenTheWaitingOnesInTheirOrder() {
        val books = listOf(book("c", order = 30), book("a", Status.READING), book("b", order = 20), book("done", Status.READY), book("bad", Status.FAILED))
        assertEquals(listOf("a", "b", "c"), ids(ImportQueue.line(books)))
        assertEquals(listOf("done", "bad"), ids(ImportQueue.imported(books)))
        assertEquals("a", ImportQueue.next(books)?.id)
    }

    @Test
    fun anEmptyQueueHasNothingImportingSoTheShelfShowsNoImportingSection() {
        val books = listOf(book("done", Status.READY), book("bad", Status.FAILED))
        assertTrue(ImportQueue.line(books).isEmpty())
        assertEquals(null, ImportQueue.next(books))
        assertTrue(ImportQueue.line(emptyList()).isEmpty())
    }

    @Test
    fun aRestartMakesTheInterruptedBookAgainBeforeTheWaitingOnes() {
        // What meta.json holds after the app was killed mid-book: one at SPEAKING, two waiting.
        val books = listOf(book("w1", order = 1), book("cut", Status.SPEAKING, order = 0), book("w2", order = 2))
        assertEquals("cut", ImportQueue.next(books)?.id)
        assertEquals("w1", ImportQueue.next(books, skip = setOf("cut"))?.id)
    }

    @Test
    fun labelsShowPositionWithTheBookBeingMadeFirst() {
        val books = listOf(book("a", Status.READING), book("b", order = 1), book("c", order = 2))
        assertEquals("Queued · position 2", ImportQueue.label(books, books[1]))
        assertEquals("Queued · position 3", ImportQueue.label(books, books[2]))
        assertEquals(books[0].label(), ImportQueue.label(books, books[0]))
    }

    private fun remote(id: String, order: Long = 0, status: Status = Status.QUEUED, fetched: Boolean = false) =
        book(id, status, order).copy(downloadUrl = "https://example.test/$id.epub", awaitingDownload = !fetched)

    @Test
    fun theHighestWaitingBookWithoutItsFileIsFetchedNext() {
        val books = listOf(remote("c", order = 3), remote("a", order = 1), remote("b", order = 2))
        assertEquals("a", ImportQueue.nextDownload(books)?.id)
    }

    @Test
    fun fetchedBooksFileBooksAndTheBookBeingMadeAreSkipped() {
        val books = listOf(
            book("running", Status.SPEAKING), book("picked", order = 1), remote("done", order = 2, fetched = true),
            remote("next", order = 3), remote("later", order = 4),
        )
        assertEquals("next", ImportQueue.nextDownload(books)?.id)
        assertEquals("later", ImportQueue.nextDownload(books, skip = setOf("next"))?.id)
        assertEquals(null, ImportQueue.nextDownload(books, skip = setOf("next", "later")))
        assertEquals(null, ImportQueue.nextDownload(listOf(book("a"), remote("b", fetched = true), remote("gone", status = Status.FAILED))))
    }

    @Test
    fun aReorderChangesWhichBookIsFetchedNext() {
        val books = listOf(remote("a", order = 1), remote("b", order = 2), remote("c", order = 3))
        assertEquals("a", ImportQueue.nextDownload(books)?.id)
        val change = ImportQueue.reorder(books, "c", 0)
        val moved = books.map { b -> change.orders[b.id]?.let { b.copy(queueOrder = it) } ?: b }
        assertEquals("c", ImportQueue.nextDownload(moved)?.id)
        assertEquals("a", ImportQueue.nextDownload(moved.filter { it.id != "c" })?.id)
    }

    @Test
    fun theRowSaysDownloadingWhileItsFileIsFetchedAndShowsAFailureWithItsPlace() {
        val books = listOf(book("a", Status.READING), remote("b", order = 1), remote("c", order = 2).copy(error = "No connection"))
        assertEquals("Downloading…", ImportQueue.label(books, books[1], downloading = mapOf("b" to DownloadProgress(0, -1))))
        assertEquals("Downloading 50% · 1 of 2 KB", ImportQueue.label(books, books[1], downloading = mapOf("b" to DownloadProgress(1024, 2048))))
        assertEquals("Queued · position 2", ImportQueue.label(books, books[1]))
        assertEquals("Queued · position 3 · Download failed: No connection", ImportQueue.label(books, books[2]))
    }

    /** The line after a reorder, as the shelf shows it once the books to stop have stopped and are waiting again. */
    private fun after(books: List<Book>, id: String, to: Int): Pair<List<String>, List<String>> {
        val change = ImportQueue.reorder(books, id, to)
        val moved = books.map { b -> change.orders[b.id]?.let { b.copy(queueOrder = it) } ?: b }
            .map { if (it.id in change.stop) it.copy(status = Status.QUEUED) else it }
        return ids(ImportQueue.line(moved)) to change.stop
    }

    private val running = listOf(book("a", Status.READING), book("b", order = 100), book("c", order = 200), book("d", order = 300))

    @Test
    fun theDropPlaceIsTheRowsDraggedAwayAndStaysInsideTheLine() {
        assertEquals("one row up", 2, ImportQueue.dropPlace(running, "d", -1))
        assertEquals("one row down", 3, ImportQueue.dropPlace(running, "c", 1))
        assertEquals("past the top", 0, ImportQueue.dropPlace(running, "d", -9))
        assertEquals("past the bottom", 3, ImportQueue.dropPlace(running, "b", 9))
        assertEquals("not in the line", -1, ImportQueue.dropPlace(running, "gone", 1))
        assertEquals(listOf("a", "b", "d", "c") to emptyList<String>(), after(running, "d", ImportQueue.dropPlace(running, "d", -1)))
    }

    @Test
    fun aWaitingBookDroppedBetweenWaitingBooksTakesThatPlaceAndTheRunningBookKeepsRunning() {
        assertEquals(listOf("a", "d", "b", "c") to emptyList<String>(), after(running, "d", 1))
        assertEquals(listOf("a", "c", "b", "d") to emptyList<String>(), after(running, "b", 2))
        assertEquals(listOf("a", "c", "d", "b") to emptyList<String>(), after(running, "b", 99))
        assertTrue("staying put changes nothing", ImportQueue.reorder(running, "c", 2).isEmpty)
        assertTrue("so does a place before the start for the first book", ImportQueue.reorder(running, "a", -5).isEmpty)
        assertTrue("and a book not in the line", ImportQueue.reorder(running, "gone", 1).isEmpty)
    }

    @Test
    fun aBookDroppedFirstIsMadeFirstAndTheRunningBookItPassedIsStoppedAndWaitsNextInLine() {
        assertEquals(listOf("b", "a", "c", "d") to listOf("a"), after(running, "b", 0))
        assertEquals(listOf("d", "a", "b", "c") to listOf("a"), after(running, "d", 0))
        assertEquals(listOf("d", "a", "b", "c") to listOf("a"), after(running, "d", -5))
    }

    @Test
    fun theRunningBookDraggedDownIsStoppedAndTheBookItPassedIsMadeInsteadOfIt() {
        assertEquals(listOf("b", "a", "c", "d") to listOf("a"), after(running, "a", 1))
        assertEquals(listOf("b", "c", "d", "a") to listOf("a"), after(running, "a", 3))
        assertEquals(listOf("b", "c", "d", "a") to listOf("a"), after(running, "a", 99))
    }

    @Test
    fun movingUpWorksWhenBooksShareAnOrder() {
        val books = listOf(book("x", order = 5, created = 1), book("y", order = 5, created = 2))
        assertEquals(listOf("y", "x") to emptyList<String>(), after(books, "y", 0))
    }

    @Test
    fun aBookStoppedToLetAnotherGoFirstIsMadeAgainInTheSameRun() = runBlocking {
        val shelf = Shelf("a", "b")
        var stopped = false
        ImportQueue.drain(
            shelf::pick,
            { id ->
                if (id == "a" && !stopped) {
                    stopped = true
                    shelf.status[id] = Status.READING
                    val job = launch { CompletableDeferred<Unit>().await() }
                    ImportQueue.track(id, job)
                    assertTrue(ImportQueue.preempt(id))
                    job.join()
                    ImportQueue.untrack(id)
                    shelf.status[id] = Status.QUEUED
                } else {
                    shelf.made += id
                    shelf.status[id] = Status.READY
                }
            },
            { _, _ -> },
        )
        assertEquals(listOf("a", "b"), shelf.made)
        assertFalse(ImportQueue.preempt("a"))
    }

    /** A fake shelf: each book's status, changed by the fake "make a book" step. */
    private class Shelf(vararg ids: String) {
        val status = ids.associateWith { Status.QUEUED }.toMutableMap()
        val order = ids.toList()
        val made = ArrayList<String>()
        fun books() = order.mapIndexed { i, id -> Book(id, id, status.getValue(id), 0f, null, 0, 0, queueOrder = i.toLong()) }
        fun pick(skip: Set<String>) = ImportQueue.next(books(), skip)?.id
    }

    @Test
    fun booksAreMadeOneAtATimeInQueueOrder() = runBlocking {
        val shelf = Shelf("a", "b", "c")
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val run: suspend (String) -> Unit = { id ->
            most.set(maxOf(most.get(), inside.incrementAndGet()))
            shelf.status[id] = Status.READING
            yield()
            shelf.made += id
            shelf.status[id] = Status.READY
            inside.decrementAndGet()
        }
        // Two workers draining at once, as when an old worker and a new one overlap: still one book at a time.
        val one = async { ImportQueue.drain(shelf::pick, run) { _, _ -> } }
        val two = async { ImportQueue.drain(shelf::pick, run) { _, _ -> } }
        one.await(); two.await()
        assertEquals(listOf("a", "b", "c"), shelf.made)
        assertEquals(1, most.get())
    }

    @Test
    fun aWaitingBookIsNotMadeWhileAnotherIsHeld() = runBlocking {
        val shelf = Shelf("a", "b")
        val hold = CompletableDeferred<Unit>()
        val started = ArrayList<String>()
        val job = launch {
            ImportQueue.drain(shelf::pick, { id -> started += id; shelf.status[id] = Status.READING; if (id == "a") hold.await(); shelf.status[id] = Status.READY }, { _, _ -> })
        }
        repeat(5) { yield() }
        assertEquals(listOf("a"), started)
        assertEquals(Status.QUEUED, shelf.status["b"])
        hold.complete(Unit)
        job.join()
        assertEquals(listOf("a", "b"), started)
    }

    @Test
    fun aFailedBookLeavesTheQueueAndTheNextIsMade() = runBlocking {
        val shelf = Shelf("a", "b", "c")
        val failures = ArrayList<Pair<String, String?>>()
        ImportQueue.drain(
            shelf::pick,
            { id ->
                shelf.status[id] = Status.READING
                if (id == "b") throw IllegalStateException("No text found in this PDF")
                shelf.made += id
                shelf.status[id] = Status.READY
            },
            { id, e -> failures += id to e.message; shelf.status[id] = Status.FAILED },
        )
        assertEquals(listOf("a", "c"), shelf.made)
        assertEquals(listOf("b" to "No text found in this PDF"), failures)
        assertTrue(ImportQueue.line(shelf.books()).isEmpty())
    }

    @Test
    fun aBookThatEndsUnfinishedIsNotTriedAgainInTheSameRun() = runBlocking {
        val shelf = Shelf("a", "b")
        val tried = ArrayList<String>()
        ImportQueue.drain(shelf::pick, { id -> tried += id }, { _, _ -> })
        assertEquals(listOf("a", "b"), tried)
    }
}
