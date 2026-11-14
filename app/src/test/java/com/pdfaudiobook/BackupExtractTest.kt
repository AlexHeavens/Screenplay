/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupExtractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray().let { ByteArrayInputStream(it) }

    private fun rejected(input: ByteArrayInputStream): String {
        try { Backup.extract(input, tmp.newFolder()) } catch (e: BackupException) { return e.message!! }
        fail("expected the backup to be rejected")
        return ""
    }

    @Test
    fun extractsTheKnownEntriesAndIgnoresOthers() {
        val dest = tmp.newFolder()
        val found = Backup.extract(zip(Files.PDF to "p", Files.AUDIO to "a", Files.TRANSCRIPT to "[]", "readme.txt" to "x"), dest)
        assertEquals(setOf(Files.PDF, Files.AUDIO, Files.TRANSCRIPT), found)
        assertEquals("a", dest.resolve(Files.AUDIO).readText())
        assertTrue(!dest.resolve("readme.txt").exists())
    }

    @Test
    fun anEpubBookNeedsNoPdf() {
        val dest = tmp.newFolder()
        val found = Backup.extract(zip(Files.EPUB to "e", Files.AUDIO to "a", Files.TRANSCRIPT to "[]"), dest)
        assertTrue(Files.EPUB in found)
        assertEquals("e", dest.resolve(Files.EPUB).readText())
    }

    @Test
    fun rejectsAZipWithNeitherPdfNorEpub() {
        assertTrue(rejected(zip(Files.AUDIO to "a", Files.TRANSCRIPT to "[]")).contains(Files.PDF))
    }

    @Test
    fun extractsTheHighlights() {
        val dest = tmp.newFolder()
        val found = Backup.extract(zip(Files.PDF to "p", Files.AUDIO to "a", Files.TRANSCRIPT to "[]", Files.HIGHLIGHTS to "[]"), dest)
        assertTrue(Files.HIGHLIGHTS in found)
        assertEquals("[]", dest.resolve(Files.HIGHLIGHTS).readText())
    }

    @Test
    fun rejectsAZipWithoutTheAudio() {
        assertTrue(rejected(zip(Files.PDF to "p", Files.TRANSCRIPT to "[]")).contains(Files.AUDIO))
    }

    @Test
    fun rejectsPathTraversalAndWritesNothingOutside() {
        val dest = tmp.newFolder()
        for (name in listOf("../evil.txt", "a/../../evil.txt", "/evil.txt", "..\\evil.txt")) {
            try { Backup.extract(zip(name to "x", Files.PDF to "p", Files.AUDIO to "a", Files.TRANSCRIPT to "[]"), dest); fail("accepted $name") } catch (_: BackupException) {}
        }
        assertTrue(!dest.parentFile!!.resolve("evil.txt").exists())
    }
}
