/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The chapter pre-pass ([ChapterEstimator]) on the real OCR of a few pages of real books, from the pages in
 * `ocr/<name>.pdf`. ChapterFixtureDumpTest runs the same checks in the JVM on the OCR saved in `ocr/<name>.pages.json`;
 * this test fails when the real OCR no longer gives the estimate the fixture expects, so that saved OCR cannot go stale
 * unseen. After an intended OCR change, copy the fresh dumps this test writes (`ocr-dumps/<name>.pages.json` in the app's
 * external files) over the assets.
 *
 * The fixtures, their sources and licences are in [ChapterFixtures].
 */
@RunWith(AndroidJUnit4::class)
class ChapterFixtureTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets


    @Test fun originContents() = check(ChapterFixtures.origin)

    @Test fun varietiesContents() = check(ChapterFixtures.varieties)

    @Test fun gibbonAnalyticContents() = check(ChapterFixtures.gibbon)

    private fun check(f: ChapterFixture) {
        val pdf = File(ctx.cacheDir, "${f.name}.pdf")
        assets.open("ocr/${f.name}.pdf").use { i -> pdf.outputStream().use { i.copyTo(it) } }
        // The fixture's PDF holds only the chosen pages; the saved OCR says which page of the whole book each one is.
        val saved = ChapterFixtures.parsePages(assets.open("ocr/${f.name}.pages.json").bufferedReader().readText())
        val survey = runBlocking { PageSurvey.read(pdf, count = saved.size) }
        val pages = survey.mapIndexed { i, p -> p.copy(page = saved[i].page) }
        File(ctx.getExternalFilesDir(null), "ocr-dumps").apply { mkdirs() }.resolve("${f.name}.pages.json").writeText(ChapterFixtures.dumpPages(pages))
        ChapterFixtures.check(f, pages)
    }
}
