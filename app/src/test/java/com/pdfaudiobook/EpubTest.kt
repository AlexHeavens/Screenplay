/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EpubTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun xhtml(body: String) = """<?xml version="1.0"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>"""

    private fun opf(manifest: String, spine: String, extra: String = "") = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Tiny Book</dc:title><dc:creator>Ann Author</dc:creator><dc:creator>Bo Co-Author</dc:creator></metadata><manifest>$manifest</manifest><spine $extra>$spine</spine></package>"""

    private fun epub(vararg entries: Pair<String, String>): File = tmp.newFile().also { f ->
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }

    private fun failure(file: File): String {
        try { Epub.read(file) } catch (e: EpubException) { return e.message!! }
        fail("expected the EPUB to be refused")
        return ""
    }

    /** The checked-in test book, which the device tests import. */
    private val lighthouse = File("src/androidTest/assets/epub/lighthouse.epub")

    private val lighthouseParagraphs = listOf(
        "Part One", "The almanac of a small lighthouse begins with the weather.",
        "The Lamp", "Every evening the keeper climbed ninety steps to light the lamp.", "The lamp was old, but it never failed. Not once in forty years.",
        "Ships passed in the night and never knew his name.",
        "The Fog", "On the foggy mornings he rang the bell instead.", "Bell", "Two strokes a minute",
    )

    @Test
    fun readsTheTitleAuthorParagraphsAndNestedChapters() {
        val b = Epub.read(lighthouse)
        assertEquals("The Lighthouse Almanac", b.title)
        assertEquals("Ada Example", b.author)
        assertEquals(listOf("Part One", "The Lamp", "The Fog"), b.chapters)
        assertEquals(lighthouseParagraphs, b.paragraphs)
    }

    @Test
    fun joinsSeveralAuthorsAndFollowsTheSpineNotTheManifest() {
        val f = epub(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf("""<item id="b" href="b.xhtml" media-type="application/xhtml+xml"/><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/><itemref idref="b"/>"""),
            "OEBPS/a.xhtml" to xhtml("<p>First.</p>"), "OEBPS/b.xhtml" to xhtml("<p>Second.</p>"),
        )
        val b = Epub.read(f)
        assertEquals("Ann Author, Bo Co-Author", b.author)
        assertEquals(listOf("First.", "Second."), b.paragraphs)
    }

    @Test
    fun anEpubWithNoNavGetsItsChaptersFromTheHeadings() {
        val f = epub(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf("""<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/><itemref idref="b"/><itemref idref="c"/>"""),
            "OEBPS/a.xhtml" to xhtml("<h1>Dawn</h1><p>It was early.</p>"),
            "OEBPS/b.xhtml" to xhtml("<p>No heading here.</p>"),
            "OEBPS/c.xhtml" to xhtml("<h2>Dusk</h2><p>It was late.</p>"),
        )
        val b = Epub.read(f)
        assertEquals(listOf("Dawn", "Dusk"), b.chapters)
        assertEquals(listOf("Dawn", "It was early.", "No heading here.", "Dusk", "It was late."), b.paragraphs)
    }

    @Test
    fun marksNameEachChapterHeadingByItsSentenceNotItsContentsListEntry() {
        val longParagraph = (1..40).joinToString(" ") { "A sentence of some length number $it." }
        val nav = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
            <li><a href="a.xhtml#one">Chapter 1. Dawn</a></li><li><a href="a.xhtml#two">Chapter 2. Dusk</a></li></ol></nav></body></html>"""
        val f = epub(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf("""<item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/>"""),
            "OEBPS/nav.xhtml" to nav,
            // The book prints its own contents list ahead of the chapters, as Project Gutenberg's do.
            "OEBPS/a.xhtml" to xhtml("""<p>Chapter 1. Dawn</p><p>Chapter 2. Dusk</p><h2 id="one">Chapter 1. Dawn</h2><p>$longParagraph</p><h2 id="two">Chapter 2. Dusk</h2><p>It was late.</p>"""),
        )
        val b = Epub.read(f)
        val sentences = Epub.speakable(b.paragraphs)
        assertTrue(sentences.size > b.paragraphs.size)
        assertEquals(listOf("Chapter 1. Dawn", "Chapter 2. Dusk"), b.marks.map { it.title })
        assertEquals(listOf("Chapter 1. Dawn", "Chapter 2. Dusk"), b.marks.map { sentences[it.index] })
        assertEquals(listOf(2, sentences.indexOf("It was late.") - 1), b.marks.map { it.index })
    }

    @Test
    fun anEpub2UsesItsNcxAndAnAnchorInsideAFile() {
        val ncx = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><docTitle><text>Tiny Book</text></docTitle><navMap>
            <navPoint id="n1"><navLabel><text>Opening</text></navLabel><content src="all.xhtml"/>
              <navPoint id="n2"><navLabel><text>The Turn</text></navLabel><content src="all.xhtml#turn"/></navPoint></navPoint></navMap></ncx>"""
        val f = epub(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="a" href="all.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/>""", """toc="ncx""""),
            "OEBPS/toc.ncx" to ncx,
            "OEBPS/all.xhtml" to xhtml("""<p>One.</p><p>Two.</p><p id="turn">Three.</p><p>Four.</p>"""),
        )
        val b = Epub.read(f)
        assertEquals(listOf("Opening", "The Turn"), b.chapters)
        assertEquals(listOf("Opening", "One.", "Two.", "The Turn", "Three.", "Four."), b.paragraphs)
    }

    @Test
    fun keepsTheTextOfTablesNotesAndEntitiesButNotScriptsOrImages() {
        val f = epub(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf("""<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/>"""),
            "OEBPS/a.xhtml" to xhtml("""<script>var x = "no";</script><p>Caf&eacute;&nbsp;au lait<a href="#n1"><sup>1</sup></a>.</p><img src="x.png" alt="pic"/><table><tr><td>Cell</td></tr></table><aside id="n1">Note text.</aside><p>* * *</p>"""),
        )
        assertEquals(listOf("Café au lait1.", "Cell", "Note text."), Epub.read(f).paragraphs)
    }

    /** One chapter as Project Gutenberg marks it: a linked `[n]` in the text, and the note as `<p class="footnote">` straight after its paragraph. */
    private fun gutenberg() = epub(
        "META-INF/container.xml" to container,
        "OEBPS/content.opf" to opf("""<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/>"""),
        "OEBPS/a.xhtml" to xhtml(
            """<h2>CHAPTER III</h2><p>The Venetians had called the French into Italy to win half of Lombardy for themselves.</p>""" +
                """<p>King Louis made five mistakes in Italy, for he allowed<a href="#fn-3.1" id="fnref-3.1" class="pginternal"><sup>[3]</sup></a> the weaker powers to be destroyed.</p>""" +
                """<p class="footnote"><a id="fn-3.1"/> <a href="#fnref-3.1" class="pginternal">[3]</a> Louis XII entered Italy in 1499 at the invitation of the Venetians.</p>""" +
                """<p>He then went on to lose the whole of it again.</p>""",
        ),
    )

    @Test
    fun aMarkedFootnoteIsTakenOutOfTheTextAndMatchedToTheParagraphThatCitesIt() {
        val b = Epub.read(gutenberg(), footnotes = true)
        assertEquals(
            listOf("CHAPTER III", "The Venetians had called the French into Italy to win half of Lombardy for themselves.",
                "King Louis made five mistakes in Italy, for he allowed the weaker powers to be destroyed.", "He then went on to lose the whole of it again."),
            b.paragraphs,
        )
        assertEquals(listOf("[3] Louis XII entered Italy in 1499 at the invitation of the Venetians."), b.footnotes.map { it.text })
        assertEquals(mapOf(2 to listOf(b.footnotes.single().text)), Footnotes.place(b.footnotes, b.paragraphs))
    }

    @Test
    fun withTheOptionOffAMarkedFootnoteIsReadAsBefore() {
        val b = Epub.read(gutenberg())
        assertTrue(b.footnotes.isEmpty())
        assertTrue(b.paragraphs.any { it.startsWith("[3] Louis XII") })
        assertTrue(b.paragraphs.any { "allowed[3] the weaker" in it })
    }

    @Test
    fun anEpub3NoteIsFoundByItsTypeAndAnUnlabelledOneGoesWithTheParagraphBefore() {
        val f = epub(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf("""<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/>"""),
            "OEBPS/a.xhtml" to xhtml("""<p>A paragraph that ends the argument here.</p><aside xmlns:epub="http://www.idpf.org/2007/ops" epub:type="footnote"><p>See the author's own preface.</p></aside><p>The next one.</p>"""),
        )
        val b = Epub.read(f, footnotes = true)
        assertEquals(listOf("A paragraph that ends the argument here.", "The next one."), b.paragraphs)
        assertEquals(mapOf(0 to listOf("See the author's own preface.")), Footnotes.place(b.footnotes, b.paragraphs))
    }

    /** The first file of The Prince from Project Gutenberg: a licence, a title, a contents list with no page numbers (linked entries), then the book's own text. */
    private fun princeFile(vararg entries: String) = epub(
        "META-INF/container.xml" to container,
        "OEBPS/content.opf" to opf(
            """<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/><itemref idref="b"/>""",
        ),
        "OEBPS/a.xhtml" to xhtml(
            """<p>The Project Gutenberg eBook of The Prince</p><p>Translated by W. K. Marriott</p><h2>Contents</h2>""" +
                entries.joinToString("") { """<p><a href="b.xhtml#c">$it</a></p>""" } +
                """<p id="start">Nicolo Machiavelli was born at Florence on 3rd May 1469, the second son of a lawyer of some repute.</p>""",
        ),
        "OEBPS/b.xhtml" to xhtml("""<h2 id="c">CHAPTER I</h2><p>All states, all powers, that have held and hold rule over men have been and are either republics or principalities.</p>"""),
    )

    private val princeEntries = arrayOf(
        "INTRODUCTION", "DEDICATION", "CHAPTER I. HOW MANY KINDS OF PRINCIPALITIES THERE ARE", "CHAPTER II. CONCERNING HEREDITARY PRINCIPALITIES",
        "CHAPTER III. CONCERNING MIXED PRINCIPALITIES", "THE LIFE OF CASTRUCCIO CASTRACANI OF LUCCA",
    )

    @Test
    fun aContentsListInTheMiddleOfAFileIsTakenOutOfTheTextAndKeptAsANote() {
        val b = Epub.read(princeFile(*princeEntries), footnotes = true, contents = true)
        assertEquals(
            listOf("The Project Gutenberg eBook of The Prince", "Translated by W. K. Marriott",
                "Nicolo Machiavelli was born at Florence on 3rd May 1469, the second son of a lawyer of some repute.",
                "CHAPTER I", "All states, all powers, that have held and hold rule over men have been and are either republics or principalities."),
            b.paragraphs,
        )
        val note = b.footnotes.single()
        assertTrue(note.contents)
        assertEquals(listOf("Contents") + princeEntries, note.text.lines())
        // The note is shown, with no marker, at the start of the book.
        assertEquals(mapOf(0 to listOf(note.text)), Footnotes.place(b.footnotes, b.paragraphs))
        assertTrue(Footnotes.markers(b.footnotes, b.paragraphs).isEmpty())
    }

    @Test
    fun withTheContentsOptionOffAContentsListIsReadAsBefore() {
        val b = Epub.read(princeFile(*princeEntries), footnotes = true)
        assertTrue(b.footnotes.isEmpty())
        assertTrue("Contents" in b.paragraphs)
        assertTrue(princeEntries.all { it in b.paragraphs })
    }

    @Test
    fun aFileOfTheBookThatMerelyHasAHeadingCalledContentsIsLeftAlone() {
        val f = epub(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf("""<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/>"""),
            "OEBPS/a.xhtml" to xhtml("""<h2>Contents</h2><p>The contents of the jar were never known to the keeper, who would not open it for any price.</p><p>He wrote only that it was heavy.</p>"""),
        )
        val b = Epub.read(f, footnotes = true, contents = true)
        assertTrue(b.footnotes.isEmpty())
        assertEquals(3, b.paragraphs.size)
    }

    @Test
    fun aNavEntryThatPointsAtTheContentsListDoesNotMakeAChapterNamedContents() {
        val f = epub(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf(
                """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>""",
                """<itemref idref="a"/><itemref idref="b"/>""",
            ),
            "OEBPS/nav.xhtml" to xhtml("""<nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops"><ol><li><a href="a.xhtml#toc">Contents</a></li><li><a href="b.xhtml#c">One</a></li></ol></nav>"""),
            "OEBPS/a.xhtml" to xhtml("""<h2 id="toc">Contents</h2><p>One 1</p><p>Two 9</p><p>Three 17</p><p>Four 25</p>"""),
            "OEBPS/b.xhtml" to xhtml("""<h2 id="c">One</h2><p>The keeper climbed ninety steps to the lamp every evening of his life.</p>"""),
        )
        val b = Epub.read(f, footnotes = true, contents = true)
        assertEquals(listOf("One"), b.chapters)
        assertEquals(listOf("One", "The keeper climbed ninety steps to the lamp every evening of his life."), b.paragraphs)
        assertEquals(listOf(0), b.marks.map { it.index })
    }

    @Test
    fun aDrmEpubIsRefusedWithAClearMessage() {
        val enc = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/><CipherData><CipherReference URI="OEBPS/a.xhtml"/></CipherData></EncryptedData></encryption>"""
        val f = epub(
            "META-INF/container.xml" to container, "META-INF/encryption.xml" to enc,
            "OEBPS/content.opf" to opf("""<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/>"""), "OEBPS/a.xhtml" to "scrambled",
        )
        assertEquals("This EPUB is protected by DRM, so it cannot be imported.", failure(f))
    }

    @Test
    fun anObfuscatedFontIsNotDrm() {
        val enc = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData><EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/><CipherData><CipherReference URI="OEBPS/f.otf"/></CipherData></EncryptedData></encryption>"""
        val f = epub(
            "META-INF/container.xml" to container, "META-INF/encryption.xml" to enc,
            "OEBPS/content.opf" to opf("""<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/>"""), "OEBPS/a.xhtml" to xhtml("<p>Readable.</p>"),
        )
        assertEquals(listOf("Readable."), Epub.read(f).paragraphs)
    }

    @Test
    fun malformedFilesAreRefusedNotCrashedOn() {
        assertTrue(failure(tmp.newFile().also { it.writeText("not a zip at all") }).contains("not a zip"))
        assertTrue(failure(tmp.newFile()).contains("not a zip")) // empty
        assertTrue(failure(epub("readme.txt" to "x")).contains("container.xml"))
        assertTrue(failure(epub("META-INF/container.xml" to container)).contains("content.opf is missing"))
        assertTrue(failure(epub("META-INF/container.xml" to container, "OEBPS/content.opf" to "<package><metadata>")).contains("damaged"))
        val empty = epub(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf("""<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>""", """<itemref idref="a"/>"""), "OEBPS/a.xhtml" to xhtml("<img src='x'/>"),
        )
        assertEquals("This EPUB has no readable text.", failure(empty))
    }

    @Test
    fun aRoundTripKeepsTitleAuthorChaptersAndParagraphs() {
        val original = Epub.read(lighthouse)
        val segments = original.paragraphs.mapIndexed { i, p -> Segment(i * 1000L, i * 1000L + 1000, p) }
        val out = ByteArrayOutputStream()
        EpubWriter.write(out, original.title, original.author, "en", "urn:uuid:x", "2026-11-13T00:00:00Z", EpubWriter.chapters(segments, original.chapters, original.title))
        val again = Epub.read(tmp.newFile().also { it.writeBytes(out.toByteArray()) })
        assertEquals(original, again)
    }

    @Test
    fun aRoundTripKeepsTextBeforeTheFirstChapterOutOfTheContents() {
        val segments = listOf("A dedication.", "Chapter 1", "Opening line.", "Chapter 2", "Closing line.").mapIndexed { i, t -> Segment(i * 1000L, i * 1000L + 1000, t) }
        val out = ByteArrayOutputStream()
        EpubWriter.write(out, "T & <U>", "A \"B\"", "en", "urn:uuid:x", "2026-11-13T00:00:00Z", EpubWriter.chapters(segments, emptyList(), "T & <U>"))
        val b = Epub.read(tmp.newFile().also { it.writeBytes(out.toByteArray()) })
        assertEquals("T & <U>", b.title)
        assertEquals("A \"B\"", b.author)
        assertEquals(listOf("Chapter 1", "Chapter 2"), b.chapters)
        assertEquals(segments.map { it.text }, b.paragraphs)
    }

    @Test
    fun theWrittenFileIsAnEpub3Container() {
        val out = ByteArrayOutputStream()
        EpubWriter.write(out, "Title", "Author", "en", "urn:uuid:x", "2026-11-13T00:00:00Z", listOf(EpubChapter("One", listOf("Hello.")), EpubChapter("Two", listOf("Bye."))))
        val f = tmp.newFile().also { it.writeBytes(out.toByteArray()) }
        ZipFile(f).use { z ->
            val names = z.entries().toList()
            assertEquals("mimetype", names.first().name)
            assertEquals(ZipEntry.STORED, names.first().method)
            assertEquals("application/epub+zip", z.getInputStream(names.first()).readBytes().toString(Charsets.UTF_8))
            assertEquals(0, names.first().extra?.size ?: 0)
            val opf = z.getInputStream(z.getEntry("OEBPS/content.opf")).readBytes().toString(Charsets.UTF_8)
            for (needle in listOf("version=\"3.0\"", "<dc:title>Title</dc:title>", "<dc:creator>Author</dc:creator>", "<dc:language>en</dc:language>", "dcterms:modified", "properties=\"nav\"")) assertTrue(needle, needle in opf)
            val nav = z.getInputStream(z.getEntry("OEBPS/nav.xhtml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(nav.contains("epub:type=\"toc\"") && nav.contains(">One</a>") && nav.contains(">Two</a>"))
            assertTrue(z.getInputStream(z.getEntry("OEBPS/chapter-001.xhtml")).readBytes().toString(Charsets.UTF_8).contains("<h1>One</h1>\n<p>Hello.</p>"))
        }
    }
}
