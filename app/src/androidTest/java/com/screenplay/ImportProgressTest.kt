/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The reader of a book that is still being imported shows two progresses, pages read and speech made, and cannot play
 * until the first audio exists. OCR, the shelf and the reader are real; the speech engine is [FakeSynthesizer].
 * The first page is held before OCR, so "no audio yet" is real, then the last, so the book is half made.
 */
@RunWith(AndroidJUnit4::class)
class ImportProgressTest {
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
        Engines.synthesizer = { FakeSynthesizer() }
    }

    @After
    fun restore() {
        Engines.beforeRecognise = { _, _ -> }
        Engines.recognisePage = null
        Engines.surveyPages = null
        scenario?.close()
    }

    private fun progress(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun waitForNode(tag: String, timeout: Long = 60_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Smoke @Test
    fun readerGreysOutPlayUntilSpeechExistsAndShowsPageAndSpeechProgressWhileImporting() {
        val pdf = File(ctx.cacheDir, "varieties-preface.pdf")
        assets.open("ocr/varieties-preface.pdf").use { i -> pdf.outputStream().use { i.copyTo(it) } }
        val last = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } } - 1
        assertTrue("the fixture needs more than one page", last > 0)
        Engines.recognisePage = cannedOcr(assets, "varieties-preface", last + 1)
        Engines.surveyPages = { emptyList() }
        val firstPage = CompletableDeferred<Unit>()
        val lastPage = CompletableDeferred<Unit>()
        Engines.beforeRecognise = { page, _ ->
            if (page == 0) firstPage.await()
            if (page == last) lastPage.await()
        }

        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", pdf), "application/pdf"),
        )
        // Nothing has been read, so nothing can have been spoken.
        compose.waitUntil(60_000) { Library.books.value.singleOrNull()?.status == Status.READING }
        compose.onNodeWithTag("book-varieties-preface").performClick()
        waitForNode("progress-read")
        compose.onNodeWithTag("play").assertIsNotEnabled()
        val readAtStart = progress("progress-read")
        assertTrue("no speech before the first page is read", progress("progress-speech") == 0f)

        // The pages before the last arrive and are spoken, with the last still held.
        firstPage.complete(Unit)
        compose.waitUntil(120_000) { Library.books.value.singleOrNull()?.playable == true }
        compose.waitUntil(30_000) { progress("progress-read") > readAtStart && progress("progress-speech") > 0f }
        compose.onNodeWithTag("play").assertIsEnabled()
        val read = progress("progress-read")
        val speech = progress("progress-speech")
        assertTrue("pages read is a part of the book: $read", read > 0f && read < 1f)
        assertTrue("speech is no more than the pages read: $speech of $read", speech <= read)

        // The last page finishes the book: both progresses go away and play stays enabled.
        lastPage.complete(Unit)
        compose.waitUntil(120_000) { Library.books.value.singleOrNull()?.status == Status.READY }
        compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("progress-read")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("progress-speech").assertDoesNotExist()
        compose.onNodeWithTag("play").assertIsEnabled()
    }
}
