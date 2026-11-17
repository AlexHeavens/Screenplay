/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * Tapping a sentence that is read but not yet spoken moves speech of a book being made to it: that sentence is spoken before the ones
 * between the old place and it, and playback starts there. The engine is a [FakeSynthesizer] held shut after its first sentence, so the
 * order in which the book's sentences reach it is the order speech chose.
 */
@RunWith(AndroidJUnit4::class)
class JumpToPositionUiTest {
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
    }

    @After
    fun restore() {
        Engines.synthesizer = { AndroidSynthesizer(it) }
        Engines.recognisePage = null
        Engines.surveyPages = null
        scenario?.close()
    }

    private fun waitUntil(what: String, timeout: Long = 300_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition()) {
            assertTrue("timed out waiting for $what; book is ${Library.books.value.singleOrNull()}", System.currentTimeMillis() < end)
            Thread.sleep(100)
        }
    }

    @Test
    fun tappingAnUnspokenSentenceMovesSpeechAndPlaybackThereAndTheSkippedTextIsSpokenAfterTheEnd() {
        val pdf = File(ctx.cacheDir, "varieties-preface.pdf")
        assets.open("ocr/varieties-preface.pdf").use { i -> pdf.outputStream().use { i.copyTo(it) } }
        val pages = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
        Engines.recognisePage = cannedOcr(assets, "varieties-preface", pages)
        Engines.surveyPages = { emptyList() }
        val gate = CompletableDeferred<Unit>()
        val reached = Collections.synchronizedList(ArrayList<String>())
        Engines.synthesizer = {
            val fake = FakeSynthesizer()
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) {
                    val first = reached.isEmpty()
                    reached += text
                    if (!first) gate.await()
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
        // The whole text is read while speech waits, so the last sentence is far beyond the few the engines already hold.
        waitUntil("all the text read") { Library.books.value.singleOrNull()?.status == Status.SPEAKING }
        val id = Library.books.value.single().id
        val text = Library.queued(ctx, id)
        val last = text.size - 1
        val between = 12
        assertTrue("the fixture needs more sentences than the engines hold: ${text.size}", last > between + 1)

        compose.onNodeWithTag("book-varieties-preface").performClick()
        compose.onNodeWithTag("scrub-right").performTouchInput { swipeDown(startY = top, endY = bottom) }
        compose.waitUntil(30_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "queued-$last")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("queued-$last").performClick()
        assertEquals(last, Speaking.from(ctx, id))
        // At once, before any audio: the sentence tapped shows it is loading.
        compose.onNodeWithTag("queued-$last").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading"))

        gate.complete(Unit)
        // Playback starts at the sentence tapped, as soon as it has audio.
        compose.waitUntil(60_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-$last").and(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("play").assertTextEquals("Pause")

        waitUntil("the finished book") { Library.books.value.singleOrNull()?.status == Status.READY }
        val order = reached.toList()
        fun spokenAt(i: Int) = order.indexOf(SpokenText.of(text[i]))
        assertTrue("the sentence tapped was spoken before the ones between: tapped at ${spokenAt(last)}, between at ${spokenAt(between)}", spokenAt(last) in 0 until spokenAt(between))
        // The text skipped is spoken after the end of the book, and no sentence is spoken twice.
        assertTrue("sentence 0 came first, then the skipped text after the end", spokenAt(0) < spokenAt(last) && spokenAt(between) > spokenAt(last))
        assertEquals(text.size, order.size)
        assertEquals(order.size, order.toSet().size)
        // The finished book is in the order of the text, and its audio is whole.
        assertEquals(text.map { it.trim() }, Library.segments(ctx, id).map { it.text.replace('\n', ' ').trim() })
        assertTrue(File(Library.dir(ctx, id), Files.AUDIO).length() > 0)
        assertTrue(!File(Library.dir(ctx, id), Files.JUMP).exists())
    }
}
