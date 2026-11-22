/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * Chapter summaries and their spoken clips. The book, the reader, the files and the backup are real; the Claude call and the
 * voices are fakes, because a test must not spend a key or need a network or a model download.
 */
@RunWith(AndroidJUnit4::class)
class SummaryTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    /** A voice that records what it was asked to speak, and speaks it as [FakeSynthesizer] does. */
    private class Recording(private val into: MutableList<String>) : Synthesizer {
        private val tone = FakeSynthesizer()
        override suspend fun speak(text: String, wav: File) { into += text; tone.speak(text, wav) }
        override fun close() = Unit
    }

    private val sentences = listOf("Chapter One", "Body one a.", "Body one b.", "Chapter Two", "Body two a.", "Body two b.")
    private val chapterStarts = listOf(0, 3)
    private val neuralSpoke = Collections.synchronizedList(ArrayList<String>())
    private val phoneSpoke = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun setUp() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options(spell = false, voice = Voice.PIPER).save(ctx)
        SummarySettings().save(ctx)
        Engines.prepareNeural = { _, _, _ -> }
        Engines.neural = { _, _ -> Recording(neuralSpoke) }
        Engines.synthesizer = { Recording(phoneSpoke) }
        Engines.summariser = { digesting { title -> ChapterDigest("Overview of $title. It has two sentences.", listOf("Lesson of $title")) } }
    }

    @After
    fun restore() {
        Engines.summariser = { ClaudeSummariser(it) }
        Engines.checkKey = { ClaudeSummariser.check(it) }
        Engines.neural = { c, v -> NeuralVoice.Lane(c, v) }
        Engines.prepareNeural = { c, v, progress -> NeuralVoice.ensure(c, v, progress) }
        Engines.synthesizer = { AndroidSynthesizer(it) }
        Options().save(ctx)
        SummarySettings().save(ctx)
        scenario?.close()
    }

    private fun digesting(make: (title: String) -> ChapterDigest) = object : Summariser {
        override suspend fun summarise(chapterTitle: String, text: String) = make(chapterTitle)
    }

    private fun clip(id: String, index: Int) = File(Library.dir(ctx, id), Files.summaryAudio(index))

    @Smoke @Test
    fun theChosenVoiceSpeaksEachSummaryAndTheClipPlaysFromItsBoxAndIsBackedUp() {
        val id = Fixtures.seedSpokenBook(ctx, sentences = sentences)
        assertEquals(chapterStarts, Library.chapters(ctx, id).map { it.index })

        runBlocking { Summaries.run(ctx, id, audio = true) }

        assertEquals(2, Summaries.load(ctx, id).summaries.size)
        assertTrue(chapterStarts.all { clip(id, it).length() > 0 && Summaries.load(ctx, id).at(it)?.audio == true })
        assertFalse("scratch file left behind", File(Library.dir(ctx, id), "summary.pcm").exists())
        // The voice chosen in the options spoke: the overview in its sentences, the lead-in, then each lesson; the phone's voice was not used.
        assertTrue("spoke $neuralSpoke", neuralSpoke.containsAll(listOf("Overview of Chapter One.", "It has two sentences.", "Key lessons.", "Lesson of Chapter One")))
        assertEquals(emptyList<String>(), phoneSpoke.toList())
        // The book's own transcript and audio are unchanged by the summaries.
        assertEquals(sentences, Library.segments(ctx, id).map { it.text })

        val entries = java.util.zip.ZipInputStream(ctx.contentResolver.openInputStream(Backup.zip(ctx, id, "fixture"))!!)
            .use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertTrue("backup holds $entries", entries.containsAll(listOf(Files.SUMMARIES) + chapterStarts.map { Files.summaryAudio(it) }))

        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("book-${Fixtures.TITLE}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("summary-listen-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("summary-0").assert(hasText("Overview of Chapter One", substring = true))
        compose.onNodeWithTag("summary-listen-0").assert(hasText("Listen")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("summary-listen-0").fetchSemanticsNodes().isNotEmpty() && runCatching { compose.onNodeWithTag("summary-listen-0").assert(hasText("Stop")) }.isSuccess }
    }

    @Test
    fun noClipIsMadeUnlessSpeakingIsAskedFor() {
        val id = Fixtures.seedSpokenBook(ctx, sentences = sentences)
        runBlocking { Summaries.run(ctx, id, audio = false) }
        assertEquals(2, Summaries.load(ctx, id).summaries.size)
        assertTrue(chapterStarts.none { clip(id, it).exists() })
        assertEquals(emptyList<String>(), neuralSpoke.toList())
    }

    @Test
    fun aFailurePartWayKeepsTheSummariesMadeAndARetryMakesTheRest() {
        val id = Fixtures.seedSpokenBook(ctx, sentences = sentences)
        var offline = true
        Engines.summariser = {
            digesting { title -> if (title == "Chapter Two" && offline) throw SummaryFailure("offline") else ChapterDigest("Overview of $title.", listOf("Lesson of $title")) }
        }
        runBlocking { Summaries.run(ctx, id, audio = true) }
        val first = Summaries.load(ctx, id)
        assertEquals("offline", first.failure)
        assertEquals(listOf(0), first.summaries.map { it.index })
        assertTrue(clip(id, 0).exists() && !clip(id, 3).exists())

        offline = false
        runBlocking { Summaries.run(ctx, id, audio = true) }
        val second = Summaries.load(ctx, id)
        assertEquals(null, second.failure)
        assertEquals(chapterStarts, second.summaries.map { it.index })
        assertTrue(clip(id, 3).exists())
    }

    @Test
    fun aChapterIsSummarisedOnceTheNextHeadingIsReadWhileTheBookIsStillBeingRead() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val pdf = File(ctx.cacheDir, "summary-book.pdf")
        assets.open("ocr/varieties-preface.pdf").use { i -> pdf.outputStream().use { i.copyTo(it) } }
        val pages = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
        val last = pages - 1
        // A heading on the first page, then the fixture's own text; the last page is held, so the text is never whole until it is let through.
        // The fixture's own text, with a heading added above the first page.
        val text = cannedOcr(assets, "varieties-preface", pages)
        Engines.recognisePage = { page -> (if (page == 0) listOf("Chapter One") else emptyList()) + text(page) }
        Engines.surveyPages = { emptyList() }
        SummarySettings(enabled = true, audio = true, apiKey = "sk-test", keyChecked = true).save(ctx)
        val held = CompletableDeferred<Unit>()
        Engines.beforeRecognise = { page, _ -> if (page == last) held.await() }
        try {
            scenario = ActivityScenario.launch(
                Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", pdf), "application/pdf"),
            )
            val end = System.currentTimeMillis() + 120_000
            fun book() = Library.books.value.singleOrNull()
            while (book()?.let { b -> Summaries.load(ctx, b.id).summaries.isNotEmpty() } != true) {
                assertTrue("no summary while the book is ${book()?.status}", System.currentTimeMillis() < end)
                Thread.sleep(100)
            }
            val id = book()!!.id
            val made = Summaries.load(ctx, id).summaries
            // The last page is still held: the book is being read, and the first chapter, with the next heading after it, is already summarised.
            assertEquals(Status.READING, book()!!.status)
            assertEquals("Chapter One", made.first().title)
            assertTrue("no clip before the book is ready", made.none { clip(id, it.index).exists() })
        } finally {
            held.complete(Unit)
            Engines.beforeRecognise = { _, _ -> }
            Engines.recognisePage = null
            Engines.surveyPages = null
        }
        // Once the book is ready every chapter is summarised and every clip is made.
        val ready = System.currentTimeMillis() + 120_000
        val id = Library.books.value.single().id
        while (Library.books.value.single().status != Status.READY || Summaries.load(ctx, id).summaries.size < Library.chapters(ctx, id).size ||
            Library.chapters(ctx, id).any { !clip(id, it.index).exists() }
        ) {
            assertTrue("not finished: ${Library.books.value.single().status}", System.currentTimeMillis() < ready)
            Thread.sleep(100)
        }
    }

    @Test
    fun theSummariesCanOnlyBeTurnedOnWithAKeyThatWorksAndAWarningShowsWhenItStopsWorking() {
        var answer = KeyStatus.REJECTED
        Engines.checkKey = { answer }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings-panel").performTouchInput { swipeUp() }
        compose.onNodeWithTag("opt-summaries").assertIsNotEnabled()
        compose.onNodeWithTag("check-key").assertIsNotEnabled()
        compose.onNodeWithTag("api-key").performTextInput("sk-test")
        compose.onNodeWithTag("check-key").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("key-status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("key-status").assert(hasText("Anthropic rejected this key."))
        compose.onNodeWithTag("opt-summaries").assertIsNotEnabled()
        assertFalse(SummarySettings.load(ctx).keyChecked)

        answer = KeyStatus.VALID
        compose.onNodeWithTag("check-key").performClick()
        compose.waitUntil(5_000) { SummarySettings.load(ctx).keyChecked }
        compose.onNodeWithTag("opt-summaries").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { SummarySettings.load(ctx).enabled }
        assertEquals(0, compose.onAllNodesWithTag("summary-key-warning").fetchSemanticsNodes().size)

        // Anthropic stops accepting the key: the summaries stay on, so the app says they are not working.
        answer = KeyStatus.REJECTED
        compose.onNodeWithTag("check-key").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("summary-key-warning").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(SummarySettings.load(ctx).warn)

        // Being offline says nothing about the key.
        answer = KeyStatus.UNREACHABLE
        compose.onNodeWithTag("check-key").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithTag("key-status").fetchSemanticsNode().config.toString().contains("Could not reach") }
        assertTrue(SummarySettings.load(ctx).warn)
    }
}
