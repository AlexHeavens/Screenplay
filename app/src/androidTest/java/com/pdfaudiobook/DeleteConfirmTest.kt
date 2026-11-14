/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Deleting a book asks first: Cancel keeps it, Delete removes it. Real UI and real library; nothing is faked. */
@RunWith(AndroidJUnit4::class)
class DeleteConfirmTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun seededBook() {
        File(ctx.filesDir, "books").deleteRecursively()
        val dir = Library.dir(ctx, "seed0001").apply { mkdirs() }
        File(dir, Files.META).writeText(
            JSONObject().put("title", "Doomed Book").put("status", "READY").put("duration", 3000L).put("created", 1L).toString(),
        )
        Library.refresh(ctx)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario?.close()
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
    }

    private fun bookShown() = compose.onAllNodesWithTag("book-Doomed Book").fetchSemanticsNodes().isNotEmpty()

    @Smoke
    @Test
    fun deleteAsksFirstCancelKeepsAndConfirmDeletes() {
        compose.onNodeWithTag("delete-Doomed Book").performClick()
        compose.onNodeWithText("Delete audiobook?").assertIsDisplayed()
        compose.onNodeWithText("“Doomed Book” will be removed", substring = true).assertIsDisplayed()
        assertTrue("book must survive until confirmed", bookShown())

        compose.onNodeWithTag("cancel-delete").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete audiobook?").fetchSemanticsNodes().isEmpty() }
        assertTrue("Cancel keeps the book", bookShown())
        assertTrue(File(ctx.filesDir, "books/seed0001").exists())

        compose.onNodeWithTag("delete-Doomed Book").performClick()
        compose.onNodeWithTag("confirm-delete").performClick()
        compose.waitUntil(10_000) { !bookShown() }
        assertTrue("Delete removes the folder", !File(ctx.filesDir, "books/seed0001").exists())
    }
}
