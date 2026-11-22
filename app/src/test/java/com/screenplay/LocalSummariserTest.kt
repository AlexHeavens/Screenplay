/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSummariserTest {
    @Test
    fun aChapterThatFitsIsOnePart() {
        assertEquals(listOf("A short chapter."), LocalSummariser.chunks("  A short chapter.  "))
    }

    @Test
    fun aLongChapterIsCutAtSentenceEndsAndNothingIsLost() {
        val sentence = "This is one sentence of the chapter. "
        val text = sentence.repeat(100).trim()
        val parts = LocalSummariser.chunks(text, max = 500)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 500 && it.endsWith(".") })
        assertEquals(text, parts.joinToString(" "))
    }

    @Test
    fun textWithNoSentenceEndIsCutAtASpace() {
        val parts = LocalSummariser.chunks("word ".repeat(200).trim(), max = 100)
        assertTrue(parts.all { it.length <= 100 && !it.startsWith("ord") })
        assertEquals(200, parts.joinToString(" ").split(" ").size)
    }
}
