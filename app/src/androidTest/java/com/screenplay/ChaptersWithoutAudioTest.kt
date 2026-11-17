/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Chapters belong to the text, not to the speech: a book that has been read but not spoken, with no audio at all,
 * lists its chapters and jumps to one. The library, reader and chapter list are real; the book's files are seeded as reading leaves them.
 */
@RunWith(AndroidJUnit4::class)
class ChaptersWithoutAudioTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options(spell = false, voice = Voice.PHONE).save(ctx)
    }

    @After
    fun close() { scenario?.close() }

    /** A book of [sentences] with the given status: no audio file, and no stored chapters. */
    private fun seed(status: Status, sentences: List<String>, spoken: Boolean = false): String {
        val id = "chapters1"
        val dir = Library.dir(ctx, id).apply { mkdirs() }
        File(dir, Files.NOTES).writeText("[]")
        val meta = JSONObject().put("title", "Unspoken").put("status", status.name).put("created", 1L)
        if (spoken) {
            val segments = sentences.mapIndexed { i, t -> Segment(i * 1000L, i * 1000L + 900, t) }
            Library.saveSegments(ctx, id, segments)
            meta.put("duration", segments.last().end)
        } else {
            File(dir, Files.QUEUE).writeText(sentences.joinToString("") { it + "\n" })
            meta.put("pages", 3).put("pagesTotal", 10)
        }
        File(dir, Files.META).writeText(meta.toString())
        Library.refresh(ctx)
        return id
    }

    private val sentences = List(40) { "Sentence number $it of the opening chapter goes on and on." } + "Chapter Two" + List(5) { "Second chapter sentence $it." }

    @Smoke @Test
    fun aBookReadButNotSpokenListsItsChaptersAndJumpsToOne() {
        seed(Status.READING, sentences)
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("book-Unspoken").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-Unspoken").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("queued-0").fetchSemanticsNodes().isNotEmpty() }
        // The heading is far down the text, so it is off screen until the list jumps to it.
        compose.onNodeWithTag("queued-40").assertIsNotDisplayed()
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("chapters").performClick() }.isSuccess && compose.onAllNodesWithTag("chapter-item-40").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chapter-item-40").assertTextEquals("Chapter Two")
        compose.onNodeWithTag("chapter-item-40").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("queued-40").assertIsDisplayed() }.isSuccess }
    }

    @Test
    fun aBookStoredWithoutChaptersGetsThemDetectedOnFirstUseAndKeepsThem() {
        val id = seed(Status.READY, sentences, spoken = true)
        assertEquals(null, Library.books.value.single().chapters)
        assertEquals(listOf(Chapter(40, 40_000, "Chapter Two")), Library.chapters(ctx, id))
        assertEquals(listOf(ChapterMark(40, "Chapter Two")), Library.books.value.single().chapters)
    }
}
