/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassagesTest {
    private val sentences = listOf(
        "The ministry guided industry through administrative guidance.",
        "Banks lent to the favoured firms at low rates.",
        "Weather in the islands was mild that year.",
        "Administrative guidance had no statutory force.",
    )

    @Test
    fun theQuestionsWordsChooseThePassagesThatHoldThem() {
        val chosen = Passages.pick(sentences, "What was administrative guidance?", budget = 10_000, near = 0, size = 60)
        assertEquals(listOf(sentences[0], sentences[3]), chosen)
    }

    @Test
    fun passagesComeBackInReadingOrderWithinTheBudget() {
        val chosen = Passages.pick(sentences, "administrative guidance", budget = sentences[3].length, near = 0, size = 60)
        assertEquals(1, chosen.size)
        assertTrue(chosen.sumOf { it.length } <= sentences[3].length)
    }

    @Test
    fun aQuestionWithNoWordInTheBookGetsThePassageWhereTheReaderIs() {
        val chosen = Passages.pick(sentences, "Tell me about volcanoes", budget = 10_000, near = 2, size = 60)
        assertEquals(listOf(sentences[2]), chosen)
    }

    @Test
    fun accentsAndCaseAreIgnored() {
        assertEquals(setOf("cafe", "ministry"), Passages.keywords("The CAFÉ ministry?"))
    }

    @Test
    fun sentencesAreJoinedIntoPassagesNoLongerThanTheSize() {
        val passages = Passages.split(sentences, 100)
        assertTrue(passages.all { it.second.length <= 100 })
        assertEquals(sentences.joinToString(" "), passages.joinToString(" ") { it.second })
    }
}

class ChatPromptTest {
    @Test
    fun thePromptEndsOpenForTheModelAndHoldsTheNotesAndTheQuestion() {
        val prompt = ChatPrompt.build("Dune", "Paul leaves.", listOf("Passage one."), emptyList(), "  Who leaves? ")
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
        assertTrue(prompt.contains("Summary of the chapter being read:\nPaul leaves."))
        assertTrue(prompt.contains("- Passage one."))
        assertTrue(prompt.contains("Question: Who leaves?"))
    }

    @Test
    fun onlyTheLastTwoEarlierTurnsAreKeptAndLongAnswersAreCut() {
        val earlier = List(4) { ChatTurn("q$it", "a$it" + "x".repeat(1000)) }
        val prompt = ChatPrompt.build("Dune", null, emptyList(), earlier, "next")
        assertTrue(!prompt.contains("q1") && prompt.contains("q2") && prompt.contains("q3"))
        assertTrue(!prompt.contains("x".repeat(401)))
        assertTrue(!prompt.contains("Summary of the chapter"))
    }
}
