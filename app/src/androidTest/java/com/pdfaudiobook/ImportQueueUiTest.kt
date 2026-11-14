/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Three books imported through the shelf's import button are made one at a time: the first is under "Importing", the others
 * wait as "Queued" and can be moved up or dragged, and when they are all made the section goes and they sit under "Imported".
 * The shelf, queue, worker and pipeline are real. Faked: the system file picker, OCR (canned text) and the speech engine,
 * and the first page of OCR is held so the books stay in the queue until the test lets them go.
 */
@RunWith(AndroidJUnit4::class)
class ImportQueueUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options().save(ctx)
        Engines.synthesizer = { FakeSynthesizer() }
    }

    @After
    fun restore() {
        Engines.beforeRecognise = { _, _ -> }
        Engines.recognisePage = null
        scenario?.close()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun text(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }

    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    /** Presses and holds the row, then drags it [dy] pixels (negative is up) and lets go. */
    private fun dragBy(tag: String, dy: Float) = compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput {
        down(center)
        advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
        moveBy(androidx.compose.ui.geometry.Offset(0f, dy / 2), delayMillis = 50)
        moveBy(androidx.compose.ui.geometry.Offset(0f, dy / 2), delayMillis = 50)
        up()
    }

    /** Takes hold of the handle and drags it [dy] pixels at once: no long press, so the whole gesture is far shorter than one. */
    private fun dragHandleBy(title: String, dy: Float) = compose.onNodeWithTag("drag-handle-$title", useUnmergedTree = true).performTouchInput {
        down(center)
        moveBy(androidx.compose.ui.geometry.Offset(0f, dy / 2), delayMillis = 20)
        moveBy(androidx.compose.ui.geometry.Offset(0f, dy / 2), delayMillis = 20)
        up()
    }

    private fun book(title: String) = Library.books.value.single { it.title == title }

    private fun uriOf(f: File) = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    @Smoke @Test
    fun booksAreMadeOneAtATimeAndAQueuedBookCanBeDraggedUp() {
        val names = listOf("queue-one", "queue-two", "queue-three")
        val files = names.map { n -> File(ctx.cacheDir, "$n.pdf").also { f -> assets.open("ocr/varieties-preface.pdf").use { i -> f.outputStream().use { i.copyTo(it) } } } }
        val pages = ParcelFileDescriptor.open(files[0], ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
        Engines.recognisePage = cannedOcr(assets, "varieties-preface", pages)
        val release = CompletableDeferred<Unit>()
        Engines.beforeRecognise = { page, _ -> if (page == 0) release.await() }
        // Speech stops after a few sentences, so a book stays part-spoken while the test reorders it.
        val speechHold = CompletableDeferred<Unit>()
        val spoken = AtomicInteger()
        Engines.synthesizer = {
            val inner = FakeSynthesizer()
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) { if (spoken.incrementAndGet() > 6) speechHold.await(); inner.speak(text, wav) }
                override fun close() = inner.close()
            }
        }

        scenario = ActivityScenario.launch(MainActivity::class.java)
        // The picker answers with the next file each time the import button is pressed.
        val picks = ArrayDeque(files.map(::uriOf))
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWithFunction {
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(picks.removeFirst()))
            }
            for ((i, n) in names.withIndex()) {
                compose.onNodeWithTag("import").performClick()
                compose.waitUntil(60_000) { exists("book-$n") }
                // Each book is in the library before the next is picked, so the queue order is the pick order.
                assertEquals(i + 1, Library.books.value.size)
            }
        } finally {
            Intents.release()
        }

        // Only the first is being made; the others are queued, with their place in line.
        compose.waitUntil(60_000) { Library.books.value.count { it.status == Status.READING } == 1 }
        compose.onNodeWithTag("section-importing").assertIsDisplayed()
        compose.waitUntil(30_000) { exists("status-queue-one") && !text("status-queue-one").startsWith("Queued") }
        assertEquals("Queued · position 2", text("status-queue-two"))
        assertEquals("Queued · position 3", text("status-queue-three"))
        assertEquals(1, Library.books.value.count { it.status == Status.READING })
        assertEquals(2, Library.books.value.count { it.status == Status.QUEUED })
        assertTrue("nothing is imported yet", !exists("section-imported"))

        // There is no Move up button: a book moves only by being dragged.
        assertTrue(names.none { exists("move-up-$it") })
        assertTrue("no drop line while nothing is dragged", names.none { exists("drop-line-$it") })
        assertTrue(top("book-queue-two") < top("book-queue-three"))
        dragBy("book-queue-three", top("book-queue-two") - top("book-queue-three"))
        compose.waitUntil(30_000) { top("book-queue-three") < top("book-queue-two") }
        assertEquals("Queued · position 2", text("status-queue-three"))
        assertEquals("Queued · position 3", text("status-queue-two"))
        assertTrue(top("book-queue-one") < top("book-queue-three"))

        // Every book in the queue shows a drag handle, the one being made too.
        assertTrue(names.all { exists("drag-handle-$it") })
        compose.onNodeWithTag("drag-handle-queue-two", useUnmergedTree = true).assertContentDescriptionEquals("Drag to reorder queue-two")
        compose.onNodeWithTag("book-queue-two", useUnmergedTree = true).assertIsNotSelected()

        // Dragging works too: press and hold the last book, drag it up past the one above, and it takes that place.
        val rowHeight = top("book-queue-two") - top("book-queue-three")
        compose.onNodeWithTag("book-queue-two", useUnmergedTree = true).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
        }
        // Held but not yet moved, the row has its own line, above it.
        compose.waitUntil(10_000) { exists("drop-line-queue-two") }
        compose.onNodeWithTag("book-queue-two", useUnmergedTree = true).performTouchInput {
            moveBy(androidx.compose.ui.geometry.Offset(0f, -rowHeight / 2), delayMillis = 50)
            moveBy(androidx.compose.ui.geometry.Offset(0f, -rowHeight / 2), delayMillis = 50)
        }
        // While held the row is lifted (selected) and a line above the row it would pass shows where it lands; letting go clears both.
        compose.onNodeWithTag("book-queue-two", useUnmergedTree = true).assertIsSelected()
        compose.waitUntil(10_000) { exists("drop-line-queue-three") }
        assertTrue("one line only", names.count { exists("drop-line-$it") } == 1)
        compose.onNodeWithTag("book-queue-two", useUnmergedTree = true).performTouchInput { up() }
        compose.onNodeWithTag("book-queue-two", useUnmergedTree = true).assertIsNotSelected()
        compose.waitUntil(10_000) { names.none { exists("drop-line-$it") } }
        compose.waitUntil(30_000) { top("book-queue-two") < top("book-queue-three") }
        assertEquals("Queued · position 2", text("status-queue-two"))
        assertTrue(top("book-queue-one") < top("book-queue-two"))

        // Dropping the first waiting book onto the book being made puts it first: it is made now, and the book it passed waits in its place.
        val row = top("book-queue-three") - top("book-queue-two")
        dragBy("book-queue-two", -row)
        // The book being made stops after the page it is reading, which can take a while on the emulator.
        compose.waitUntil(90_000) { Library.books.value.single { it.title == "queue-two" }.status != Status.QUEUED }
        compose.waitUntil(90_000) { Library.books.value.single { it.title == "queue-one" }.status == Status.QUEUED }
        compose.waitUntil(30_000) { text("status-queue-one") == "Queued · position 2" }
        assertEquals("Queued · position 3", text("status-queue-three"))
        assertTrue(top("book-queue-two") < top("book-queue-one") && top("book-queue-one") < top("book-queue-three"))

        // Dropping a waiting book onto the last place works too.
        dragBy("book-queue-one", row)
        compose.waitUntil(30_000) { text("status-queue-one") == "Queued · position 3" }
        assertEquals("Queued · position 2", text("status-queue-three"))
        assertTrue(top("book-queue-three") < top("book-queue-one"))

        // Let them go. The book being made speaks a few sentences, then the speech is held.
        release.complete(Unit)
        compose.waitUntil(120_000) { Library.segments(ctx, book("queue-two").id).size >= 3 }
        val kept = Library.segments(ctx, book("queue-two").id)
        val wordsKept = book("queue-two").words
        assertTrue(wordsKept > 0)

        // Taking hold of the handle of the book being made moves it at once and drops it a place: the book behind it is made first,
        // and the speech it had made is kept.
        dragHandleBy("queue-two", top("book-queue-three") - top("book-queue-two"))
        compose.waitUntil(90_000) { book("queue-three").status != Status.QUEUED && book("queue-two").status == Status.QUEUED }
        compose.waitUntil(30_000) { text("status-queue-two") == "Queued · position 2" }
        assertTrue(top("book-queue-three") < top("book-queue-two") && top("book-queue-two") < top("book-queue-one"))
        val stopped = Library.segments(ctx, book("queue-two").id)
        assertEquals("the speech made so far is kept", kept, stopped.take(kept.size))
        assertTrue("and so are its words", book("queue-two").words >= wordsKept)

        // Let them finish: one at a time, in the order the shelf shows, and the stopped book went on from where it was.
        val finished = ArrayList<String>()
        speechHold.complete(Unit)
        compose.waitUntil(240_000) {
            for (b in Library.books.value) if (b.status == Status.READY && b.title !in finished) finished += b.title
            assertTrue("more than one book at once", Library.books.value.count { it.status != Status.QUEUED && it.importing } <= 1)
            Library.books.value.size == 3 && Library.books.value.all { it.status == Status.READY }
        }
        assertEquals(listOf("queue-three", "queue-two", "queue-one"), finished)
        val all = Library.segments(ctx, book("queue-two").id)
        assertEquals("the kept speech starts the finished book", kept, all.take(kept.size))
        assertTrue("it carries on from there with no gap or overlap", all.zipWithNext().all { (a, b) -> b.start == a.end })
        compose.waitUntil(30_000) { !exists("section-importing") }
        compose.onNodeWithTag("section-imported").assertIsDisplayed()
        for (n in names) compose.onNodeWithTag("book-$n", useUnmergedTree = true).assertIsDisplayed()
    }
}
