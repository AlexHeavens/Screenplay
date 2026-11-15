/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place that decides which sentence of a book is spoken next. Sentences are numbered by their place in the book's text.
 * It speaks forward from the cursor (the start of the book, or where the reader last jumped to) and, once nothing is left
 * after it, wraps to the earliest sentence that has no audio. A sentence that has audio, or is being spoken now, is never
 * handed out again, so a jump and the wrap-around never speak anything twice.
 *
 * [spoken] are the sentences that have audio already (a stopped book carrying on). Text arrives with [add] while the book is
 * read; [finish] says no more will come.
 */
class SpeakOrder(spoken: Collection<Int> = emptySet(), from: Int = 0) {
    private val texts = ArrayList<String>()
    private val taken = HashSet<Int>(spoken)
    // Where the next forward search starts, and below which every sentence is taken: both only move on, so a pick does not rescan the book.
    private var forward = from
    private var lowest = 0
    private var finished = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun add(text: String) { texts += text; wake.trySend(Unit) }

    @Synchronized
    fun finish() { finished = true; wake.trySend(Unit) }

    /** Speech goes on from sentence [index]: the next sentence handed out is the first without audio at or after it. */
    @Synchronized
    fun jump(index: Int) { forward = index.coerceAtLeast(0); wake.trySend(Unit) }

    /** The next sentence to speak, or null when there is none yet. */
    @Synchronized
    fun pick(): Pair<Int, String>? {
        while (lowest in taken) lowest++
        val i = (forward until texts.size).firstOrNull { it !in taken } ?: lowest.takeIf { it < texts.size } ?: return null
        taken += i
        forward = i + 1
        return i to texts[i]
    }

    /** The next sentence to speak, waiting for text that is still being read; null once the text is finished and every sentence has been handed out. */
    suspend fun take(): Pair<Int, String>? {
        while (true) {
            synchronized(this) {
                pick()?.let { return it }
                if (finished) return null
            }
            wake.receive()
        }
    }
}

/** Jumps in books being made: where speech goes on from, kept in the book's folder so it survives the worker being stopped. */
object Speaking {
    private val running = ConcurrentHashMap<String, SpeakOrder>()

    fun register(id: String, order: SpeakOrder) { running[id] = order }
    fun unregister(id: String) { running.remove(id) }

    /** Moves speech of book [id] to sentence [index] of its text: the running worker picks it up at once, a worker that starts later reads it from the folder. */
    fun jump(ctx: Context, id: String, index: Int) {
        Library.writeAtomically(File(Library.dir(ctx, id), Files.JUMP), index.toString())
        running[id]?.jump(index)
    }

    /** Where speech of book [id] was last moved to; the start when it never was. */
    fun from(ctx: Context, id: String): Int = File(Library.dir(ctx, id), Files.JUMP).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
}

/** The book's text in reading order, as the reader shows it. */
object BookText {
    /**
     * [spoken] are the sentences with audio (in the order they were spoken), [read] every sentence read so far in text order. The result has one
     * entry per sentence of the text: its audio times where it is spoken, a [Segment.pending] entry where it is not. It stops at the first sentence
     * known to neither list (a stopped book being read again to carry on).
     */
    fun merge(spoken: List<Segment>, read: List<String>): List<Segment> {
        val byIndex = HashMap<Int, Segment>()
        spoken.forEachIndexed { i, s -> byIndex[if (s.at >= 0) s.at else i] = s }
        val size = maxOf(read.size, (byIndex.keys.maxOrNull() ?: -1) + 1)
        val out = ArrayList<Segment>(size)
        for (i in 0 until size) {
            out += byIndex[i] ?: read.getOrNull(i)?.let { Segment(0, 0, it, i, pending = true) } ?: break
        }
        return out
    }
}
