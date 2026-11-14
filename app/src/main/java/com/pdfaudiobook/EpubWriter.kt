/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One XHTML file of the book. A null [title] is text before the first chapter: a file in the reading order but not in the table of contents. */
data class EpubChapter(val title: String?, val paragraphs: List<String>)

/**
 * Writes a book as an EPUB 3 that [Epub] reads back with the same title, author, chapters and paragraphs: a text-only book with
 * one XHTML file per chapter, the paragraphs as `<p>`, and a nav document built from the chapters.
 */
object EpubWriter {
    /** The chapters to write, from the transcript: one paragraph per sentence row, as the reader shows them. A book with no chapters is one, named for the book. */
    fun chapters(segments: List<Segment>, contents: List<String>, title: String): List<EpubChapter> {
        val marks = Chapters.find(segments, contents).distinctBy { it.index }
        if (marks.isEmpty()) return listOf(EpubChapter(title, segments.map { it.text }))
        val front = segments.take(marks.first().index).map { it.text }
        return listOfNotNull(front.takeIf { it.isNotEmpty() }?.let { EpubChapter(null, it) }) + marks.mapIndexed { k, c ->
            val body = segments.subList(c.index, marks.getOrNull(k + 1)?.index ?: segments.size).map { it.text }
            // The heading row becomes the chapter's <h1>, not a paragraph.
            EpubChapter(c.title, if (body.isNotEmpty() && Chapters.isTitleOf(c, body.first())) body.drop(1) else body)
        }
    }

    /** [identifier] names the book (`urn:uuid:...`); [modified] is a UTC time like `2026-11-13T09:00:00Z`. Both are parameters so a test can fix them. */
    fun write(out: OutputStream, title: String, author: String, language: String, identifier: String, modified: String, chapters: List<EpubChapter>) {
        val files = chapters.mapIndexed { i, c -> "chapter-%03d.xhtml".format(i + 1) to c }
        ZipOutputStream(out).use { z ->
            // The mimetype entry comes first and is stored, so a reader can recognise the file from its first bytes.
            val mime = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = CRC32().also { it.update(mime) }.value })
            z.write(mime)
            z.closeEntry()
            fun add(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            add("META-INF/container.xml", container)
            add("OEBPS/content.opf", opf(title, author, language, identifier, modified, files.map { it.first }))
            add("OEBPS/nav.xhtml", nav(title, language, files.filter { it.second.title != null }.map { it.first to it.second.title!! }))
            for ((name, c) in files) add("OEBPS/$name", chapter(c, title, language))
        }
    }

    private const val XHTML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n"

    private val container = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    private fun opf(title: String, author: String, language: String, identifier: String, modified: String, files: List<String>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\" xml:lang=\"${esc(language)}\">\n")
        append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("    <dc:identifier id=\"book-id\">${esc(identifier)}</dc:identifier>\n")
        append("    <dc:title>${esc(title)}</dc:title>\n")
        if (author.isNotBlank()) append("    <dc:creator>${esc(author)}</dc:creator>\n")
        append("    <dc:language>${esc(language)}</dc:language>\n")
        append("    <meta property=\"dcterms:modified\">${esc(modified)}</meta>\n")
        append("  </metadata>\n  <manifest>\n")
        append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        files.forEachIndexed { i, f -> append("    <item id=\"c${i + 1}\" href=\"$f\" media-type=\"application/xhtml+xml\"/>\n") }
        append("  </manifest>\n  <spine>\n")
        files.indices.forEach { append("    <itemref idref=\"c${it + 1}\"/>\n") }
        append("  </spine>\n</package>\n")
    }

    private fun page(title: String, language: String, body: String) = XHTML +
        "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" lang=\"${esc(language)}\" xml:lang=\"${esc(language)}\">\n" +
        "<head>\n  <meta charset=\"utf-8\"/>\n  <title>${esc(title)}</title>\n</head>\n<body>\n$body</body>\n</html>\n"

    private fun nav(title: String, language: String, entries: List<Pair<String, String>>) = page(
        title, language,
        "<nav epub:type=\"toc\" id=\"toc\">\n<h1>Contents</h1>\n<ol>\n" + entries.joinToString("") { (f, t) -> "<li><a href=\"$f\">${esc(t)}</a></li>\n" } + "</ol>\n</nav>\n",
    )

    private fun chapter(c: EpubChapter, bookTitle: String, language: String) =
        page(c.title ?: bookTitle, language, (c.title?.let { "<h1>${esc(it)}</h1>\n" } ?: "") + c.paragraphs.joinToString("") { "<p>${esc(it)}</p>\n" })

    /** XML text, with the characters XML 1.0 forbids dropped. */
    private fun esc(s: String) = s.filter { it == '\t' || it == '\n' || it == '\r' || it in ' '..'퟿' || it in ''..'�' || it.isSurrogate() }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
