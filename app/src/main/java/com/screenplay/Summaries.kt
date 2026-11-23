/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** What the summariser makes of one chapter: a short overview, and the lessons worth taking from it. */
data class ChapterDigest(val overview: String, val lessons: List<String>) {
    companion object {
        private val bullet = charArrayOf('-', '*', '•')

        /** The bullet lines, without their mark, are the lessons; any other non-empty lines are the overview. */
        fun parse(reply: String): ChapterDigest {
            val lines = reply.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val (marked, plain) = lines.partition { it.first() in bullet }
            return ChapterDigest(
                plain.joinToString(" ") { it.removePrefix("SUMMARY:").trim() }.trim(),
                marked.map { it.trimStart(*bullet).trim() }.filter { it.isNotEmpty() },
            )
        }
    }
}

/** Turns the text of one chapter into a short overview and a few bullet points of its key lessons. */
interface Summariser {
    /**
     * Throws [SummaryFailure] when the summary cannot be made, with a message safe to show.
     * [onStep] is told how many of the [total] model calls the chapter needs are done, from 0 to the total.
     */
    suspend fun summarise(chapterTitle: String, text: String, onStep: (done: Int, total: Int) -> Unit = { _, _ -> }): ChapterDigest

    /** Gives back what the summariser holds, once a book's summaries are done. */
    fun close() {}
}

class SummaryFailure(message: String) : Exception(message)

/**
 * A chapter's summary, anchored to the sentence where the chapter starts. [overview] is empty for a summary made before
 * overviews existed. [audio] is whether its clip has been made; it is read from disk, not saved with the text.
 */
data class ChapterSummary(val index: Int, val title: String, val bullets: List<String>, val overview: String = "", val audio: Boolean = false)

/** What the book's summaries file holds: the summaries made so far and, if the last attempt failed, why. */
data class SummaryState(val summaries: List<ChapterSummary> = emptyList(), val failure: String? = null) {
    fun at(index: Int) = summaries.firstOrNull { it.index == index }
}

/** The chapter being summarised and how far its model calls have got: the measure behind the progress bar. */
data class SummaryWork(val title: String, val done: Int, val total: Int)

object Summaries {
    /** What each book's summariser is working on now; a book with no entry is not summarising. */
    val working = kotlinx.coroutines.flow.MutableStateFlow<Map<String, SummaryWork>>(emptyMap())

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Tries again for the chapters still without a summary, from the reader's retry button. */
    fun retry(ctx: Context, id: String) {
        // A book still being read has no final chapters yet.
        val reading = Library.books.value.firstOrNull { it.id == id }?.status == Status.READING
        scope.launch { run(ctx, id, SummarySettings.load(ctx).audio, textWhole = !reading) }
    }

    fun load(ctx: Context, id: String): SummaryState {
        val f = File(Library.dir(ctx, id), Files.SUMMARIES)
        if (!f.exists()) return SummaryState()
        return try {
            val j = JSONObject(f.readText())
            val a = j.getJSONArray("summaries")
            val dir = Library.dir(ctx, id)
            SummaryState(
                List(a.length()) {
                    val o = a.getJSONObject(it)
                    val b = o.getJSONArray("bullets")
                    val index = o.getInt("index")
                    ChapterSummary(
                        index, o.getString("title"), List(b.length()) { i -> b.getString(i) }, o.optString("overview"),
                        File(dir, Files.summaryAudio(index)).exists(),
                    )
                },
                if (j.has("failure")) j.getString("failure") else null,
            )
        } catch (e: org.json.JSONException) {
            SummaryState()
        }
    }

    private fun save(ctx: Context, id: String, s: SummaryState) {
        val a = JSONArray()
        s.summaries.forEach {
            a.put(JSONObject().put("index", it.index).put("title", it.title).put("bullets", JSONArray(it.bullets)).put("overview", it.overview))
        }
        val j = JSONObject().put("summaries", a)
        s.failure?.let { j.put("failure", it) }
        File(Library.dir(ctx, id), Files.SUMMARIES).writeText(j.toString())
    }

