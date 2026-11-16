/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * An imported book takes its title and author from its ISBN. The shelf and import are real; OCR text is canned
 * (a copyright page with an ISBN), the speech engine is [FakeSynthesizer] and the catalogue is a fake lookup.
 */
@RunWith(AndroidJUnit4::class)
class IsbnImportTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val asked = ArrayList<String>()

    /** The fixture PDF has one page: a title, a copyright line with the ISBN, then a chapter of prose. */
    private val page = listOf(
        "A Short History of Foxes",
        "First published 1998. ISBN 978-0-306-40615-7 Printed in Spain.",
        "Chapter 1",
        "The quick brown fox jumps over the lazy dog and then it goes home to sleep for the whole of the night, while the moon rises over the quiet hills and the river runs on.",
    )

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options(spell = false).save(ctx)
        Engines.synthesizer = { FakeSynthesizer() }
        Engines.recognisePage = { page }
        Engines.surveyPages = { emptyList() }
    }

    @After
    fun restore() {
        Engines.recognisePage = null
        Engines.surveyPages = null
        Engines.lookupBook = { isbn -> Isbn.openLibrary(isbn) }
        scenario?.close()
    }

    private fun importFixture() {
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(Fixtures.pdf(ctx), "application/pdf"),
        )
    }

    @Smoke @Test
    fun theShelfShowsTheTitleAndAuthorFoundFromTheIsbn() {
        Engines.lookupBook = { isbn -> asked += isbn; BookInfo("A Short History of Foxes", "Vulpes Vulpes") }
        importFixture()
        compose.waitUntil(120_000) { Library.books.value.singleOrNull()?.title == "A Short History of Foxes" }
        val author = androidx.compose.ui.test.hasTestTag("author-A Short History of Foxes")
        compose.waitUntil(30_000) { compose.onAllNodes(author, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("author-A Short History of Foxes", useUnmergedTree = true).assertTextEquals("Vulpes Vulpes")
        assertEquals(listOf("9780306406157"), asked)
    }

    @Smoke @Test
    fun aFailedLookupKeepsTheFileTitleAndTheBookStillFinishes() {
        Engines.lookupBook = { throw java.io.IOException("offline") }
        importFixture()
        compose.waitUntil(120_000) { Library.books.value.singleOrNull()?.status == Status.READY }
        assertEquals(Fixtures.TITLE, Library.books.value.single().title)
        assertEquals("", Library.books.value.single().author)
    }
}
