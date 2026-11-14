/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Test
import java.io.File

/**
 * The OCR fixtures of OcrFixtureTest, run on the OCR output saved from the real scans (`ocr/<name>.ocr.json`), so the
 * cleaning is checked in the JVM without waiting for OCR. OcrFixtureTest fails when the real OCR no longer matches the saved output.
 */
class OcrFixtureDumpTest {
    private val dir = File("src/androidTest/assets/ocr")

    @Test fun originOfSpecies() = check(OcrFixtures.origin)

    @Test fun declineAndFall() = check(OcrFixtures.gibbon)


    @Test fun varietiesOfReligiousExperience() = check(OcrFixtures.james)

    @Test fun varietiesPrefaceThenContents() = check(OcrFixtures.varietiesPreface)

    @Test fun radiantEnergy() = check(OcrFixtures.bell)

    private fun check(f: OcrFixture) {
        val ocr = OcrFixtures.parseDump(File(dir, "${f.name}.ocr.json").readText())
        OcrFixtures.check(f, ocr, File(dir, "${f.name}.txt").readText())
    }
}
