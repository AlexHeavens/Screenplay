/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * A file picked through the import button is a row under "Importing" at once, with the stage it is in and a status for it, until it is a book:
 * a PDF is shown as saved, then queued and made; a zip that is no backup stays as a failed row with the reason until it is removed.
 * Real: the shelf, picker result handling, copy, queue and pipeline. Faked: the system file picker, OCR (canned text) and speech.
 */
@RunWith(AndroidJUnit4::class)
class PendingImportUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options(spell = false, voice = Voice.PHONE).save(ctx)
        Engines.synthesizer = { FakeSynthesizer(msPerWord = 40) }
        Engines.surveyPages = { emptyList() }
    }

    @After
    fun restore() {
        Engines.beforeCopy = {}
        Engines.beforeRecognise = { _, _ -> }
        Engines.recognisePage = null
        Engines.surveyPages = null
        scenario?.close()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun text(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }

    private fun pick(f: File) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Intents.init()
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))),
        )
        try { compose.onNodeWithTag("import").performClick() } finally { Intents.release() }
    }

    @Smoke @Test
    fun aPickedPdfIsARowFromTheMomentItIsPickedAndItsStageChangesUntilItIsReady() {
        val file = File(ctx.cacheDir, "picked-local.pdf").also { f -> assets.open("ocr/varieties-preface.pdf").use { i -> f.outputStream().use { i.copyTo(it) } } }
        val pages = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
        Engines.recognisePage = cannedOcr(assets, "varieties-preface", pages)
        val copying = CountDownLatch(1)
        val release = CountDownLatch(1)
        Engines.beforeCopy = { copying.countDown(); release.await() }

        pick(file)
        // Held while the file is being saved: no book yet, but the row is there with its stage and status.
        assertTrue(copying.await(30, java.util.concurrent.TimeUnit.SECONDS))
        compose.waitUntil(30_000) { exists("stage-text-DOWNLOAD-picked-local") }
        assertTrue("under Importing", exists("section-importing"))
        assertTrue("the file is a row, not yet a book", exists("pending-picked-local") && Library.books.value.isEmpty())
        assertEquals("Saving file", text("stage-text-DOWNLOAD-picked-local"))
        assertTrue("reading is next", exists("stage-READ-picked-local"))

        release.countDown()
        // Saved: the row is the book's, with its fetch done and no text for it, then made through to its listening bar.
        compose.waitUntil(60_000) { exists("book-picked-local") && !exists("pending-picked-local") }
        assertEquals(1, Library.books.value.size)
        assertTrue("the fetch of a local file is done: no text for it", !exists("stage-text-DOWNLOAD-picked-local"))
        compose.waitUntil(120_000) { exists("stage-bar-LISTEN-picked-local") }
        assertTrue("nothing left importing", !exists("section-importing"))
    }

    @Smoke @Test
    fun aZipThatIsNoBackupStaysAsAFailedRowWithTheReasonUntilItIsRemoved() {
        val bogus = File(ctx.cacheDir, "bogus-backup.zip")
        java.util.zip.ZipOutputStream(bogus.outputStream()).use { it.putNextEntry(java.util.zip.ZipEntry("readme.txt")); it.write(1); it.closeEntry() }
        // A zip is not copied: it is checked, then restored. The row shows each step until it fails.
        pick(bogus)
        compose.waitUntil(30_000) { exists("status-bogus-backup") }
        assertTrue("under Importing", exists("section-importing"))
        assertTrue(text("status-bogus-backup"), text("status-bogus-backup").startsWith("Failed: "))
        assertTrue("no book was made", Library.books.value.isEmpty())
        compose.onNodeWithTag("delete-bogus-backup", useUnmergedTree = true).performClick()
        compose.waitUntil(30_000) { !exists("pending-bogus-backup") }
        assertTrue("the section goes with its last row", !exists("section-importing"))
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun aFileBeingSavedCanBeDraggedUpTheQueueAndTakesThatPlaceWhenItIsABook() {
        val names = listOf("drag-a", "drag-b", "drag-c")
        val files = names.map { n -> File(ctx.cacheDir, "$n.pdf").also { f -> assets.open("ocr/varieties-preface.pdf").use { i -> f.outputStream().use { i.copyTo(it) } } } }
        val pages = ParcelFileDescriptor.open(files[0], ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
        Engines.recognisePage = cannedOcr(assets, "varieties-preface", pages)
        // The first page is held so the first book stays being made and the second waits behind it.
        val hold = CountDownLatch(1)
        Engines.beforeRecognise = { page, _ -> if (page == 0) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { hold.await() } }
        val copies = java.util.concurrent.atomic.AtomicInteger()
        val copying = CountDownLatch(1)
        val release = CountDownLatch(1)
        Engines.beforeCopy = { if (copies.incrementAndGet() == 3) { copying.countDown(); release.await() } }

        scenario = ActivityScenario.launch(MainActivity::class.java)
        val picks = ArrayDeque(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWithFunction {
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(picks.removeFirst()))
            }
            for ((i, n) in names.withIndex()) {
                compose.onNodeWithTag("import").performClick()
                if (i < 2) compose.waitUntil(60_000) { Library.books.value.size == i + 1 && exists("book-$n") }
            }
        } finally {
            Intents.release()
        }
        assertTrue(copying.await(30, java.util.concurrent.TimeUnit.SECONDS))
        compose.waitUntil(30_000) { exists("drag-handle-drag-c") && Library.books.value.count { it.status == Status.READING } == 1 }
        assertTrue("the file is last, behind both books", top("book-drag-b") < top("pending-drag-c"))

        // Drop it onto the second book: it takes that place once it is a book, and the second waits behind it.
        compose.onNodeWithTag("drag-handle-drag-c", useUnmergedTree = true).performTouchInput {
            val dy = top("book-drag-b") - top("pending-drag-c")
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, dy / 2), delayMillis = 20)
            moveBy(androidx.compose.ui.geometry.Offset(0f, dy / 2), delayMillis = 20)
            up()
        }
        compose.waitUntil(30_000) { top("pending-drag-c") < top("book-drag-b") }
        release.countDown()
        compose.waitUntil(60_000) { Library.books.value.size == 3 && !exists("pending-drag-c") }
        compose.waitUntil(30_000) { ImportQueue.line(Library.books.value).map { it.title } == listOf("drag-a", "drag-c", "drag-b") }
        hold.countDown()
    }
}
