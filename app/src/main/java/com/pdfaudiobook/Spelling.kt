/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** A misspelling the checker found: where it is, and what it suggests, best first. */
data class Fix(val start: Int, val length: Int, val options: List<String>)

object Spelling {
    /**
     * Applies the first suggestion of each fix that is a plain typo repair. OCR errors are one or two characters off,
     * so a suggestion further away, or for a capitalised word or one with digits (names, codes), is left alone.
     */
    fun apply(text: String, fixes: List<Fix>): String {
        val sb = StringBuilder(text)
        for (f in fixes.sortedByDescending { it.start }) {
            if (f.start < 0 || f.start + f.length > text.length) continue
            val word = text.substring(f.start, f.start + f.length)
            val best = f.options.firstOrNull { acceptable(word, it) } ?: continue
            sb.replace(f.start, f.start + f.length, best)
        }
        return sb.toString()
    }

    fun acceptable(word: String, suggestion: String): Boolean =
        word.length > 2 && word.all { it.isLetter() } && word.first().isLowerCase() &&
            suggestion.all { it.isLetter() } && suggestion.first() == word.first() && distance(word, suggestion) <= 2

    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/** The phone's own spell checker. Without one enabled, text comes back unchanged. */
class PlatformSpeller(private val ctx: Context) {
    suspend fun correct(sentences: List<String>, onProgress: (Float) -> Unit): List<String> = withContext(Dispatchers.Main) {
        var reply = CompletableDeferred<Array<SentenceSuggestionsInfo?>>()
        val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
            override fun onGetSuggestions(results: Array<SuggestionsInfo>?) {}
            override fun onGetSentenceSuggestions(results: Array<SentenceSuggestionsInfo?>?) {
                reply.complete(results ?: emptyArray())
            }
        }
        @Suppress("DEPRECATION")
        val session = ctx.getSystemService(TextServicesManager::class.java)
            ?.newSpellCheckerSession(null, Locale.US, listener, false)
            ?: return@withContext sentences
        try {
            val out = ArrayList<String>(sentences.size)
            for (batch in sentences.chunked(BATCH)) {
                reply = CompletableDeferred()
                session.getSentenceSuggestions(Array(batch.size) { TextInfo(batch[it]) }, 5)
                val info = withTimeoutOrNull(BATCH_TIMEOUT_MS) { reply.await() }
                batch.forEachIndexed { i, text ->
                    out += if (info != null && info.size == batch.size) Spelling.apply(text, fixes(info[i])) else text
                }
                onProgress(out.size.toFloat() / sentences.size)
            }
            out
        } finally {
            session.close()
        }
    }

    /** The engine may return null for a sentence, or for one of its words; either means no fix. */
    private fun fixes(s: SentenceSuggestionsInfo?): List<Fix> = if (s == null) emptyList() else (0 until s.suggestionsCount).mapNotNull { i ->
        val info = s.getSuggestionsInfoAt(i)
        if (info == null || info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO == 0) null
        else Fix(s.getOffsetAt(i), s.getLengthAt(i), List(info.suggestionsCount) { info.getSuggestionAt(it) })
    }

    private companion object {
        const val BATCH = 50
        const val BATCH_TIMEOUT_MS = 15_000L
    }
}
