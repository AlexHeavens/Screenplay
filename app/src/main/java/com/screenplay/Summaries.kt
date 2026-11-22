/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What the summariser makes of one chapter: a short overview, and the lessons worth taking from it. */
data class ChapterDigest(val overview: String, val lessons: List<String>)

/** Turns the text of one chapter into a short overview and a few bullet points of its key lessons. */
interface Summariser {
    /** Throws [SummaryFailure] when the summary cannot be made, with a message safe to show. */
    suspend fun summarise(chapterTitle: String, text: String): ChapterDigest
}

class SummaryFailure(message: String) : Exception(message)

/** What asking Anthropic about a key found: [UNREACHABLE] (no network, or the service is down) says nothing about the key. */
enum class KeyStatus { VALID, REJECTED, UNREACHABLE }

/**
 * Calls the Claude API. Haiku rather than Sonnet: a short overview and a few bullet points over one chapter is an easy task, a book has
 * many chapters of thousands of words each, and the user pays for the key.
 */
class ClaudeSummariser(private val ctx: Context) : Summariser {
    override suspend fun summarise(chapterTitle: String, text: String): ChapterDigest = withContext(Dispatchers.IO) {
        val key = SummarySettings.load(ctx).apiKey
        if (key.isEmpty()) throw SummaryFailure("no API key")
        val body = JSONObject().put("model", MODEL).put("max_tokens", 600).put(
            "messages",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "content",
                    "Here is the text of the chapter \"$chapterTitle\" from a book. Reply in this form and nothing else: " +
                        "one line starting with \"SUMMARY: \" that sums up the chapter in 2 or 3 plain sentences for a listener, then " +
                        "its 3 to 5 most important lessons as short bullet points, one per line, each starting with \"- \".\n\n${text.take(MAX_CHARS)}",
                ),
            ),
        )
        val conn = try {
            (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("x-api-key", key)
                setRequestProperty("anthropic-version", "2023-06-01")
                setRequestProperty("content-type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        } catch (e: java.io.IOException) {
            throw SummaryFailure("offline")
        }
        try {
            val code = conn.responseCode
            if (code == 401) SummarySettings.markChecked(ctx, false)
            if (code != 200) throw SummaryFailure(if (code == 429) "rate limited" else if (code == 401) "API key rejected" else "API error $code")
            val reply = JSONObject(conn.inputStream.bufferedReader().readText())
            parse(reply.getJSONArray("content").getJSONObject(0).getString("text"))
        } catch (e: java.io.IOException) {
            throw SummaryFailure("offline")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MODEL = "claude-haiku-5-5"
        private const val MODELS = "https://api.anthropic.com/v1/models?limit=1"

        /** Asks for the list of models, which costs nothing and needs only a working key. */
        suspend fun check(key: String): KeyStatus = withContext(Dispatchers.IO) {
            try {
                val conn = URL(MODELS).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("x-api-key", key.trim())
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                    when (conn.responseCode) {
                        200 -> KeyStatus.VALID
                        401, 403 -> KeyStatus.REJECTED
                        else -> KeyStatus.UNREACHABLE
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: java.io.IOException) {
                KeyStatus.UNREACHABLE
            }
        }

        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val MAX_CHARS = 200_000

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

/**
 * A chapter's summary, anchored to the sentence where the chapter starts. [overview] is empty for a summary made before
 * overviews existed. [audio] is whether its clip has been made; it is read from disk, not saved with the text.
 */
data class ChapterSummary(val index: Int, val title: String, val bullets: List<String>, val overview: String = "", val audio: Boolean = false)

/** What the book's summaries file holds: the summaries made so far and, if the last attempt failed, why. */
data class SummaryState(val summaries: List<ChapterSummary> = emptyList(), val failure: String? = null) {
    fun at(index: Int) = summaries.firstOrNull { it.index == index }
}

object Summaries {
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
        (if (s.overview.isBlank()) emptyList() else Text.sentences(listOf(s.overview))) + "Key lessons." + s.bullets

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
     * One run per book at a time: a second call while one is going does nothing.
     * Never throws: a failure is stored as the state's [SummaryState.failure] for the reader to show with a retry.
     */
    suspend fun run(ctx: Context, id: String, audio: Boolean, textWhole: Boolean = true) {
        if (!running.add(id)) return
        try { make(ctx, id, audio, textWhole) } finally { running.remove(id) }
    }

    private suspend fun make(ctx: Context, id: String, audio: Boolean, textWhole: Boolean) {
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
                var summary = state.at(c.index)
                if (summary == null) {
                    val end = chapters.getOrNull(n + 1)?.index ?: segments.size
                    val text = segments.subList(c.index, end).joinToString(" ") { it.text }
                    val digest = summariser.summarise(c.title, text)
                    if (digest.lessons.isEmpty()) throw SummaryFailure("empty reply")
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
        }
    }
}
