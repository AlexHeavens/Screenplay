/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptReadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun json(segments: List<Segment>) = JSONArray().also { a ->
        segments.forEach { a.put(JSONObject().put("start", it.start).put("end", it.end).put("text", it.text)) }
    }.toString()

    private val two = listOf(Segment(0, 1020, "PREFACE"), Segment(1020, 9508, "PERHAPS the oldest subject"))

    @Test
    fun readsAWholeTranscript() {
        val f = tmp.newFile("transcript.json").also { it.writeText(json(two)) }
        assertEquals(two, Library.readSegments(f))
    }

    @Test
    fun missingFileIsEmpty() {
        assertEquals(emptyList<Segment>(), Library.readSegments(File(tmp.root, "transcript.json")))
    }

    // The phone crashed with "Unterminated string at character N" on a transcript cut short mid-write.
    @Test
    fun aTruncatedTranscriptIsUnreadableNotACrash() {
        val whole = json(two)
        for (cut in listOf(1, 30, whole.length - 10, whole.length - 1)) {
            val f = tmp.newFile().also { it.writeText(whole.substring(0, cut)) }
            assertNull("cut at $cut", Library.readSegments(f))
        }
    }

    @Test
    fun aReaderNeverSeesAHalfWrittenTranscript() {
        val f = File(tmp.root, "transcript.json").also { Library.writeAtomically(it, json(two)) }
        val grown = List(2000) { Segment(it * 10L, it * 10L + 9, "sentence number $it of the book") }
        val writer = Thread { for (n in 1..200) Library.writeAtomically(f, json(grown.take(n * 10))) }
        writer.start()
        while (writer.isAlive) assertEquals(true, Library.readSegments(f) != null)
        writer.join()
        assertEquals(grown, Library.readSegments(f))
        assertEquals(listOf("transcript.json"), tmp.root.list()!!.toList())
    }
}
