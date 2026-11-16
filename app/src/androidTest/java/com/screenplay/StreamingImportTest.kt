/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A book can be played while it is still being read. OCR, the player and the transcript are real; the speech engine is
 * [FakeSynthesizer]. A fixture's last page is held before OCR, so "audio exists" can only be true if speech started
 * without waiting for the end of the book.
 */
@RunWith(AndroidJUnit4::class)
class StreamingImportTest {
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
        Engines.surveyPages = null
        scenario?.close()
    }

    private fun fixture(name: String): File {
        val pdf = File(ctx.cacheDir, "$name.pdf")
        assets.open("ocr/$name.pdf").use { i -> pdf.outputStream().use { i.copyTo(it) } }
        // The book's own text, not a fresh OCR pass: what is under test is how pages become audio, and OCR is slow.
        Engines.recognisePage = cannedOcr(assets, name, pageCount(pdf))
        Engines.surveyPages = { emptyList() }
        return pdf
    }

    private fun pageCount(pdf: File) = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { it.pageCount }
    }

    private fun waitUntil(what: String, timeout: Long = 120_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition()) {
            assertTrue("timed out waiting for $what; book is ${Library.books.value.singleOrNull()}", System.currentTimeMillis() < end)
            Thread.sleep(100)
        }
    }

    @Smoke @Test
    fun audioOfTheFirstPagesIsPlayableBeforeTheLastPageHasBeenRead() {
        val pdf = fixture("varieties-preface")
        val last = pageCount(pdf) - 1
        assertTrue("the fixture needs more than one page", last > 0)
        val lastPageReached = CompletableDeferred<Unit>()
        val letLastPageThrough = CompletableDeferred<Unit>()
        Engines.beforeRecognise = { page, _ ->
            if (page == last) { lastPageReached.complete(Unit); letLastPageThrough.await() }
        }

        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", pdf), "application/pdf"),
        )
        // The last page is held, so everything the book can do from here, it does with that page unread.
        waitUntil("audio before the last page") { Library.books.value.singleOrNull()?.playable == true }
        val book = Library.books.value.single()
        assertEquals(Status.READING, book.status)
        assertTrue("the last page was not held: ${book.pages} of ${book.pagesTotal} pages read", book.pages < book.pagesTotal)
        assertTrue(Library.segments(ctx, book.id).isNotEmpty())
        assertTrue(File(Library.dir(ctx, book.id), "audio.pcm").length() > 0)
        assertTrue(!File(Library.dir(ctx, book.id), Files.AUDIO).exists())

        // The shelf lets the reader open it, and the reader shows the text and plays the audio that exists.
        compose.onNodeWithTag("book-varieties-preface").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-0")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("play").performClick()
        compose.onNodeWithTag("play").assertTextEquals("Pause")
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("0:00 /", substring = true).not()).fetchSemanticsNodes().isNotEmpty()
        }

        // Letting the last page through finishes the book, and the transcript grows to hold its sentences too.
        val before = Library.segments(ctx, book.id).size
        letLastPageThrough.complete(Unit)
        waitUntil("the finished book") { Library.books.value.singleOrNull()?.status == Status.READY }
        assertTrue(Library.segments(ctx, book.id).size >= before)
        assertTrue(File(Library.dir(ctx, book.id), Files.AUDIO).length() > 0)
        assertTrue(!File(Library.dir(ctx, book.id), "audio.pcm").exists())
    }

    @Test
    fun textThatIsReadButNotYetSpokenShowsInTheReader() {
        val pdf = fixture("varieties-preface")
        val gate = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        Engines.synthesizer = {
            val fake = FakeSynthesizer()
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) {
                    if (calls.getAndIncrement() > 0) gate.await()
                    fake.speak(text, wav)
                }
                override fun close() = fake.close()
            }
        }
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", pdf), "application/pdf"),
        )
        waitUntil("the first sentence spoken") { Library.books.value.singleOrNull()?.playable == true }
        waitUntil("more text read than spoken") { Library.queued(ctx, Library.books.value.single().id).size > Library.segments(ctx, Library.books.value.single().id).size }
        val id = Library.books.value.single().id

        compose.onNodeWithTag("book-varieties-preface").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "queued-${Library.segments(ctx, id).size}")).fetchSemanticsNodes().isNotEmpty()
        }

        gate.complete(Unit)
        waitUntil("the finished book") { Library.books.value.singleOrNull()?.status == Status.READY }
        assertTrue(!File(Library.dir(ctx, id), Files.QUEUE).exists())
    }

    @Test
    fun theScrollBarReachesTextThatIsReadButNotYetSpoken() {
        val pdf = fixture("varieties-preface")
        val gate = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        Engines.synthesizer = {
            val fake = FakeSynthesizer()
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) {
                    if (calls.getAndIncrement() > 0) gate.await()
                    fake.speak(text, wav)
                }
                override fun close() = fake.close()
            }
        }
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", pdf), "application/pdf"),
        )
        waitUntil("the first sentence spoken") { Library.books.value.singleOrNull()?.playable == true }
        waitUntil("more text read than spoken") { Library.queued(ctx, Library.books.value.single().id).size > Library.segments(ctx, Library.books.value.single().id).size }
        val id = Library.books.value.single().id

        compose.onNodeWithTag("book-varieties-preface").performClick()
        val lastRead = Library.queued(ctx, id).size - 1
        compose.onNodeWithTag("scrub-right").performTouchInput { swipeDown(startY = top, endY = bottom) }
        compose.waitUntil(30_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "queued-$lastRead")).fetchSemanticsNodes().isNotEmpty()
        }
        gate.complete(Unit)
    }

    /** What the fake engine makes of a transcript: 400 ms of tone per word, then the gap. */
    private fun expectedSegments(sentences: List<String>): List<Segment> {
        val rate = 16000
        var bytes = 0L
        return sentences.map { s ->
            val start = bytes * 500 / rate
            bytes += (rate * 4 / 10 * s.split(" ").size) * 2L
            bytes += (rate * 2L * Speech.GAP_MS / 1000).toInt() and 1.inv()
            Segment(start, bytes * 500 / rate, s)
        }
    }

    @Test
    fun aFinishedBookHasTheSameTranscriptAndAudioAsReadingTheWholeBookFirst() {
        for (name in listOf("varieties-preface", "gibbon")) {
            File(ctx.filesDir, "books").deleteRecursively()
            Library.refresh(ctx)
            val pdf = fixture(name)
            Library.import(ctx, FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", pdf))
            waitUntil("$name finished") { Library.books.value.singleOrNull()?.status.let { it == Status.READY || it == Status.FAILED } }
            val book = Library.books.value.single()
            assertEquals(Status.READY, book.status)

            val whole = Text.prepare(runBlocking { PdfText.read(pdf) { _, _ -> } }, Options())
            val segments = Library.segments(ctx, book.id)
            assertEquals("$name: sentences", whole, segments.map { it.text })
            assertEquals("$name: audio timeline", expectedSegments(whole), segments)
            assertEquals("$name: duration", segments.last().end, book.durationMs)
        }
    }
}
