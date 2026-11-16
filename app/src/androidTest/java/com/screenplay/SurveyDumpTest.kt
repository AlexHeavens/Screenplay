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
 * Writes the pre-pass OCR ([PageSurvey]) of every `*.pdf` in the app's external files `survey-in` directory as
 * `<name>.pages.json` in `survey-out`, for building fixtures (see [ChapterFixtures]). It checks nothing.
 */
@RunWith(AndroidJUnit4::class)
class SurveyDumpTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun dump() {
        val inDir = File(ctx.getExternalFilesDir(null), "survey-in")
        val outDir = File(ctx.getExternalFilesDir(null), "survey-out").apply { mkdirs() }
        for (pdf in inDir.listFiles { f -> f.extension == "pdf" }.orEmpty()) {
            val pages = runBlocking { PageSurvey.read(pdf, count = 200) }
            File(outDir, pdf.nameWithoutExtension + ".pages.json").writeText(ChapterFixtures.dumpPages(pages))
        }
    }
}
