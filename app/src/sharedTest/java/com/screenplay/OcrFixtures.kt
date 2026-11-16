/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The OCR fixtures and their checks, shared by the device test that runs real OCR (OcrFixtureTest) and the JVM test
 * that reads the saved OCR output (OcrFixtureDumpTest). Both compile this file.
 *
 * The word error rate limits are ratchets: lower one when a change makes the reading better.
 */
class OcrFixture(
    val name: String,
    val maxWer: Double,
    val mustContain: List<String>,
    val mustNotContain: List<String>,
    val chapters: List<String>? = null,
)

object OcrFixtures {
    /** Origin of Species (1859): two chapter-opening pages, the last page of the conclusion, and two index pages. */
    val origin = OcrFixture(
        // Not yet: OCR returns one block out of reading order, so "ment or modification." is read a paragraph early.
        "origin", maxWer = 0.03,
        // A chapter opens in small capitals, which OCR reads as "WHEN We", so phrases start after them.
        mustContain = listOf("look to the individuals of the same variety", "There is grandeur in this view of life"),
        mustNotContain = listOf("CHAP,", "CONCLUSION.", "VARIATION UNDER DOMESTIOATION", "INDEX", "Abyssinia, plants of"),
    )

    /** Gibbon, Decline and Fall, vol. 1 (1897): a chapter opening with margin sidenotes and footnotes. */
    val gibbon = OcrFixture(
        "gibbon", maxWer = 0.02,
        mustContain = listOf("is not alone by the rapidity or extent of conquest", "The elegant mythology of Homer"),
        mustNotContain = listOf(
            "Principles of government", "Universal spirit of toleration", "Of the people", "THE DECLINE AND FALL",
            "OF THE ROMAN EMPIRE", "between Lahor and Dehli", "lively a manner as", "de Guignes",
        ),
    )



    /** James, Varieties of Religious Experience (1902): footnote numbers set right after closing quotation marks. */
    val james = OcrFixture(
        // Not yet: OCR reads the opening quotes after "Self." 1 as "cć", so that marker stays.
        "james", maxWer = 0.092,
        mustContain = listOf("subject to death.\" In the vision of God", "to express it by.\" Or as when Angelus Silesius"),
        mustNotContain = listOf("itself.\"\" 8", "by.\" 4", "death.\" 2", "centre.\" 3", "found,\" 4"),
    )

    /** The Varieties of Religious Experience (1902): a preface, then a contents page whose entries end in page numbers. */
    val varietiesPreface = OcrFixture(
        "varieties-preface", maxWer = 0.07,
        mustContain = listOf("Lecturer on Natural Religion", "with no small amount of trepidation"),
        mustNotContain = listOf("CONTENTS", "neurally conditioned", "personal branch", "LECTURE III", "Percepts versus abstract concepts"),
        // The contents page prints "LECTURE I" too, so the opening's repeat of it and its title must still reach the chapter list.
        chapters = listOf("Preface", "Lecture I Religion and Neurology"),
    )


    /**
     * Popular Science Monthly vol. 19 (1881): the end of one article, then Bell's "Production of Sound by Radiant Energy",
     * which opens with a two-line drop cap and sets the footnote markers *, † and ‡ inside the body text.
     */
    val bell = OcrFixture(
        // Not yet: the 276-word footnote on the first page and "* Continued from page 197." are read aloud; OCR orders the
        // drop cap "A" before the byline, so it is dropped ("T the time"); OCR reads † as "+" and ‡ as "t", so those
        // markers stay; and OCR loses the last four lines of the third page.
        "bell", maxWer = 0.391,
        mustContain = listOf("PRODUCTION OF SOUND BY RADIANT", "the loudest effects obtained were produced", "(even of the same stick) yielded the same results"),
        mustNotContain = listOf(
            "EERGY.*", "Proceedings of", "Annales de Chimie", "THE POPULAR SCIENCE MONTHLY", "EUROPEAN SCHOOLS OF FORESTRY", "FIG. 7", "FIG. 8",
        ),
    )

    /** What the app speaks for these OCR paragraphs: the path of [PdfText.read] followed by the import's cleaning. */
    fun spoken(ocr: List<String>): List<String> = Text.prepare(Text.fromOcr(ocr), Options(spell = false))

    fun check(f: OcrFixture, ocr: List<String>, expected: String): String {
        val spokenLines = spoken(ocr)
        val spoken = spokenLines.joinToString("\n")
        val wer = wordErrorRate(words(expected), words(spoken))
        val flat = spoken.replace(Regex("\\s+"), " ")
        assertTrue("${f.name}: WER %.3f is above %.3f".format(wer, f.maxWer), wer <= f.maxWer)
        assertTrue("${f.name}: a spoken line without a letter: ${spokenLines.filter { l -> l.none { it.isLetter() } }}", spokenLines.all { l -> l.any { it.isLetter() } })
        f.mustContain.forEach { assertTrue("${f.name}: missing \"$it\"", flat.contains(it)) }
        f.mustNotContain.forEach { assertTrue("${f.name}: should not read \"$it\"", !flat.contains(it)) }
        if (f.chapters != null) {
            val found = Chapters.find(spokenLines.mapIndexed { i, s -> Segment(i * 10L, i * 10L + 9, s) }).map { it.title }
            assertEquals("${f.name}: chapters", f.chapters, found)
        }
        return spoken
    }

    /** The saved OCR paragraphs, one JSON string array per fixture (`ocr/<name>.ocr.json`). */
    fun parseDump(json: String): List<String> = JSONArray(json).let { a -> List(a.length()) { a.getString(it) } }

    fun dumpJson(ocr: List<String>): String = JSONArray(ocr).toString(1)

    /** Lowercase words without punctuation, so the rate counts misread words, not quote styles or dashes. */
    fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }

    /** Word-level edit distance divided by the expected word count. */
    fun wordErrorRate(expected: List<String>, actual: List<String>): Double {
        var prev = IntArray(actual.size + 1) { it }
        for (i in 1..expected.size) {
            val cur = IntArray(actual.size + 1)
            cur[0] = i
            for (j in 1..actual.size) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (expected[i - 1] == actual[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[actual.size].toDouble() / expected.size
    }
}
