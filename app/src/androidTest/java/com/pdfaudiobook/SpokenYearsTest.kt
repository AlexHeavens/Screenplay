/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A year is spoken the way people say it, while the transcript keeps its digits. OCR and the transcript are real; the voice is a recording fake. */
@RunWith(AndroidJUnit4::class)
class SpokenYearsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        spoken.clear()
        Engines.synthesizer = {
            object : Synthesizer {
                override suspend fun speak(text: String, wav: File) { spoken += text; FakeSynthesizer().speak(text, wav) }
                override fun close() = Unit
            }
        }
    }

    @After
    fun close() {
        scenario?.close()
    }

    @Smoke @Test
    fun aYearReachesTheVoiceInWordsWhileTheTranscriptKeepsTheDigits() {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val paint = Paint().apply { color = Color.BLACK; textSize = 20f; isAntiAlias = true }
        page.canvas.drawText("The treaty was signed in 1969 by both sides.", 40f, 200f, paint)
        doc.finishPage(page)
        val pdf = File(ctx.cacheDir, "years.pdf")
        pdf.outputStream().use { doc.writeTo(it) }
        doc.close()

        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", pdf), "application/pdf"),
        )
        compose.waitUntil(120_000) { compose.onAllNodes(hasText("Ready", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-years").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-0")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("sentence-0").assert(hasText("in 1969", substring = true))
        assertTrue("the voice was given $spoken", spoken.any { "in nineteen sixty-nine" in it && "1969" !in it })
    }
}
