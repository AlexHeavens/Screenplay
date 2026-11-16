/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The chapter pre-pass on the OCR saved from real scans (`ocr/<name>.pages.json`), so contents layouts are checked in the
 * JVM without waiting for OCR. ChapterFixtureTest fails when the real OCR no longer matches the saved output.
 */
class ChapterFixtureDumpTest {
    private val dir = File("src/androidTest/assets/ocr")

    private fun pages(f: ChapterFixture) = ChapterFixtures.parsePages(File(dir, "${f.name}.pages.json").readText())


    @Test fun originContentsWithDescriptionsAndPageRanges() = ChapterFixtures.check(ChapterFixtures.origin, pages(ChapterFixtures.origin))

    @Test fun varietiesContentsWithSeveralLecturesUnderOneHeading() = ChapterFixtures.check(ChapterFixtures.varieties, pages(ChapterFixtures.varieties))

    @Test fun anAnalyticContentsIsLeftToTheTextDetection() = ChapterFixtures.check(ChapterFixtures.gibbon, pages(ChapterFixtures.gibbon))

    @Test fun aBookWithoutAContentsPageHasNoPlan() {
        // The same book with its contents pages cut out: nothing to estimate from, so the text detection stays in charge.
        val body = pages(ChapterFixtures.origin).filter { it.page > 9 }
        assertEquals(ChapterPlan.NONE, ChapterEstimator.plan(body))
    }
}
