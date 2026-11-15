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
    fun aFailedFileHasNoStagesAndSaysWhy() {
        val failed = PendingImport("k", "t", null, "Not an audiobook backup")
        assertTrue(failed.stages().isEmpty())
        assertEquals("Failed: Not an audiobook backup", failed.status)
        assertEquals("", PendingImport("k", "t", PendingStep.SAVING).status)
    }

    @Test
    fun theStepsAreWorded() {
        assertEquals(listOf("Checking file", "Saving file", "Restoring backup"), PendingStep.values().map { it.text })
    }
}
