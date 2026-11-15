/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

/** What happens to a picked file before it is a book in the queue; [text] is the status the row shows while it does. */
enum class PendingStep(val text: String) { CHECKING("Checking file"), SAVING("Saving file"), RESTORING("Restoring backup") }

/**
 * A file the reader picked that is not yet a book in the queue: it is being checked (PDF, EPUB or backup?), copied into the app, or restored as a backup.
 * The shelf lists it under "Importing" from the moment it is picked, after the books already in line, which is where it joins the queue.
 * A [step] of null with an [error] is an import that failed before it became a book: the row stays, with the reason, until the reader removes it.
 */
data class PendingImport(val key: String, val title: String, val step: PendingStep?, val error: String? = null,
    /** Where the reader dragged the file: the number of books above it once it joins the queue; null to join at the end. */
    val place: Int? = null,
) {
    /** The stages the row shows, as a queued book's: the file being fetched (here, copied) now, reading next. Backups are restored whole, so they have no reading. */
    fun stages(): List<ImportStage> = when (step) {
        null -> emptyList()
        PendingStep.RESTORING -> listOf(ImportStage(StageKind.DOWNLOAD, StageState.ONGOING, null, step.text))
        else -> listOf(ImportStage(StageKind.DOWNLOAD, StageState.ONGOING, null, step.text), ImportStage(StageKind.READ, StageState.NEXT))
    }

    /** The words under the title when there are no stages: why the import failed. */
    val status: String get() = error?.let { "Failed: $it" } ?: ""
}
