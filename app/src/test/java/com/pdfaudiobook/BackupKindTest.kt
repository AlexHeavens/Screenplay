/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one Import button sends a zip to the restore and a PDF or an EPUB to the importer; an EPUB is a zip too. */
class BackupKindTest {
    @Test
    fun aZipIsABackupWhateverTypeTheProviderReports() {
        assertTrue(Backup.isBackup("Screenplay library.zip", "application/zip"))
        assertTrue(Backup.isBackup("Moby Dick.zip", "application/octet-stream"))
        assertTrue(Backup.isBackup("Moby Dick.ZIP", null))
        assertTrue(Backup.isBackup(null, "application/x-zip-compressed"))
    }

    @Test
    fun aPdfOrAnEpubIsABookToImport() {
        assertFalse(Backup.isBackup("Moby Dick.pdf", "application/pdf"))
        assertFalse(Backup.isBackup("Moby Dick.epub", "application/epub+zip"))
        assertFalse(Backup.isBackup("Moby Dick.epub", "application/octet-stream"))
        assertFalse(Backup.isBackup(null, "application/epub+zip"))
    }
}
