/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ln

/** One question the reader asked about the book, and what the model answered. */
data class ChatTurn(val question: String, val answer: String)

/**
 * The parts of the book to show the model with a question. The model's window holds a few pages at most, so the passages whose words best
 * match the question are chosen, with the words the book rarely uses counting most. It is pure: no Android, no I/O.
 */
object Passages {
    private val ignored = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was", "one", "our", "out", "has", "his", "how", "its",
        "who", "what", "when", "where", "which", "why", "does", "did", "this", "that", "with", "from", "have", "about", "book", "chapter", "say", "says",
    )
    private val word = Regex("[\\p{L}\\p{N}]+")

    /** The question's words that are worth matching, in lower case without accents. */
    fun keywords(question: String): Set<String> = word.findAll(Search.fold(question)).map { it.value }.filter { it.length > 2 && it !in ignored }.toSet()

    /** The sentences in [texts] joined into passages of about [size] characters, each with the index of its first sentence. */
    fun split(texts: List<String>, size: Int): List<Pair<Int, String>> {
        val passages = ArrayList<Pair<Int, String>>()
        var from = 0
        val open = StringBuilder()
        for ((i, text) in texts.withIndex()) {
            if (open.isNotEmpty() && open.length + text.length > size) {
                passages += from to open.toString()
                open.clear()
            }
            if (open.isEmpty()) from = i
            if (open.isNotEmpty()) open.append(' ')
            open.append(text)
        }
        if (open.isNotEmpty()) passages += from to open.toString()
        return passages
    }

    /**
     * The passages of [texts] that match [question] best, in reading order, in all at most [budget] characters (a passage is at most [size]).
     * When no word of the question is in the book, the passage at sentence [near], where the reader is, is the one chosen.
     */
    fun pick(texts: List<String>, question: String, budget: Int, near: Int, size: Int = 600): List<String> {
        val passages = split(texts, size)
        if (passages.isEmpty()) return emptyList()
        val words = keywords(question)
        val folded = passages.map { Search.fold(it.second) }
        val holding = words.associateWith { w -> folded.count { it.contains(w) } }
        val scores = folded.map { text -> words.sumOf { w -> if (text.contains(w)) ln(1.0 + passages.size / holding.getValue(w)) else 0.0 } }
        val nearest = passages.indexOfLast { it.first <= near }.coerceAtLeast(0)
        val ranked = passages.indices.filter { scores[it] > 0 }.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { kotlin.math.abs(it - nearest) })
            .ifEmpty { listOf(nearest) }
        var left = budget
        // A passage that does not fit is passed over, so a smaller one further down the ranking can still go in.
        val chosen = ranked.filter { i -> (passages[i].second.length <= left).also { fits -> if (fits) left -= passages[i].second.length } }
        return chosen.sorted().map { passages[it].second }
    }
}

/** The text sent to the model for one question. */
object ChatPrompt {
    /** What the passages may take of the window (about 1,250 of its 4,096 tokens), leaving room for the earlier turns, the question and the reply. */
    const val PASSAGE_CHARS = 5000

    /** The most of the earlier turns kept, and the longest earlier answer kept whole. */
    private const val EARLIER_TURNS = 2
    private const val EARLIER_ANSWER_CHARS = 400

    fun build(title: String, summary: String?, passages: List<String>, earlier: List<ChatTurn>, question: String): String {
        val rules = "You answer questions about the book \"$title\" for a listener. Use only the notes and passages the user gives. " +
            "If they do not hold the answer, say so. Answer in at most four plain sentences."
        val notes = buildString {
            if (!summary.isNullOrBlank()) append("Summary of the chapter being read:\n$summary\n\n")
            append("Passages from the book:\n")
            passages.forEach { append("- ").append(it).append('\n') }
            append("\nQuestion: ").append(question.trim())
        }
        return LocalLlm.turn("system", rules) +
            earlier.takeLast(EARLIER_TURNS).joinToString("") { LocalLlm.turn("user", it.question) + LocalLlm.turn("assistant", it.answer.take(EARLIER_ANSWER_CHARS)) } +
            LocalLlm.turn("user", notes) + LocalLlm.turn("assistant")
    }
}

/** Answers questions about a book. */
interface Chat {
    /** The answer to [prompt] (made by [ChatPrompt.build]); [onPiece] gets each streamed piece. Throws [SummaryFailure] with a message safe to show. */
    suspend fun answer(prompt: String, onPiece: (String) -> Unit): String

    /** Gives back what the chat holds, once the reader leaves it. */
    fun close() {}
}

/** Chats with [LocalModel] on the device. The model is loaded on the first question and given back at [close], once an answer under way has stopped. */
class LocalChat(private val ctx: Context) : Chat {
    private var engine: LlmInference? = null
    private val turn = Mutex()

    override suspend fun answer(prompt: String, onPiece: (String) -> Unit): String = turn.withLock {
        val llm = engine ?: LocalLlm.load(ctx).also { engine = it }
        LocalLlm.ask(llm, prompt, onPiece)
    }

    override fun close() {
        // Closing the model under a reply would crash it: wait for the reply, which stops when the reader's coroutine is cancelled.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { turn.withLock { engine?.close(); engine = null } }
    }
}
