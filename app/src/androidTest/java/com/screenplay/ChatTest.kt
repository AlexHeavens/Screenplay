/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/** The chat about a book. The book and the reader are real; the model is a fake, because a test must not load 1.6 GB. */
@RunWith(AndroidJUnit4::class)
class ChatTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val prompts = Collections.synchronizedList(ArrayList<String>())
    private val closed = java.util.concurrent.atomic.AtomicInteger()

    @Before
    fun setUp() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options(spell = false).save(ctx)
        SummarySettings().save(ctx)
        Engines.chat = {
            object : Chat {
                override suspend fun answer(prompt: String, onPiece: (String) -> Unit): String {
                    prompts += prompt
                    onPiece("It says")
                    return "It says two things."
                }

                override fun close() { closed.incrementAndGet() }
            }
        }
    }

    @After
    fun restore() {
        Engines.chat = { LocalChat(it) }
        scenario?.close()
    }

    private fun openBook() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("book-${Fixtures.TITLE}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("ask").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ask").performClick()
    }

    @Test
    fun aQuestionIsAnsweredFromThePassagesThatMatchIt() {
        Fixtures.seedSpokenBook(ctx, sentences = listOf("Chapter One", "The ministry guided industry.", "Weather was mild.", "Chapter Two", "Banks lent at low rates."))
        openBook()
        compose.onNodeWithTag("chat-input").performTextInput("What did the ministry do?")
        compose.onNodeWithTag("chat-send").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat-answer-0").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("chat-send").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-answer-0").assert(hasText("It says two things."))
        compose.onNodeWithTag("chat-question-0").assert(hasText("What did the ministry do?"))
        assertEquals(1, prompts.size)
        assertTrue(prompts.single().contains("Question: What did the ministry do?"))
        assertTrue(prompts.single().contains("The ministry guided industry."))
    }

    @Test
    fun anImportInProgressHoldsTheChatBack() {
        Fixtures.seedSpokenBook(ctx, sentences = listOf("Chapter One", "Body one."))
        val waiting = Fixtures.seedSpokenBook(ctx, title = "Another", sentences = listOf("Chapter One", "Body one."))
        Library.update(ctx, waiting) { it.copy(status = Status.QUEUED) }
        openBook()
        compose.onNodeWithTag("chat-blocked").assertExists()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
    }

    @Test
    fun leavingTheChatGivesTheModelBack() {
        Fixtures.seedSpokenBook(ctx, sentences = listOf("Chapter One", "Body one."))
        openBook()
        compose.onNodeWithTag("chat-input").assertExists()
        scenario?.close()
        scenario = null
        compose.waitUntil(10_000) { closed.get() > 0 }
    }
}
