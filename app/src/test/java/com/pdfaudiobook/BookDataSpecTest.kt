/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The per-book stored files are a contract with books already on phones (docs/data-specs.md): the app's own writer must produce files
 * that satisfy the frozen schemas, and the current reader must read every golden file ever captured, and files with fields missing or added.
 */
class BookDataSpecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun assertValid(kind: String, text: String, what: String) {
        val problems = BookSpec.problems(kind, text)
        assertTrue("$what does not satisfy schemas/$kind/v1.json: $problems", problems.isEmpty())
    }

    private fun assertInvalid(kind: String, text: String, what: String) =
        assertFalse("$what should not satisfy schemas/$kind/v1.json", BookSpec.problems(kind, text).isEmpty())

    private fun sameJson(expected: String, actual: String, what: String) {
        val e = JSONTokener(expected); val a = JSONTokener(actual)
        assertTrue("$what\nexpected: $expected\n  actual: $actual", e.similar(a))
    }

    private class JSONTokener(text: String) {
        private val value: Any = text.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }
        fun similar(o: JSONTokener): Boolean = if (value is JSONObject) value.similar(o.value) else (value as JSONArray).similar(o.value)
    }

    // ---- the real writer satisfies the schemas ----

    @Test
    fun metaWrittenForEveryStateSatisfiesTheSchema() {
        for ((name, text) in BookSpec.metaTexts()) assertValid("book", text, "meta of '$name'")
        for (s in Status.entries) assertTrue("a fixture per status", BookSpec.books.values.any { it.status == s })
    }

    @Test
    fun everyStoredListSatisfiesItsSchema() {
        for ((kind, byName) in BookSpec.listTexts(tmp.newFolder())) for ((name, text) in byName) assertValid(kind, text, "$kind '$name'")
    }

    @Test
    fun metaIsWrittenWithItsVersion() {
        for ((name, text) in BookSpec.metaTexts()) assertEquals("version of '$name'", Library.META_VERSION, JSONObject(text).getInt("version"))
    }

    @Test
    fun theWriterEmitsExactlyTheFieldsTheSchemaNames() {
        val props = JSONObject(BookSpec.schema("book").readText()).getJSONObject("properties").keys().asSequence().toSet()
        val written = BookSpec.books.values.flatMap { JSONObject(Library.metaJson(it).toString()).keys().asSequence().toList() }.toSet() +
            BookSpec.imports.values.flatMap { it.keys().asSequence().toList() }
        assertEquals(props, written)
    }

    @Test
    fun metaWrittenByUpdateAlwaysHasEveryFieldThatIsNotConditional() {
        val always = setOf("version", "title", "author", "status", "progress", "duration", "created", "words", "wordsTotal", "pages", "pagesTotal", "wordsQueued", "rate", "contents", "estimates")
        for ((name, book) in BookSpec.books) assertTrue("'$name' lacks ${always - JSONObject(Library.metaJson(book).toString()).keys().asSequence().toSet()}", JSONObject(Library.metaJson(book).toString()).keys().asSequence().toSet().containsAll(always))
    }

    @Test
    fun theWriterOutputReadsBackAsTheBookThatWasWritten() {
        for ((name, book) in BookSpec.books) {
            val dir = tmp.newFolder("b-$name")
            Library.writeMeta(dir, book)
            val read = Library.readBook(dir)!!
            assertEquals("'$name'", book.copy(id = dir.name, awaitingDownload = book.downloadUrl.isNotEmpty()), read)
        }
    }

    @Test
    fun aBookWithNoEpubYetIsAwaitingItsDownload() {
        val dir = tmp.newFolder("d")
        File(dir, Files.META).writeText(BookSpec.imports.getValue("download-queued").toString())
        assertTrue(Library.readBook(dir)!!.awaitingDownload)
        File(dir, Files.EPUB).writeText("x")
        assertFalse(Library.readBook(dir)!!.awaitingDownload)
    }

    // ---- golden files: every one captured so far is read by the current reader ----

    private fun goldenFolders(kind: String): List<File> =
        File(BookSpec.root, "golden/$kind").listFiles { f -> f.isDirectory }!!.sortedBy { it.name }

    private fun goldenFiles(dir: File) = dir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".expected.json") }!!.sortedBy { it.name }

    @Test
    fun everyGoldenFileIsValidAndReadsAsItsExpectedFile() {
        var count = 0
        for ((kind, reader) in BookSpec.kinds) {
            val folders = goldenFolders(kind)
            assertTrue("no golden versions for $kind", folders.isNotEmpty())
            for (dir in folders) for (f in goldenFiles(dir)) {
                val expected = File(dir, f.nameWithoutExtension + ".expected.json")
                assertTrue("${f.path} has no ${expected.name}", expected.exists())
                assertValid(kind, f.readText(), f.path)
                sameJson(expected.readText(), reader.show(f), "${f.path} read differently")
                count++
            }
            for (e in dir(kind).filter { it.name.endsWith(".expected.json") }) assertTrue("${e.path} has no golden file", File(e.parentFile, e.name.removeSuffix(".expected.json") + ".json").exists())
        }
        assertTrue("golden files found: $count", count > 40)
    }

    private fun dir(kind: String) = goldenFolders(kind).flatMap { it.listFiles()!!.toList() }

    @Test
    fun everyReleasedGoldenFolderHasAFrozenSchema() {
        for (kind in BookSpec.kinds.keys) for (d in goldenFolders(kind).filter { it.name.matches(Regex("v\\d+")) })
            assertTrue("no schema for ${d.path}", BookSpec.schema(kind, d.name.drop(1).toInt()).exists())
    }

    @Test
    fun goldenFilesCoverEveryStatusAndEveryFixtureName() {
        val names = goldenFiles(File(BookSpec.root, "golden/book/v1")).map { it.nameWithoutExtension }.filter { !it.startsWith("legacy-") }.toSet()
        assertEquals(BookSpec.metaTexts().keys, names)
        val statuses = goldenFiles(File(BookSpec.root, "golden/book/v1")).map { JSONObject(it.readText()).getString("status") }.toSet()
        assertEquals(Status.entries.map { it.name }.toSet(), statuses)
    }

    // ---- a missing optional field reads as its documented default ----

    private fun fullMeta() = JSONObject(Library.metaJson(BookSpec.books.getValue("ready-full").copy(error = "Could not read the PDF")).toString())
    private fun load(j: JSONObject) = Library.bookFromMeta("golden", j, hasEpub = false)

    /** Field name in `meta.json` -> what the book is when only that field is left out of an otherwise full file. */
    private val defaults: Map<String, (Book) -> Book> = mapOf(
        "author" to { it.copy(author = "") }, "progress" to { it.copy(progress = 0f) }, "error" to { it.copy(error = null) },
        "duration" to { it.copy(durationMs = 0) }, "created" to { it.copy(created = 0) }, "words" to { it.copy(words = 0) },
        "wordsTotal" to { it.copy(wordsTotal = 0) }, "pages" to { it.copy(pages = 0) }, "pagesTotal" to { it.copy(pagesTotal = 0) },
        "wordsQueued" to { it.copy(wordsQueued = 0) }, "rate" to { it.copy(rate = 0) }, "contents" to { it.copy(contents = emptyList()) },
        "estimates" to { it.copy(estimates = emptyList()) }, "chapters" to { it.copy(chapters = null) }, "position" to { it.copy(positionMs = null) },
        "finished" to { it.copy(finished = false) }, "order" to { it.copy(queueOrder = 0) }, "source" to { it.copy(source = "") },
        "download" to { it.copy(downloadUrl = "", awaitingDownload = false) }, "version" to { it },
    )

    @Test
    fun everyOptionalMetaFieldHasATestedDefault() {
        val props = JSONObject(BookSpec.schema("book").readText()).getJSONObject("properties").keys().asSequence().toSet()
        val required = JSONObject(BookSpec.schema("book").readText()).getJSONArray("required").let { a -> List(a.length()) { a.getString(it) } }.toSet()
        assertEquals(props - required, defaults.keys)
    }

    @Test
    fun aMetaFileMissingAnyOptionalFieldLoadsWithItsDefault() {
        val full = fullMeta()
        val whole = load(full)
        assertTrue("the full fixture sets every field", full.keys().asSequence().toSet() == defaults.keys + setOf("title", "status"))
        for ((field, expected) in defaults) {
            val j = JSONObject(full.toString()).also { it.remove(field) }
            assertValid("book", j.toString(), "meta without '$field'")
            assertEquals("without '$field'", expected(whole), load(j))
        }
    }

    @Test
    fun aMetaFileWithOnlyTheRequiredFieldsLoadsWithEveryDefault() {
        val j = JSONObject().put("title", "Bare").put("status", "QUEUED")
        assertValid("book", j.toString(), "bare meta")
        assertEquals(Book(id = "golden", title = "Bare", status = Status.QUEUED, progress = 0f, error = null, durationMs = 0, created = 0), load(j))
    }

    @Test
    fun anEstimateMissingItsOptionalFieldsLoadsWithTheirDefaults() {
        val j = JSONObject().put("title", "T").put("status", "READY").put("estimates", JSONArray().put(JSONObject().put("title", "Ch")))
        assertValid("book", j.toString(), "estimate with only a title")
        assertEquals(listOf(ChapterEstimate("Ch", null, null, emptyList())), load(j).estimates)
        val withNulls = JSONObject().put("title", "T").put("status", "READY").put("estimates", JSONArray().put(JSONObject().put("title", "Ch").put("printed", JSONObject.NULL).put("page", JSONObject.NULL)))
        assertValid("book", withNulls.toString(), "estimate with null page numbers")
        assertEquals(load(j).estimates, load(withNulls).estimates)
    }

    @Test
    fun theRequiredMetaFieldsAreRequiredByTheSchemaAndTheReader() {
        for (field in listOf("title", "status")) {
            val j = fullMeta().also { it.remove(field) }
            assertInvalid("book", j.toString(), "meta without '$field'")
            try { load(j); fail("reader accepted meta without '$field'") } catch (_: org.json.JSONException) {}
        }
    }

    @Test
    fun aStatusNoReaderKnowsIsRejectedByTheSchemaAndTheReader() {
        val j = fullMeta().put("status", "PAUSED")
        assertInvalid("book", j.toString(), "unknown status")
        try { load(j); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun theOptionalFieldsOfListEntriesHaveTheirDefaults() {
        val seg = File(tmp.root, "t.json").also { it.writeText("""[{"start":1,"end":2,"text":"x"}]""") }
        assertValid("segments", seg.readText(), "segment without at")
        assertEquals(listOf(Segment(1, 2, "x", -1)), Library.readSegments(seg))
        val fn = File(tmp.root, "f.json").also { it.writeText("""[{"anchor":"1","text":"x"}]""") }
        assertValid("footnotes", fn.readText(), "footnote without contents")
        assertEquals(listOf(Footnote("1", "x", false)), Library.readFootnotes(fn))
        val hl = File(tmp.root, "h.json").also { it.writeText("""[{"index":1,"hash":2,"color":"MAGENTA"}]""") }
        assertEquals(HighlightColor.DEFAULT, Highlights.read(hl).single().color)
    }

    @Test
    fun aMissingOptionalFileReadsAsEmpty() {
        val none = File(tmp.root, "absent.json")
        assertEquals(emptyList<Segment>(), Library.readSegments(none))
        assertEquals(emptyList<Footnote>(), Library.readFootnotes(none))
        assertEquals(emptyList<ParagraphNote>(), ParagraphNotes.read(none))
        assertEquals(emptyList<ParagraphHighlight>(), Highlights.read(none))
    }

    // ---- data written by a newer app is survived by this reader ----

    @Test
    fun unknownFieldsAndANewerVersionAreIgnored() {
        val full = fullMeta()
        val whole = load(full)
        val newer = JSONObject(full.toString()).put("version", 99).put("brandNew", JSONObject().put("a", 1)).put("alsoNew", JSONArray().put(1))
        newer.getJSONArray("estimates").getJSONObject(0).put("extra", "x")
        newer.getJSONArray("chapters").getJSONObject(0).put("extra", true)
        assertEquals(whole, load(newer))
        assertEquals(whole, load(JSONObject(full.toString()).also { it.remove("version") }))
    }

    @Test
    fun unknownFieldsInListEntriesAreIgnored() {
        val seg = File(tmp.root, "t.json").also { it.writeText("""[{"start":1,"end":2,"text":"x","at":4,"speaker":"A","words":[1,2]}]""") }
        assertEquals(listOf(Segment(1, 2, "x", 4)), Library.readSegments(seg))
        val notes = File(tmp.root, "n.json").also { it.writeText("""[{"id":"a","at":1,"text":"t","colour":"red"}]""") }
        assertEquals(listOf(Note("a", 1, "t")), Library.readNotes(notes))
        val pn = File(tmp.root, "pn.json").also { it.writeText("""[{"index":1,"hash":2,"text":"t","tags":[]}]""") }
        assertEquals(listOf(ParagraphNote(1, 2, "t")), ParagraphNotes.read(pn))
        val hl = File(tmp.root, "h.json").also { it.writeText("""[{"index":1,"hash":2,"color":"PINK","opacity":0.5}]""") }
        assertEquals(listOf(ParagraphHighlight(1, 2, HighlightColor.PINK)), Highlights.read(hl))
        val fn = File(tmp.root, "f.json").also { it.writeText("""[{"anchor":"1","text":"x","contents":true,"page":3}]""") }
        assertEquals(listOf(Footnote("1", "x", true)), Library.readFootnotes(fn))
        for ((kind, text) in listOf("segments" to seg, "notes" to notes, "paragraph-notes" to pn, "highlights" to hl, "footnotes" to fn)) assertValid(kind, text.readText(), "$kind with extra fields")
    }

    @Test
    fun anUnreadableTranscriptIsNullNotACrash() {
        val f = File(tmp.root, "t.json").also { it.writeText("""[{"start":1,"end":2,"te""") }
        assertNull(Library.readSegments(f))
        assertInvalid("segments", """{"start":1}""", "a transcript that is not an array")
    }

    // ---- golden capture: the "Per-book files" step of .github/workflows/golden.yml runs this ----

    /**
     * Writes golden files from the real writers into `$GOLDEN_OUT_DIR/<kind>/v<META_VERSION>`, each with its `.expected.json` as the current reader
     * reads it. Goldens are never replaced: it fails when that folder exists there or in `golden/`. For version 1 it also writes the shapes of the
     * first release beside them as `legacy-*` (there is no older writer to run).
     */
    @Test
    fun generateGoldenFiles() {
        val out = System.getenv("GOLDEN_OUT_DIR")
        assumeTrue("set GOLDEN_OUT_DIR to capture golden files", out != null)
        val v = "v${Library.META_VERSION}"
        for (kind in BookSpec.kinds.keys) for (base in listOf(File(out!!), File(BookSpec.root, "golden")))
            assertFalse("${File(base, "$kind/$v").path} exists: golden files are never replaced", File(base, "$kind/$v").exists())
        fun put(kind: String, name: String, text: String) {
            val d = File(out, "$kind/$v").apply { mkdirs() }
            val f = File(d, "$name.json").apply { writeText(text) }
            File(d, "$name.expected.json").writeText(BookSpec.kinds.getValue(kind).show(f))
        }
        for ((name, text) in BookSpec.metaTexts()) put("book", name, text)
        val lists = BookSpec.listTexts(tmp.newFolder())
        for ((kind, byName) in lists) for ((name, text) in byName) put(kind, name, text)
        if (Library.META_VERSION == 1) {
            // First release: no `version`, no `download`, no segment `at` (git show 18383a7:app/src/main/java/com/pdfaudiobook/Library.kt). Those files
            // follow schema v1, so they sit in its folder under a legacy- prefix.
            for ((name, text) in BookSpec.metaTexts()) JSONObject(text).let { if (!it.has("download")) put("book", "legacy-$name", it.also { j -> j.remove("version") }.toString()) }
            // A backup made before details were kept restores as just a title, a duration, a status and a time (Backup.restoreBook).
            put("book", "legacy-backup-without-details", """{"title":"Old backup","duration":9508,"status":"READY","created":1763000000000}""")
            for ((name, text) in lists.getValue("segments")) put("segments", "legacy-$name", JSONArray(text).also { a -> for (i in 0 until a.length()) a.getJSONObject(i).remove("at") }.toString())
        }
    }
}
