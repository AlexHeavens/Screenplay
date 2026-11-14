/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Backs a seeded book up with the real zipper and imports it through "Import backup"; only the system file picker is faked. */
@RunWith(AndroidJUnit4::class)
class BackupImportTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val audio = ByteArray(2048) { (it * 7).toByte() }

    @Before
    fun seededBook() {
        File(ctx.filesDir, "books").deleteRecursively()
        Fixtures.seedBook(
            ctx, "seed0001", "Seeded",
            listOf(Segment(0, 1500, "First sentence."), Segment(1500, 3000, "Second sentence.")),
            audio, author = "Ada Author", contents = listOf("One", "Two"),
        )
        Library.saveNotes(ctx, "seed0001", listOf(Note("n1", 1200, "remember this")))
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun pick(uri: android.net.Uri?) {
        Intents.init()
        try {
            val result = Instrumentation.ActivityResult(Activity.RESULT_OK, uri?.let { Intent().setData(it) })
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(result)
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("import-backup").performClick()
            compose.waitUntil(30_000) { Intents.getIntents().any { it.action == Intent.ACTION_OPEN_DOCUMENT } }
        } finally {
            Intents.release()
        }
    }

    private fun waitForText(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Smoke @Test
    fun importsABackupAsANewBookWithTranscriptNotesAndDetails() {
        pick(Backup.zip(ctx, "seed0001", "Seeded"))
        // The snackbar, not the shelf's "Imported" section header, which is there from the start.
        waitForText("Imported “Seeded”")

        val books = Library.books.value
        assertEquals(2, books.size)
        val copy = books.single { it.id != "seed0001" }
        assertEquals("Seeded", copy.title)
        assertEquals("Ada Author", copy.author)
        assertEquals(Status.READY, copy.status)
        assertEquals(listOf("One", "Two"), copy.contents)
        assertEquals(Library.segments(ctx, "seed0001"), Library.segments(ctx, copy.id))
        assertEquals(listOf(Note("n1", 1200, "remember this")), Library.notes(ctx, copy.id))
        assertArrayEquals(audio, File(Library.dir(ctx, copy.id), Files.AUDIO).readBytes())
        assertEquals("the original stays untouched", 2, Library.segments(ctx, "seed0001").size)
    }

    @Smoke @Test
    fun refusesAZipThatIsNotABackupAndSaysSo() {
        val bogus = File(ctx.cacheDir, "backup/bogus.zip").apply { parentFile!!.mkdirs() }
        java.util.zip.ZipOutputStream(bogus.outputStream()).use { it.putNextEntry(java.util.zip.ZipEntry("readme.txt")); it.write(1); it.closeEntry() }
        pick(androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", bogus))
        waitForText("Not an audiobook backup")
        assertEquals(1, Library.books.value.size)
    }

    @Smoke @Test
    fun exportsTheWholeLibraryFromTheSettingsAndImportsItBack() {
        pick(Backup.zip(ctx, "seed0001", "Seeded"))
        waitForText("Imported “Seeded”")
        assertEquals(2, Library.books.value.size)

        Intents.init()
        val shared = try {
            Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("export-backup").performClick()
            compose.waitUntil(30_000) { Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER } }
            Intents.getIntents().first { it.action == Intent.ACTION_CHOOSER }
                .getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        } finally {
            Intents.release()
        }
        val entryNames = ArrayList<String>()
        ctx.contentResolver.openInputStream(shared)!!.use { input ->
            java.util.zip.ZipInputStream(input).use { z -> generateSequence { z.nextEntry }.forEach { entryNames += it.name } }
        }
        assertEquals("one backup per book", 2, entryNames.size)
        assertEquals(listOf(true, true), entryNames.map { it.endsWith(".zip") })

        pick(shared)
        compose.waitUntil(30_000) { Library.books.value.size == 4 }
        assertEquals(4, Library.books.value.count { it.title == "Seeded" })
    }
}
