/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

/** One step of [SentenceStream]: takes an item, returns what it can now release, and returns the rest on [finish]. */
internal interface Stage {
    fun add(x: String): List<String>
    fun finish(): List<String>
}

/**
 * [Text.prepare] for a book whose pages arrive one at a time. Feeding every page and then calling [finish] gives
 * exactly the sentences `Text.prepare(Text.mergeAcrossPages(Text.clean(allParagraphs)), options)` gives; each call
 * returns what is already certain, so speech can start while later pages are still being read.
 *
 * What a later page can still change is held back, never guessed:
 * - a paragraph that a page break may cut is held until the next paragraph shows whether it continues;
 * - front matter ([FrontStage]) is held until the opening heading is certain to lie in the first quarter;
 * - back matter ([BackStage]) is held from a notes or index heading until the text is long enough that the heading cannot
 *   lie in the second half;
 * - a run of list lines ([ProseStage]) is held until a paragraph ends it;
 * - the last sentence is held, since the next one may join it.
 */
class SentenceStream(private val options: Options) {
    private val stages: List<Stage> = buildList {
        add(PageStage())
        if (options.trimFront) add(FrontStage())
        if (options.cleanText) {
            add(BackStage())
            add(ProseStage())
        }
        add(SentenceStage(if (options.mergeFragments) 1000 else 300))
        if (options.mergeFragments) add(FragmentStage())
    }
    private val contentsTracker = ContentsTracker()

    /** Shows the stream a contents page that is kept out of the speech, so its titles still give the book's chapters. */
    fun noteContents(paragraphs: List<String>) = paragraphs.forEach(contentsTracker::add)

    /** Chapter titles from the contents page, as far as the text so far shows them. */
    val contents: List<String> get() = contentsTracker.titles

    /** Adds the OCR blocks of the next page and returns the sentences that are now certain. */
    fun add(blocks: List<String>): List<String> = blocks.flatMap { b -> push(1, stages[0].add(b)) }

    fun finish(): List<String> = stages.indices.flatMap { k -> push(k + 1, stages[k].finish()) }

    private fun push(from: Int, items: List<String>): List<String> {
        var cur = items
        // The first stage's output is the paragraph list that the contents page is read from.
        if (from == 1) cur.forEach(contentsTracker::add)
        for (k in from until stages.size) cur = cur.flatMap { stages[k].add(it) }
        return cur
    }

    /**
     * Cleans blocks and joins a paragraph cut by a page break ([Text.clean], [Text.mergeAcrossPages]). A short block that
     * repeats an earlier one is kept when body text follows it within three blocks ([Chapters.bodyFollows]), so such a
     * block waits for those three.
     */
    private class PageStage : Stage {
        private val seen = HashSet<String>()
        private val pending = ArrayList<String>()
        private var held: String? = null

        override fun add(x: String): List<String> {
            pending += Text.trimBlock(x)
            return drain(false)
        }

        override fun finish(): List<String> = drain(true) + listOfNotNull(held).also { held = null }

        private fun drain(last: Boolean): List<String> {
            val out = ArrayList<String>()
            while (pending.isNotEmpty()) {
                val b = pending[0]
                val key = Text.repeatKey(b)
                if (!Text.isPageNumber(b) && key != null && key in seen) {
                    if (!last && pending.size < 4) break
                    if (!Chapters.bodyFollows(pending, 1)) { pending.removeAt(0); continue }
                } else if (!Text.isPageNumber(b) && key != null) seen += key
                pending.removeAt(0)
                if (!Text.isPageNumber(b)) merge(b)?.let { out += it }
            }
            return out
        }

        private fun merge(b: String): String? {
            val prev = held
            return when {
                prev == null -> { held = b; null }
                Text.continuesAcrossPage(prev, b) -> { held = Text.joinAcrossPage(prev, b); null }
                else -> { held = b; prev }
            }
        }
    }

    /**
     * [Text.trimFront]: the opening heading counts when it is in the first quarter of the paragraphs (at least 20), which
     * depends on the length of the book. A heading at index c is certain once c < 20 or c < paragraphs so far / 4, since
     * the book only grows. Until then, and with no heading at all, everything is held and [finish] decides.
     */
    private class FrontStage : Stage {
        private val buf = ArrayList<String>()
        private var scanned = 0
        private var heading = -1
        private var decided = false

