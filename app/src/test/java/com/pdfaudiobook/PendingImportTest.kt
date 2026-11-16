/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingImportTest {
    private fun shown(p: PendingImport) = p.stages().map { Triple(it.kind, it.state, it.text) }

    @Test
    fun aFileBeingCheckedOrSavedShowsItsStepAsTheFetchAndReadingAsNext() {
        for (step in listOf(PendingStep.CHECKING, PendingStep.SAVING)) {
            assertEquals(
                listOf(Triple(StageKind.DOWNLOAD, StageState.ONGOING, step.text), Triple(StageKind.READ, StageState.NEXT, "")),
                shown(PendingImport("k", "t", step)),
            )
        }
    }

    @Test
    fun aBackupBeingRestoredHasNoReadingStage() {
        assertEquals(listOf(Triple(StageKind.DOWNLOAD, StageState.ONGOING, "Restoring backup")), shown(PendingImport("k", "t", PendingStep.RESTORING)))
    }

    @Test
    fun aBackupBookWaitingToBeRestoredShowsOnlyTheFetchAsNext() {
        assertEquals(listOf(Triple(StageKind.DOWNLOAD, StageState.NEXT, "")), shown(PendingImport("k", "t", PendingStep.WAITING)))
    }

    @Test
    fun aFailedFileHasNoStagesAndSaysWhy() {
        val failed = PendingImport("k", "t", null, "Not an audiobook backup")
        assertTrue(failed.stages().isEmpty())
        assertEquals("Failed: Not an audiobook backup", failed.status)
        assertEquals("", PendingImport("k", "t", PendingStep.SAVING).status)
    }

    @Test
    fun theStepsAreWorded() {
        assertEquals(listOf("Checking file", "Saving file", "Restoring backup", "Waiting to restore"), PendingStep.values().map { it.text })
    }

    private fun book(id: String, order: Long, status: Status = Status.QUEUED) = Book(id, id, status, 0f, null, 0, order, queueOrder = order)

    private fun ids(rows: List<ImportQueue.QueueRow>) = rows.map { (if (it.pending) "~" else "") + it.id }

    private val books = listOf(book("a", 0, Status.READING), book("b", 1), book("c", 2))

    @Test
    fun aPickedFileJoinsBehindTheLineUnlessItWasDropped() {
        val files = listOf(PendingImport("p", "p", PendingStep.SAVING), PendingImport("q", "q", PendingStep.CHECKING))
        assertEquals(listOf("a", "b", "c", "~p", "~q"), ids(ImportQueue.rows(books, files)))
        assertEquals(listOf("a", "~q", "b", "c", "~p"), ids(ImportQueue.rows(books, files.map { if (it.key == "q") it.copy(place = 1) else it })))
        assertEquals("the same place keeps the pick order", listOf("~p", "~q", "a", "b", "c"), ids(ImportQueue.rows(books, files.map { it.copy(place = 0) })))
        assertEquals("a place past the last book is the end", listOf("a", "b", "c", "~p"), ids(ImportQueue.rows(books, listOf(files[0].copy(place = 9)))))
    }

    @Test
    fun aFailedFileIsNotInTheQueue() {
        assertEquals(listOf("a", "b", "c"), ids(ImportQueue.rows(books, listOf(PendingImport("f", "f", null, "no")))))
    }

    @Test
    fun theBooksOfABackupQueueAtTheTopInTheirOrderUnlessDropped() {
        val backup = listOf(PendingImport("r1", "r1", PendingStep.WAITING), PendingImport("r2", "r2", PendingStep.WAITING), PendingImport("p", "p", PendingStep.SAVING))
        assertEquals(listOf("~r1", "~r2", "a", "b", "c", "~p"), ids(ImportQueue.rows(books, backup)))
        assertEquals(listOf("~r1", "a", "~r2", "b", "c", "~p"), ids(ImportQueue.rows(books, backup.map { if (it.key == "r2") it.copy(place = 1) else it })))
    }

    @Test
    fun theDropPlaceOfAFileIsTheNumberOfBooksAboveIt() {
        val all = ImportQueue.rows(books, listOf(PendingImport("p", "p", PendingStep.SAVING)))
        val up = ImportQueue.moveRow(all, "p", ImportQueue.dropRow(all, "p", -2))
        assertEquals(listOf("a", "~p", "b", "c"), ids(up))
        assertEquals(1, ImportQueue.booksAbove(up, "p"))
        assertEquals("a book moved down past the file counts only books", 2, ImportQueue.booksAbove(ImportQueue.moveRow(all, "a", 3), "a"))
        assertEquals(-1, ImportQueue.dropRow(all, "gone", 1))
    }
}
