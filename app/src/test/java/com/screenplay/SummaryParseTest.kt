/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryParseTest {
    @Test
    fun theSummaryLineIsTheOverviewAndTheBulletsAreTheLessons() {
        val digest = ChapterDigest.parse("SUMMARY: It argues for small steps. They compound.\n- Start small\n* Repeat daily\n• Review weekly\n")
        assertEquals("It argues for small steps. They compound.", digest.overview)
        assertEquals(listOf("Start small", "Repeat daily", "Review weekly"), digest.lessons)
    }

    @Test
    fun aReplyWithoutBulletsHasNoLessons() {
        val digest = ChapterDigest.parse("SUMMARY: Only an overview.")
        assertEquals("Only an overview.", digest.overview)
        assertEquals(emptyList<String>(), digest.lessons)
    }
}

class SummaryWorkTest {
    @org.junit.Test
    fun theBarFillsWithTheCallUnderWayAndNeverPassesFull() {
        org.junit.Assert.assertEquals(0.25f, SummaryWork("Chapter", done = 1, total = 4).fraction, 0.001f)
        org.junit.Assert.assertEquals(0.375f, SummaryWork("Chapter", done = 1, total = 4, within = 0.5f).fraction, 0.001f)
        org.junit.Assert.assertEquals(1f, SummaryWork("Chapter", done = 4, total = 4, within = 0.95f).fraction, 0.001f)
    }
}
