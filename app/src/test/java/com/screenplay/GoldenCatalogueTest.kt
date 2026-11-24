/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream

/**
 * The golden catalogue files (golden/catalogue/vN/, docs/data-specs.md): real files each released format wrote, never edited. The current
 * parser must read every one and give the recorded result (`<file>.expected.json` next to it).
 */
class GoldenCatalogueTest {
    private val golden = File("../golden/catalogue")

    private fun dataFiles(root: File) = root.walkTopDown().filter { it.isFile && it.name.endsWith(".tsv.xz") }.sortedBy { it.path }.toList()

    /** What reading [data] gives: the facts the app shows and searches by, in a stable order. */
    private fun describe(data: CatalogueData): JSONObject {
        val all = data.size
        val entries = JSONArray()
        for (e in data.search(CatalogueQuery(), all).entries) {
            entries.put(JSONObject().put("id", e.id).put("title", e.title).put("author", e.author).put("languages", JSONArray(e.languages)).put("downloads", e.downloads).put("rating10", e.rating10).put("ratingCount", e.ratingCount))
        }
        val genres = JSONObject()
        for (g in Genre.values()) genres.put(g.name, JSONArray(data.search(CatalogueQuery(genre = g), all).entries.map { it.id }))
        val languages = JSONArray(data.languages.map { JSONObject().put("code", it.code).put("count", it.count) })
        return JSONObject().put("version", data.version).put("size", data.size).put("entriesByTitle", entries).put("idsByDownloads", JSONArray(data.search(CatalogueQuery(sort = Sort.DOWNLOADS), all).entries.map { it.id }))
            .put("idsByRating", JSONArray(data.search(CatalogueQuery(sort = Sort.RATING), all).entries.map { it.id })).put("idsByGenre", genres).put("languages", languages)
    }

    private fun parse(file: File) = file.inputStream().use { CatalogueData.parse(it) }

    @Test
    fun theGoldenFolderHoldsAFilePerFormatTheAppReads() {
        for (v in 1..CatalogueData.FORMAT) assertTrue("golden/catalogue/v$v has no data file", dataFiles(File(golden, "v$v")).isNotEmpty())
    }

    @Test
    fun theCurrentParserReadsEveryGoldenFileAsRecorded() {
        val files = dataFiles(golden)
        assertTrue("no golden files under ${golden.absolutePath}", files.isNotEmpty())
        for (file in files) {
            val expected = File(file.path.removeSuffix(".tsv.xz") + ".expected.json")
            assertTrue("${file.name} has no ${expected.name}", expected.exists())
            val read = describe(parse(file))
            val recorded = JSONObject(expected.readText())
            assertTrue("${file.path} reads differently from ${expected.name}:\n${read.toString(1)}", read.similar(recorded))
        }
    }

    @Test
    fun theFormatInTheFileNameFolderIsTheFormatInTheFile() {
        for (file in dataFiles(golden)) {
            val first = XZInputStream(file.inputStream()).bufferedReader().readLine()!!
            assertEquals(file.path, "catalogue-format ${file.parentFile.name.removePrefix("v")} build ${first.split(' ')[3]}", first)
        }
    }

    /** The parser as it shipped with format 2 (commit 83ba3fd), kept as it was: it needs exactly 8 columns. Apps in the field still read this way. */
    private fun legacyFormat2Ids(text: String): List<Int> {
        val lines = text.lines()
        val head = lines[0].split(' ')
        require(head.size == 4 && head[0] == "catalogue-format" && head[1].toIntOrNull() in 1..2 && head[2] == "build")
        require(lines[1].startsWith("tags"))
        return lines.drop(2).filter { it.isNotEmpty() }.map { line ->
            val f = line.split('\t')
            require(f.size == if (head[1] == "1") 6 else 8) { "unreadable row: $line" }
            f[0].toInt()
        }
    }

    @Test
    fun theOldestShippedParserReadsTheNewestGoldenFile() {
        val newest = dataFiles(File(golden, "v${CatalogueData.FORMAT}"))
        for (file in newest) {
            val text = XZInputStream(file.inputStream()).readBytes().toString(Charsets.UTF_8)
            assertEquals(file.name, parse(file).size, legacyFormat2Ids(text).size)
        }
    }

    @Test
    fun aColumnAppendedToTheNewestFormatIsSkippedByTheCurrentParser() {
        for (file in dataFiles(File(golden, "v${CatalogueData.FORMAT}"))) {
            val text = XZInputStream(file.inputStream()).readBytes().toString(Charsets.UTF_8)
            val wider = text.lines().mapIndexed { i, l -> if (i >= 2 && l.isNotEmpty()) l + "\tfuture" else l }.joinToString("\n")
            val out = ByteArrayOutputStream().also { o -> XZOutputStream(o, LZMA2Options()).use { it.write(wider.toByteArray()) } }.toByteArray()
            assertTrue(describe(CatalogueData.parse(ByteArrayInputStream(out))).similar(describe(parse(file))))
        }
    }

    /**
     * Not a check: with GOLDEN_RECORD_DIR set (by .github/workflows/golden.yml) it writes `<file>.expected.json` next to every data file under
     * that directory that has none, and never replaces one.
     */
    @Test
    fun recordsTheExpectedResultForNewGoldenFiles() {
        val dir = System.getenv("GOLDEN_RECORD_DIR")
        assumeTrue("GOLDEN_RECORD_DIR is not set", !dir.isNullOrEmpty())
        for (file in dataFiles(File(dir!!))) {
            val expected = File(file.path.removeSuffix(".tsv.xz") + ".expected.json")
            if (!expected.exists()) expected.writeText(describe(parse(file)).toString(1) + "\n")
        }
    }
}