    /** What the clip says: the overview, then the lessons. Long overviews are split so no one utterance passes the engine's limit. */
    private fun script(s: ChapterSummary): List<String> =
        (if (s.overview.isBlank()) emptyList() else Text.sentences(listOf(s.overview))) + (if (s.bullets.isEmpty()) emptyList() else listOf("Key lessons.") + s.bullets)

    /** Speaks a chapter's summary in the voice chosen in the options, into its own clip. A clip that exists is kept. */
    private suspend fun speak(ctx: Context, id: String, s: ChapterSummary) {
        val dir = Library.dir(ctx, id)
        val out = File(dir, Files.summaryAudio(s.index))
        if (out.exists()) return
        val pcm = File(dir, "summary.pcm")
        val synths = Engines.lanes(ctx, Options.load(ctx).voice)
        try {
            val (_, rate) = Speech.render(synths, script(s), File(dir, "summary-sentence.wav"), pcm) {}
            AacEncoder.encode(pcm, rate, out)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            out.delete()
            throw e
        } catch (e: Exception) {
            out.delete()
            throw SummaryFailure("could not speak the summary")
        } finally {
            synths.forEach { it.close() }
            pcm.delete()
        }
    }

    /**
     * Summarises each chapter that has no summary yet, and with [audio] speaks each one that has no clip, saving after
     * each chapter so a failure part way keeps what exists.
     * While the book's text is still being read ([textWhole] false), only the chapters followed by a heading already read are whole, so only those are done.
     * Once it is whole, the chapters are the final ones: a summary of a heading that turned out not to be one is dropped.
     * [chapterWhole] is told where each chapter ends, and returns before the chapter is summarised, so a caller can have the chapter spoken first and take the chapters one at a time.
     * One run per book at a time: a second call while one is going does nothing.
     * Never throws: a failure is stored as the state's [SummaryState.failure] for the reader to show with a retry.
     */
    suspend fun run(ctx: Context, id: String, audio: Boolean, textWhole: Boolean = true, chapterWhole: suspend (end: Int) -> Unit = {}) {
        if (!running.add(id)) return
        try { make(ctx, id, audio, textWhole, chapterWhole) } finally { running.remove(id) }
    }

    private suspend fun make(ctx: Context, id: String, audio: Boolean, textWhole: Boolean, chapterWhole: suspend (end: Int) -> Unit) {
        var state = load(ctx, id)
        val segments = Library.bookSegments(ctx, id)
        val chapters = if (textWhole) Library.chapters(ctx, id, segments) else Library.chaptersSoFar(ctx, id, segments)
        if (textWhole) {
            val stale = state.summaries.filter { s -> chapters.none { it.index == s.index } }
            if (stale.isNotEmpty()) {
                stale.forEach { File(Library.dir(ctx, id), Files.summaryAudio(it.index)).delete() }
                state = state.copy(summaries = state.summaries - stale.toSet())
                save(ctx, id, state)
            }
        }
        val summariser = Engines.summariser(ctx)
        try {
            // A chapter still being read has no heading after it yet, so its end is not known.
            for ((n, c) in chapters.withIndex().take(if (textWhole) chapters.size else (chapters.size - 1).coerceAtLeast(0))) {
                val end = chapters.getOrNull(n + 1)?.index ?: segments.size
                chapterWhole(end)
                var summary = state.at(c.index)
                if (summary == null) {
                    val text = segments.subList(c.index, end).joinToString(" ") { it.text }
                    val digest = summariser.summarise(c.title, text) { done, total -> working.update { it + (id to SummaryWork(c.title, done, total)) } }
                    if (digest.lessons.isEmpty() && digest.overview.isBlank()) throw SummaryFailure("empty reply")
                    summary = ChapterSummary(c.index, c.title, digest.lessons, digest.overview)
                    state = state.copy(summaries = state.summaries + summary, failure = null)
                    save(ctx, id, state)
                }
                if (audio) speak(ctx, id, summary)
            }
            if (state.failure != null) save(ctx, id, state.copy(failure = null))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            save(ctx, id, state.copy(failure = (e as? SummaryFailure)?.message ?: "failed"))
        } finally {
            working.update { it - id }
            summariser.close()
        }
    }
}
