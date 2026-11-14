/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reads real scans (public-domain, except one page kept for a private regression) through the app's OCR and cleaning, and compares the result with a proofread
 * transcription of the body text (from Wikisource), without running speech. Each fixture holds the hard parts of a
 * real book: running heads, page numbers, margin sidenotes, footnotes and their markers, a small-capitals chapter opening
 * and an index.
 *
 * The fixtures and checks are in [OcrFixtures]. OcrFixtureDumpTest runs the same checks on the OCR output saved in
 * `ocr/<name>.ocr.json`, in the JVM and in seconds; this test keeps that saved output honest by failing when the real OCR
 * differs from it, for the fixtures that run by default (see [check]). After an intended OCR change, copy the fresh dumps this test writes (see [DUMP_DIR]) over the assets.
 *
 * Sources: grayscale 1280 px page scans from Wikimedia Commons, "Origin of Species 1859 facsimile.djvu" pages
 * 17, 18, 502–504, "Decline and Fall of the Roman Empire vol 1 (1897).djvu" pages 102–103 and "The varieties of
 * religious experience, a study in human nature.djvu" (James, 1902) pages 433 and 436. The expected text is
 * the validated Wikisource transcription of the same pages, with running heads, sidenotes, footnotes and the index removed.
 * The Varieties preface fixture is "The varieties of religious experience, a study in
 * human nature.djvu" (James, 1902) pages 9–11 and 17 from Wikimedia Commons, with its Wikisource transcription. Page 11 is the
 * contents page and page 17 opens Lecture I, so the chapter heading there repeats a contents entry.
 */
@RunWith(AndroidJUnit4::class)
class OcrFixtureTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Smoke @Test fun originOfSpecies() = check(OcrFixtures.origin, everyFixture = true)

    @Smoke @Test fun declineAndFall() = check(OcrFixtures.gibbon)


    @Smoke @Test fun varietiesOfReligiousExperience() = check(OcrFixtures.james, everyFixture = true)

    @Smoke @Test fun varietiesPrefaceThenContents() = check(OcrFixtures.varietiesPreface, everyFixture = true)



    private fun check(f: OcrFixture, everyFixture: Boolean = false) {
        // Real OCR costs about ten seconds a page on the emulator and two cores. The two cheapest fixtures, a 200 ppi CCITT scan and a
        // 150 ppi JPEG scan, keep the saved dumps honest on every run; the rest run with `-e ocrAll true`, which is how to refresh every dump.
        assumeTrue("run with -e ocrAll true", !everyFixture || InstrumentationRegistry.getArguments().getString("ocrAll") == "true")
        val pdf = File(ctx.cacheDir, "${f.name}.pdf")
        assets.open("ocr/${f.name}.pdf").use { i -> pdf.outputStream().use { i.copyTo(it) } }
        val expected = assets.open("ocr/${f.name}.txt").bufferedReader().readText()

        val ocr = runBlocking { PdfText.ocr(pdf) { _, _ -> } }
        val fresh = File(ctx.getExternalFilesDir(null), DUMP_DIR).apply { mkdirs() }.resolve("${f.name}.ocr.json")
        fresh.writeText(OcrFixtures.dumpJson(ocr))

        val saved = OcrFixtures.parseDump(assets.open("ocr/${f.name}.ocr.json").bufferedReader().readText())
        val drift = OcrFixtures.wordErrorRate(OcrFixtures.words(saved.joinToString("\n")), OcrFixtures.words(ocr.joinToString("\n")))
        assertTrue(
            "${f.name}: real OCR differs from the saved dump by WER %.4f (limit %.4f), so the JVM test reads stale text. ".format(drift, MAX_DRIFT) +
                "Copy ${fresh.absolutePath} to app/src/androidTest/assets/ocr/${f.name}.ocr.json",
            drift <= MAX_DRIFT,
        )

        Log.i("OcrFixtureTest", "${f.name}: OCR drift %.4f".format(drift))
        Log.i("OcrFixtureTest", "${f.name}\n" + OcrFixtures.check(f, ocr, expected))
    }

    private companion object {
        /** Sub-directory of the app's external files directory that receives the fresh dumps. */
        const val DUMP_DIR = "ocr-dumps"

        /** How far the real OCR text may differ from the saved dump, as a word error rate, before the dump counts as stale. */
        const val MAX_DRIFT = 0.005
    }
}
