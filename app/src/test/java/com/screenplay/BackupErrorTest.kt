/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The exact words a person reads when a backup is refused. */
class BackupErrorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (name, content) -> z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray()); z.closeEntry() } }
    }.toByteArray().let { ByteArrayInputStream(it) }

    private fun refusal(vararg entries: Pair<String, String>): String {
        try { Backup.extract(zip(*entries), tmp.newFolder()) } catch (e: BackupException) { return e.message!! }
        fail("expected the backup to be refused")
        return ""
    }

    @Test
    fun anUnsafeNameIsQuotedInTheMessage() {
        assertEquals("Not a valid backup: unsafe file name \"../evil.txt\".", refusal("../evil.txt" to "x"))
    }

    @Test
    fun aMissingAudioIsNamed() {
        assertEquals("Not an audiobook backup: missing ${Files.AUDIO}.", refusal(Files.PDF to "p", Files.TRANSCRIPT to "[]"))
    }

    @Test
    fun aMissingTranscriptIsNamed() {
        assertEquals("Not an audiobook backup: missing ${Files.TRANSCRIPT}.", refusal(Files.PDF to "p", Files.AUDIO to "a"))
    }

    @Test
    fun everythingMissingIsNamedTogether() {
        val message = refusal("readme.txt" to "x")
        for (name in listOf(Files.AUDIO, Files.TRANSCRIPT, Files.PDF)) assertEquals("$name is named in: $message", true, name in message)
    }

    @Test
    fun aZipWithNoEntriesIsRefusedAsMissingEverything() {
        val message = refusal()
        assertEquals(true, message.startsWith("Not an audiobook backup: missing "))
    }

    @Test
    fun aFileInsideAFolderOfTheZipDoesNotCountAsTheBooksFile() {
        // Only top-level names are taken, so a folder cannot hide the book's audio from the check or write outside the book's folder.
        val dest = tmp.newFolder()
        try { Backup.extract(zip("books/${Files.AUDIO}" to "a", Files.PDF to "p", Files.TRANSCRIPT to "[]"), dest); fail("expected a refusal") } catch (e: BackupException) {
            assertEquals("Not an audiobook backup: missing ${Files.AUDIO}.", e.message)
        }
        assertFalse(dest.resolve("books").exists())
    }
}
