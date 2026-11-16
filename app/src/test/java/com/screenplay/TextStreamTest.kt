/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextStreamTest {
    private val prose = "The ministry set out to shape the economy through guidance rather than command, and it did so for decades afterwards."
    private val longProse = "$prose Nobody in the cabinet doubted the plan, though several officials questioned whether the banks would follow it."

    internal fun stream(pages: List<List<String>>, options: Options): List<String> {
        val s = SentenceStream(options)
        return pages.flatMap { s.add(it) } + s.finish()
    }

    internal fun batch(pages: List<List<String>>, options: Options) =
        Text.prepare(Text.mergeAcrossPages(Text.clean(pages.flatten())), options)

    private val allOptions = listOf(true, false).flatMap { front ->
        listOf(true, false).flatMap { clean -> listOf(true, false).map { merge -> Options(trimFront = front, cleanText = clean, mergeFragments = merge) } }
    }

    internal fun piece(r: Random, n: Int): String = when (r.nextInt(24)) {
        0 -> "Preface"
        1 -> "Chapter ${1 + r.nextInt(3)}"
        2 -> "Notes"
        3 -> "Index"
        4 -> "Contents"
        5 -> "Introduction"
        6 -> "Copyright 1982 by the Author. All rights reserved."
        7 -> "Ministry, 1925-1973, ${r.nextInt(400)}."
        8 -> "${r.nextInt(300)}"
        9 -> "B:"
        10 -> "Y COMMON sense held that the plan would work, and it did."
        11 -> "and so it continued across the page"
        12 -> "an inter-"
        13 -> "A Coastal Model?"
        14 -> "Short title"
        15 -> "It was said of the plan that it worked well.${r.nextInt(30)} The rest followed."
        16 -> "Appendix A"
        17, 18, 19 -> "$longProse (item $n)."
        else -> "$prose (item $n)."
    }

    @Test
    fun streamingPagesGivesTheSentencesOfTheWholeBook() {
        var checked = 0
        for (seed in 0 until 1500) {
            val r = Random(seed)
            val pieces = List(1 + r.nextInt(130)) { piece(r, it) }
            val pages = ArrayList<List<String>>()
            var at = 0
            while (at < pieces.size) {
                val n = 1 + r.nextInt(8)
                pages += pieces.subList(at, minOf(pieces.size, at + n))
                at += n
            }
            for (o in allOptions) {
                assertEquals("seed $seed, $o", batch(pages, o), stream(pages, o))
                checked++
            }
        }
        assertEquals(1500 * 8, checked)
    }

    @Test
    fun theContentsListIsTheOneTheWholeBookGives() {
        for (seed in 0 until 300) {
            val r = Random(seed)
            val pieces = List(5 + r.nextInt(80)) { if (r.nextInt(12) == 0) "Contents" else if (r.nextBoolean()) "Chapter title number ${r.nextInt(50)}" else piece(r, it) }
            val s = SentenceStream(Options())
            pieces.chunked(1 + r.nextInt(6)).forEach { s.add(it) }
            s.finish()
            assertEquals("seed $seed", Chapters.contents(Text.mergeAcrossPages(Text.clean(pieces))).first, s.contents)
        }
    }

    @Test
    fun speechCanStartOnPageOneOnceThePrefaceIsFollowedByProse() {
        val s = SentenceStream(Options())
        val first = s.add(listOf("Title page", "Preface", prose, longProse, prose, longProse, longProse))
        assertTrue("sentences of page 1 wait for later pages: $first", first.isNotEmpty())
        assertEquals("Preface", first.first())
    }

    @Test
    fun aLateHeadingWaitsUntilTheBookIsLongEnoughToCountIt() {
        // The heading is paragraph 30: inside the first quarter only if the book has more than 120 paragraphs.
        val front = List(30) { "Imprint line $it" } + "Preface" + List(3) { longProse }
        val s = SentenceStream(Options())
        assertEquals(emptyList<String>(), s.add(front))
        val more = List(100) { "$prose (item $it)." }
        assertEquals(emptyList<String>(), s.add(more.take(80)))
        assertTrue(s.add(more.drop(80)).isNotEmpty())
    }

    @Test
    fun backMatterIsNeverSpokenButTheTextBeforeItIsReleasedEarly() {
        val body = List(60) { "$prose (item $it)." }
        val s = SentenceStream(Options(trimFront = false))
        val out = ArrayList<String>()
        out += s.add(body)
        out += s.add(listOf("Notes", "Footnote text goes here and says little. $prose"))
        assertTrue("the body was held back", out.size >= 50)
        out += s.finish()
        assertEquals(batch(listOf(body, listOf("Notes", "Footnote text goes here and says little. $prose")), Options(trimFront = false)), out)
        assertTrue(out.none { it.startsWith("Footnote") })
    }
}
