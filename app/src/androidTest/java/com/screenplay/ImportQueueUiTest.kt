/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

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
import androidx.compose.ui.test.onAllNodesWithText
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
import java.util.concurrent.atomic.AtomicBoolean
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

    private companion object {
        /** No test here plays a book, so its fake speech is short: the speaking stage encodes a tenth of the default audio. */
        const val QUICK_SPEECH_MS = 40
    }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options(spell = false).save(ctx)
        Engines.synthesizer = { FakeSynthesizer(msPerWord = QUICK_SPEECH_MS) }
        Engines.surveyPages = { emptyList() }
    }

    @After
    fun restore() {
        Engines.beforeRecognise = { _, _ -> }
        Engines.recognisePage = null
        Engines.surveyPages = null
        Engines.beforeMoveWrite = {}
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

    /**
     * Imports [names] through the shelf's import button, one after another, with the first page of OCR held until [release] completes:
     * returns once the first is being made and the others are queued behind it.
     */
    private fun importQueued(names: List<String>, release: CompletableDeferred<Unit>) {
        val files = names.map { n -> File(ctx.cacheDir, "$n.pdf").also { f -> assets.open("ocr/varieties-preface.pdf").use { i -> f.outputStream().use { i.copyTo(it) } } } }
        val pages = ParcelFileDescriptor.open(files[0], ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
        Engines.recognisePage = cannedOcr(assets, "varieties-preface", pages)
        Engines.beforeRecognise = { page, _ -> if (page == 0) release.await() }
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
        compose.waitUntil(60_000) { Library.books.value.count { it.status == Status.READING } == 1 }
    }

    @Smoke @Test
    fun booksAreMadeOneAtATimeAndAQueuedBookCanBeDraggedUp() {
        val names = listOf("queue-one", "queue-two", "queue-three")
        val release = CompletableDeferred<Unit>()
        // Speech stops after a few sentences, so a book stays part-spoken while the test reorders it.
        val speechHold = CompletableDeferred<Unit>()
        val spoken = AtomicInteger()
        Engines.synthesizer = {
            val inner = FakeSynthesizer(msPerWord = QUICK_SPEECH_MS)
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) { if (spoken.incrementAndGet() > 6) speechHold.await(); inner.speak(text, wav) }
                override fun close() = inner.close()
            }
        }

        importQueued(names, release)
        // Only the first is being made; the others are queued, with their place in line.
        compose.onNodeWithTag("section-importing").assertIsDisplayed()
        compose.waitUntil(30_000) { exists("stage-bar-READ-queue-one") }
        assertTrue("a waiting row says nothing in words: its place is its row", names.none { exists("status-$it") })
        assertEquals(1, Library.books.value.count { it.status == Status.READING })
        assertEquals(2, Library.books.value.count { it.status == Status.QUEUED })
        assertTrue("nothing is imported yet", !exists("section-imported"))

        // There is no Move up button: a book moves only by being dragged.
        assertTrue(names.none { exists("move-up-$it") })
        assertTrue("no drop line while nothing is dragged", names.none { exists("drop-line-$it") })
        assertTrue(top("book-queue-two") < top("book-queue-three"))
        dragBy("book-queue-three", top("book-queue-two") - top("book-queue-three"))
        compose.waitUntil(30_000) { top("book-queue-three") < top("book-queue-two") }
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
        assertTrue(top("book-queue-one") < top("book-queue-two"))

        // Dropping the first waiting book onto the book being made puts it first: it is made now, and the book it passed waits in its place.
        val row = top("book-queue-three") - top("book-queue-two")
        dragBy("book-queue-two", -row)
        // The book being made stops after the page it is reading, which can take a while on the emulator.
        compose.waitUntil(90_000) { Library.books.value.single { it.title == "queue-two" }.status != Status.QUEUED }
        compose.waitUntil(90_000) { Library.books.value.single { it.title == "queue-one" }.status == Status.QUEUED }
        compose.waitUntil(30_000) { top("book-queue-two") < top("book-queue-one") && top("book-queue-one") < top("book-queue-three") }
        assertTrue(top("book-queue-two") < top("book-queue-one") && top("book-queue-one") < top("book-queue-three"))

        // Dropping a waiting book onto the last place works too.
        dragBy("book-queue-one", row)
        compose.waitUntil(30_000) { top("book-queue-three") < top("book-queue-one") }
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
        compose.waitUntil(30_000) { top("book-queue-three") < top("book-queue-two") }
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
        assertEquals("the kept speech starts the finished book", kept.map { it.copy(at = -1) }, all.take(kept.size).map { it.copy(at = -1) })
        assertTrue("it carries on from there with no gap or overlap", all.zipWithNext().all { (a, b) -> b.start == a.end })
        compose.waitUntil(30_000) { !exists("section-importing") }
        compose.onNodeWithTag("section-imported").assertIsDisplayed()
        for (n in names) compose.onNodeWithTag("book-$n", useUnmergedTree = true).assertIsDisplayed()
    }

    /** Takes hold of the handle of [title] and drops it on the row of [target], at once, and lets go. */
    private fun dropOnRowOf(title: String, target: String) = dragHandleBy(title, top("book-$target") - top("book-$title"))

    @Test
    fun aBookDroppedFirstIsListedFirstAtOnceWhileTheMoveIsStillBeingSaved() {
        val names = listOf("lag-one", "lag-two", "lag-three")
        val release = CompletableDeferred<Unit>()
        importQueued(names, release)
        // Saving the move takes 1.5 s: a shelf that waits for the disk, or for the running book to stop, cannot show the new order in under one.
        val savingDone = AtomicBoolean()
        Engines.beforeMoveWrite = { Thread.sleep(1_500); savingDone.set(true) }
        try {
            // The last waiting book is dropped on the running book: it goes first, the running book it passed waits behind it.
            val dropped = android.os.SystemClock.elapsedRealtime()
            dropOnRowOf("lag-three", "lag-one")
            compose.waitUntil(1_000) { top("book-lag-three") < top("book-lag-one") }
            android.util.Log.i("ImportQueueUiTest", "the new order showed ${android.os.SystemClock.elapsedRealtime() - dropped} ms after the drop gesture began")
            assertTrue("the new order showed before the move was saved", !savingDone.get())
            // Once it is saved and the running book has stopped, the new first book is made, and the rows never went back to the old order on the way.
            compose.waitUntil(120_000) {
                assertTrue("the rows keep the new order", top("book-lag-three") < top("book-lag-one") && top("book-lag-one") < top("book-lag-two"))
                book("lag-three").status != Status.QUEUED && book("lag-one").status == Status.QUEUED
            }
        } finally {
            Engines.beforeMoveWrite = {}
            for (n in names) Library.delete(ctx, book(n).id)
        }
    }

    @Test
    fun aBookStoppedByAMoveCarriesOnAtItsStageAndNeverGoesThroughReadingAgain() {
        val names = listOf("keep-one", "keep-two")
        val release = CompletableDeferred<Unit>()
        // Speech stops after a few sentences, so the book stays in its speaking stage while the test moves things.
        val speechHold = CompletableDeferred<Unit>()
        val spoken = AtomicInteger()
        Engines.synthesizer = {
            val inner = FakeSynthesizer(msPerWord = QUICK_SPEECH_MS)
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) { if (spoken.incrementAndGet() > 6) speechHold.await(); inner.speak(text, wav) }
                override fun close() = inner.close()
            }
        }
        importQueued(names, release)
        try {
            release.complete(Unit)
            compose.waitUntil(120_000) { book("keep-one").status == Status.SPEAKING && Library.segments(ctx, book("keep-one").id).isNotEmpty() }
            val wordsBefore = book("keep-one").words
            val totalBefore = book("keep-one").wordsTotal
            assertTrue(totalBefore > 0)
            // The waiting book dropped first stops the speaking one, which goes back to the queue.
            dropOnRowOf("keep-two", "keep-one")
            compose.waitUntil(90_000) { book("keep-one").status == Status.QUEUED && book("keep-two").status != Status.QUEUED }
            // Dropped first again, it is made now: from here its row must never show Reading, and it keeps its words and total.
            dropOnRowOf("keep-one", "keep-two")
            var reading = 0
            compose.waitUntil(90_000) {
                if (book("keep-one").status == Status.READING) reading++
                book("keep-one").status != Status.QUEUED
            }
            repeat(20) { if (book("keep-one").status == Status.READING) reading++; Thread.sleep(100) }
            assertEquals("the book went through reading again", 0, reading)
            assertEquals(Status.SPEAKING, book("keep-one").status)
            assertEquals(totalBefore, book("keep-one").wordsTotal)
            assertTrue("its words were kept", book("keep-one").words >= wordsBefore)
            assertEquals("done", compose.onNodeWithTag("stage-READ-keep-one", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription])
        } finally {
            speechHold.complete(Unit)
            for (n in names) Library.delete(ctx, book(n).id)
        }
    }

    private fun importNotification() = ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications.singleOrNull { it.id == 1 }?.notification

    @Test
    fun theNotificationNamesTheBookAndItsStageAndPausesAndResumesItThroughTheRealWorker() {
        val names = listOf("note-one", "note-two")
        val release = CompletableDeferred<Unit>()
        // Speech stops after a few sentences, so the book stays in its speaking stage while the test presses the buttons.
        val speechHold = CompletableDeferred<Unit>()
        val spoken = AtomicInteger()
        Engines.synthesizer = {
            val inner = FakeSynthesizer(msPerWord = QUICK_SPEECH_MS)
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) { if (spoken.incrementAndGet() > 6) speechHold.await(); inner.speak(text, wav) }
                override fun close() = inner.close()
            }
        }
        importQueued(names, release)
        try {
            release.complete(Unit)
            compose.waitUntil(120_000) { book("note-one").status == Status.SPEAKING && Library.segments(ctx, book("note-one").id).isNotEmpty() }
            compose.waitUntil(30_000) { importNotification()?.extras?.getString(android.app.Notification.EXTRA_TITLE) == "note-one" }
            val running = importNotification()!!
            assertTrue(running.extras.getString(android.app.Notification.EXTRA_TEXT)!!.startsWith("Speaking "))
            assertEquals(listOf("Pause"), running.actions.map { it.title.toString() })

            // The Pause button is the notification's own PendingIntent, sent to the real receiver and worker.
            running.actions.single().actionIntent.send()
            compose.waitUntil(30_000) { importNotification()?.actions?.singleOrNull()?.title == "Resume" }
            val stopped = importNotification()!!
            assertTrue(stopped.extras.getString(android.app.Notification.EXTRA_TEXT)!!.startsWith("Paused · Speaking "))
            val wordsAtPause = book("note-one").words
            assertEquals("the stage is kept, the book does not go back to waiting", Status.SPEAKING, book("note-one").status)
            assertEquals("Paused", text("status-note-one"))
            Thread.sleep(2_000)
            assertEquals("nothing is made while paused", wordsAtPause, book("note-one").words)
            assertEquals(Status.QUEUED, book("note-two").status)

            // Resume carries on at the stage and progress the book had, never through reading again.
            stopped.actions.single().actionIntent.send()
            speechHold.complete(Unit)
            var reading = 0
            compose.waitUntil(240_000) {
                if (book("note-one").status == Status.READING) reading++
                Library.books.value.all { it.status == Status.READY }
            }
            assertEquals("the book went through reading again", 0, reading)
            assertTrue(book("note-one").words >= wordsAtPause)
        } finally {
            speechHold.complete(Unit)
            ImportQueue.resume()
            for (n in names) Library.books.value.firstOrNull { it.title == n }?.let { Library.delete(ctx, it.id) }
        }
    }

    @Test
    fun theHomeScreenPauseAndResumeShareTheNotificationsPausedState() {
        val names = listOf("home-one", "home-two")
        val release = CompletableDeferred<Unit>()
        val speechHold = CompletableDeferred<Unit>()
        val spoken = AtomicInteger()
        Engines.synthesizer = {
            val inner = FakeSynthesizer(msPerWord = QUICK_SPEECH_MS)
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) { if (spoken.incrementAndGet() > 6) speechHold.await(); inner.speak(text, wav) }
                override fun close() = inner.close()
            }
        }
        importQueued(names, release)
        try {
            release.complete(Unit)
            compose.waitUntil(120_000) { book("home-one").status == Status.SPEAKING && Library.segments(ctx, book("home-one").id).isNotEmpty() }
            compose.waitUntil(30_000) { exists("import-pause") }
            assertTrue(!exists("import-resume"))
            compose.onNodeWithTag("import-pause", useUnmergedTree = true).performClick()
            // The shelf, the queue and the notification all show the pause.
            compose.waitUntil(30_000) { exists("import-resume") && ImportQueue.paused.value }
            assertEquals("Paused", text("status-home-one"))
            assertEquals(Status.SPEAKING, book("home-one").status)
            compose.waitUntil(30_000) { importNotification()?.actions?.singleOrNull()?.title == "Resume" }
            // Resume from the notification: the home screen goes back to Pause.
            importNotification()!!.actions.single().actionIntent.send()
            compose.waitUntil(30_000) { exists("import-pause") && !ImportQueue.paused.value }
            compose.waitUntil(30_000) { importNotification()?.actions?.singleOrNull()?.title == "Pause" }
            // And Pause then Resume from the home screen carries on.
            compose.onNodeWithTag("import-pause", useUnmergedTree = true).performClick()
            compose.waitUntil(30_000) { exists("import-resume") }
            compose.onNodeWithTag("import-resume", useUnmergedTree = true).performClick()
            speechHold.complete(Unit)
            compose.waitUntil(240_000) { Library.books.value.all { it.status == Status.READY } }
        } finally {
            speechHold.complete(Unit)
            ImportQueue.resume()
            for (n in names) Library.books.value.firstOrNull { it.title == n }?.let { Library.delete(ctx, it.id) }
        }
    }

    @Test
    fun aSpeakingBookMovedBackInTheQueueKeepsShowingItsSpeakingStage() {
        val names = listOf("stage-one", "stage-two")
        val release = CompletableDeferred<Unit>()
        val speechHold = CompletableDeferred<Unit>()
        val spoken = AtomicInteger()
        Engines.synthesizer = {
            val inner = FakeSynthesizer()
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) { if (spoken.incrementAndGet() > 6) speechHold.await(); inner.speak(text, wav) }
                override fun close() = inner.close()
            }
        }
        importQueued(names, release)
        try {
            release.complete(Unit)
            compose.waitUntil(120_000) { book("stage-one").status == Status.SPEAKING && Library.segments(ctx, book("stage-one").id).isNotEmpty() }
            val state = androidx.compose.ui.semantics.SemanticsProperties.StateDescription
            assertEquals("in progress", compose.onNodeWithTag("stage-SPEAK-stage-one", useUnmergedTree = true).fetchSemanticsNode().config[state])
            // Dropped behind the waiting book, the speaking one stops and waits again, but it has not gone back to reading.
            dropOnRowOf("stage-two", "stage-one")
            compose.waitUntil(90_000) { book("stage-one").status == Status.QUEUED && book("stage-two").status != Status.QUEUED }
            assertEquals("done", compose.onNodeWithTag("stage-READ-stage-one", useUnmergedTree = true).fetchSemanticsNode().config[state])
            assertEquals("next", compose.onNodeWithTag("stage-SPEAK-stage-one", useUnmergedTree = true).fetchSemanticsNode().config[state])
            compose.onNodeWithTag("stage-text-SPEAK-stage-one", useUnmergedTree = true).assertExists()
        } finally {
            speechHold.complete(Unit)
            for (n in names) Library.delete(ctx, book(n).id)
        }
    }

    @Test
    fun aMoveThatCannotBeSavedPutsTheOldOrderBackAndSaysWhy() {
        val names = listOf("fail-one", "fail-two", "fail-three")
        importQueued(names, CompletableDeferred())
        Engines.beforeMoveWrite = { throw java.io.IOException("disk full") }
        try {
            dropOnRowOf("fail-three", "fail-one")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Could not move the book: disk full", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { top("book-fail-one") < top("book-fail-two") && top("book-fail-two") < top("book-fail-three") }
            assertEquals("the book being made carries on", 1, Library.books.value.count { it.status == Status.READING })
            assertEquals("fail-one", Library.books.value.single { it.status == Status.READING }.title)
        } finally {
            Engines.beforeMoveWrite = {}
            for (n in names) Library.delete(ctx, book(n).id)
        }
    }
}
