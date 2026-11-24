/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Loads [LocalModel] and runs one prompt at a time on it, for the summaries and the chat about a book. */
object LocalLlm {
    /** The model's window, in tokens, shared by the prompt and the reply. */
    const val WINDOW = 4096

    /** The model, loaded; the caller closes it. Throws [SummaryFailure] with a message safe to show. */
    suspend fun load(ctx: Context): LlmInference {
        try {
            Engines.prepareSummaryModel(ctx) {}
        } catch (e: java.io.IOException) {
            throw SummaryFailure("could not download the summary model")
        }
        return try {
            NeuralVoice.inBackground {
                LlmInference.createFromOptions(ctx, LlmInference.LlmInferenceOptions.builder().setModelPath(LocalModel.file(ctx).path).setMaxTokens(WINDOW).build())
            }
        } catch (e: Exception) {
            throw SummaryFailure("could not load the summary model")
        }
    }

    /** One turn of a prompt, in the chat format the model was trained on. An [assistant] turn with no [text] is left open for the model to write. */
    fun turn(role: String, text: String? = null) = "<|im_start|>$role\n" + (text?.let { "$it<|im_end|>\n" } ?: "")

    /** The reply to [prompt] (turns made with [turn], ending in an open assistant turn). [onPiece] gets each streamed piece of it. */
    suspend fun ask(llm: LlmInference, prompt: String, onPiece: (String) -> Unit): String {
        val session = LlmInferenceSession.createFromOptions(llm, LlmInferenceSession.LlmInferenceSessionOptions.builder().build())
        val reply = suspendCancellableCoroutine { waiting ->
            val generating = NeuralVoice.inBackground {
                session.addQueryChunk(prompt)
                session.generateResponseAsync { piece, _ -> onPiece(piece) }
            }
            // A reply is minutes of work that nothing else can stop: when the book is deleted, the model is told to give up, so the next book is not kept waiting.
            waiting.invokeOnCancellation { session.cancelGenerateResponseAsync() }
            generating.addListener({
                // The session is closed only once the model has finished with it, however it ended.
                val result = runCatching { generating.get() }
                session.close()
                result.onSuccess { waiting.resume(it) }.onFailure { waiting.resumeWithException(it) }
            }, Runnable::run)
        }
        // The model sometimes writes a line break as the two characters backslash and n, so those are turned into line breaks.
        return reply.replace("\\n", "\n")
    }
}
