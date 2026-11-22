/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Summarises on the device with [LocalModel]. A chapter longer than the model's window is read in parts: each part is boiled down to
 * a few notes, and the summary is written from the notes. The model is loaded on first use and held until [close], so a book's
 * chapters share one load and the memory is given back when the book's summaries are done.
 */
class LocalSummariser(private val ctx: Context) : Summariser {
    private var engine: LlmInference? = null

    override suspend fun summarise(chapterTitle: String, text: String): ChapterDigest = withContext(Dispatchers.Default) {
        try {
            Engines.prepareSummaryModel(ctx) {}
        } catch (e: java.io.IOException) {
            throw SummaryFailure("could not download the summary model")
        }
        val llm = engine ?: try {
            NeuralVoice.inBackground {
                LlmInference.createFromOptions(
                    ctx, LlmInference.LlmInferenceOptions.builder().setModelPath(LocalModel.file(ctx).path).setMaxTokens(WINDOW).build(),
                )
            }
        } catch (e: Exception) {
            throw SummaryFailure("could not load the summary model")
        }.also { engine = it }
        val parts = chunks(text)
        val source = if (parts.size == 1) parts.single() else parts.joinToString("\n\n") { part ->
            ask(llm, "Here is part of the chapter \"$chapterTitle\" from a book. Write notes on it in at most 60 words, plain text.\n\n$part")
        }
        ChapterDigest.parse(
            ask(
                llm,
                "Here is the text of the chapter \"$chapterTitle\" from a book. Reply in this form and nothing else: " +
                    "one line starting with \"SUMMARY: \" that sums up the chapter in 2 or 3 plain sentences for a listener, then " +
                    "its 3 to 5 most important lessons as short bullet points, one per line, each starting with \"- \".\n\n${source.take(CHUNK_CHARS)}",
            ),
        )
    }

    private fun ask(llm: LlmInference, prompt: String): String =
        NeuralVoice.inBackground { llm.generateResponse("<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n") }

    override fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        /** The model's window, in tokens, shared by the prompt and the reply. */
        private const val WINDOW = 4096

        /** About 1500 tokens of English, which leaves the window room for the instructions and the reply. */
        const val CHUNK_CHARS = 6_000

        /** Cuts [text] into parts of at most [max] characters, at the last sentence end or space before the limit where there is one. */
        fun chunks(text: String, max: Int = CHUNK_CHARS): List<String> {
            val out = ArrayList<String>()
            var rest = text.trim()
            while (rest.length > max) {
                val window = rest.substring(0, max)
                val cut = listOf(". ", "? ", "! ", "\n").maxOf { window.lastIndexOf(it) }.let { if (it > max / 2) it + 1 else window.lastIndexOf(' ').let { s -> if (s > 0) s else max } }
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty() || out.isEmpty()) out += rest
            return out
        }
    }
}