        override fun add(x: String): List<String> {
            if (decided) return listOf(x)
            buf += x
            while (heading < 0 && scanned < buf.size) {
                val p = buf[scanned]
                if (p.length > 60 || !Text.opening.matches(p)) { scanned++; continue }
                // A heading needs the three paragraphs after it to tell prose from a contents page.
                if (scanned + 4 > buf.size) break
                if (buf.subList(scanned + 1, scanned + 4).any(Text::isProse)) heading = scanned else scanned++
            }
            if (heading >= 0 && (heading < 20 || heading < buf.size / 4)) {
                decided = true
                return buf.drop(heading).also { buf.clear() }
            }
            return emptyList()
        }

        override fun finish(): List<String> = if (decided) emptyList() else Text.trimFront(buf.toList()).also { buf.clear() }
    }

    /**
     * [Text.trimBack]: cuts from the first notes, index or similar heading that starts in the second half of the text
     * (by characters). A heading that starts at character c is in the second half only if the text is at most 2c long, so
     * once more than 2c characters have arrived it is passed over, and what it held back is released.
     */
    private class BackStage : Stage {
        private val held = ArrayList<String>()
        private var base = 0L
        private var heldChars = 0L

        private fun isHeading(p: String) = Text.backMatter.matches(p.trim().trimEnd('.', ':'))

        override fun add(x: String): List<String> {
            if (held.isEmpty()) {
                if (!isHeading(x)) { base += x.length; return listOf(x) }
            }
            held += x
            heldChars += x.length
            val out = ArrayList<String>()
            // The first held paragraph is a heading. It is certainly not a cut when its offset is below half of the text so far.
            while (held.isNotEmpty() && base < (base + heldChars) / 2) {
                do {
                    val p = held.removeAt(0)
                    out += p
                    base += p.length
                    heldChars -= p.length
                } while (held.isNotEmpty() && !isHeading(held[0]))
            }
            return out
        }

        override fun finish(): List<String> {
            val half = (base + heldChars) / 2
            var seen = base
            for ((i, p) in held.withIndex()) {
                if (seen >= half && isHeading(p)) return held.take(i).also { held.clear() }
                seen += p.length
            }
            return held.toList().also { held.clear() }
        }
    }

    /** [Text.dropNonProse]: holds paragraphs back to the last one that no list run can cross ([Text.endsRuns]). */
    private class ProseStage : Stage {
        private val buf = ArrayList<String>()

        override fun add(x: String): List<String> {
            Text.joinDropCap(buf, x)
            val end = buf.indexOfLast(Text::endsRuns)
            if (end < 0) return emptyList()
            val head = buf.subList(0, end + 1)
            return Text.dropRuns(head.toList()).also { head.clear() }
        }

        override fun finish() = Text.dropRuns(buf.toList()).also { buf.clear() }
    }

    /** Splits each paragraph into sentences ([Text.sentences]). */
    private class SentenceStage(private val max: Int) : Stage {
        override fun add(x: String) = Text.sentences(listOf(x), max)
        override fun finish() = emptyList<String>()
    }

    /** [Text.mergeFragments]: the last sentence is held, since the next may be the rest of it. */
    private class FragmentStage : Stage {
        private var held: String? = null

        override fun add(x: String): List<String> {
            val prev = held
            return when {
                prev == null -> { held = x; emptyList() }
                Text.joinsFragment(prev, x, 1000) -> { held = "$prev $x"; emptyList() }
                else -> { held = x; listOf(prev) }
            }
        }

        override fun finish() = listOfNotNull(held).also { held = null }
    }

    /** [Chapters.contents] needs only the first "contents" paragraph and the ones after it, so it keeps no more. */
    private class ContentsTracker {
        private val lines = ArrayList<String>()
        private var started = false
        var titles: List<String> = emptyList()
            private set

        fun add(p: String) {
            if (!started) started = Chapters.contents(listOf(p)).second >= 0
            if (!started || lines.size > LINES) return
            lines += p
            titles = Chapters.contents(lines).first
        }

        private companion object {
            /** Chapters.contents reads at most this many paragraphs after the contents line. */
            const val LINES = 40
        }
    }
}
