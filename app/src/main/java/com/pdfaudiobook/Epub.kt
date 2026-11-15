/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/** The EPUB cannot be read; the message is shown to the user as it is. */
class EpubException(message: String) : Exception(message)

/**
 * What an EPUB holds, as the pipeline that OCR feeds takes it.
 * [paragraphs] are in reading order, with each chapter's title as a paragraph of its own where the text does not open with it;
 * [chapters] are those titles, in order. [marks] name the same chapters by the index of their heading among the [Epub.speakable] sentences,
 * which is where the book's own contents put them, so nothing needs to look for them in the text again.
 * [footnotes] are the notes the book marks as such (when asked for), taken out of [paragraphs] and matched to the paragraph that cites them.
 */
data class EpubBook(val title: String, val author: String, val paragraphs: List<String>, val chapters: List<String>, val marks: List<ChapterMark> = emptyList(), val footnotes: List<Footnote> = emptyList())

/**
 * Reads an EPUB 2 or 3 with `java.util.zip` and SAX, which both the JVM and Android have, so a JVM test can run it.
 * An EPUB is a zip of XHTML with a text layer, so unlike a scanned PDF it needs no OCR: the OPF spine gives the reading
 * order, the XHTML gives the paragraphs, and the nav document (EPUB 3) or NCX (EPUB 2) gives the chapters. Images, tables and
 * footnotes are not kept as such, but the text in them is.
 */
object Epub {
    /** Longest XML file read; a bigger one is a zip bomb or not an EPUB. */
    private const val MAX_XML = 64 * 1024 * 1024

    /** Longest paragraph handed to speech whole; a longer one is split into sentences, as OCR text is. */
    const val MAX_PARAGRAPH = 1000

    // Fonts are obfuscated by the book itself, which is no lock on the text; any other encryption is DRM.
    private val fontAlgorithms = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

    /** Reads the book at [file]; [onDoc] counts the spine's documents as each is read. Throws [EpubException] with a message for the user. */
    fun read(file: File, footnotes: Boolean = false, onDoc: (done: Int, total: Int) -> Unit = { _, _ -> }): EpubBook {
        try {
            ZipFile(file).use { return read(it, footnotes, onDoc) }
        } catch (e: ZipException) {
            throw EpubException("This is not an EPUB: the file is not a zip archive.")
        } catch (e: IOException) {
            throw EpubException("Could not read the EPUB: ${e.message}")
        }
    }

    private fun read(zip: ZipFile, footnotes: Boolean, onDoc: (Int, Int) -> Unit): EpubBook {
        zip.text("META-INF/encryption.xml")?.let { xml ->
            val algorithms = Regex("Algorithm\\s*=\\s*[\"']([^\"']+)[\"']").findAll(xml).map { it.groupValues[1] }
            if (algorithms.any { it !in fontAlgorithms }) throw EpubException("This EPUB is protected by DRM, so it cannot be imported.")
        }
        val container = zip.text("META-INF/container.xml") ?: throw EpubException("This is not an EPUB: META-INF/container.xml is missing.")
        val opfPath = Container().also { parse(container, it) }.opf ?: throw EpubException("This EPUB is damaged: container.xml names no package file.")
        val opf = Opf().also { parse(zip.text(opfPath) ?: throw EpubException("This EPUB is damaged: $opfPath is missing."), it) }

        val spine = opf.spine.filter { it.linear }.mapNotNull { opf.manifest[it.idref] }
            .filter { it.type.endsWith("html+xml") || it.type == "text/html" }.filter { "nav" !in it.properties }
        val docs = ArrayList<Pair<String, Doc>>()
        for ((n, item) in spine.withIndex()) {
            onDoc(n, spine.size)
            val path = resolve(opfPath, item.href).first
            val xml = zip.text(path) ?: continue // A listed file the zip lacks costs that file only.
            docs += path to Doc(footnotes).also { parse(xml, it) }.also { it.finish() }
        }
        onDoc(spine.size, spine.size)

        // Paragraph numbers over the whole book, so a chapter mark from the nav can name its place.
        val start = HashMap<String, Int>()
        var total = 0
        for ((path, d) in docs) { start[path] = total; total += d.paragraphs.size }
        if (total == 0) throw EpubException("This EPUB has no readable text.")
        fun place(from: String, href: String): Int? {
            val (path, fragment) = resolve(from, href)
            val doc = docs.firstOrNull { it.first == path }?.second ?: return null
            return start.getValue(path) + (fragment?.let { doc.anchors[it] } ?: 0)
        }

        var marks = chapterEntries(zip, opf, opfPath).mapNotNull { (from, e) -> place(from, e.href)?.let { it to e.title } }.filter { it.first < total }
        if (marks.isEmpty()) marks = docs.mapNotNull { (path, d) -> d.firstHeading?.let { start.getValue(path) + it to d.paragraphs[it] } }
        marks = marks.map { (at, title) -> at to TitleCase.of(title) }.sortedBy { it.first } // Stable, so two titles at one place (a part and its first chapter) keep their order.

        val all = ArrayList<String>(total + marks.size)
        val headings = ArrayList<Pair<Int, String>>() // Index in [all] of each chapter's heading, and its title.
        val flat = docs.flatMap { it.second.paragraphs }
        var m = 0
        for ((i, p) in flat.withIndex()) {
            while (m < marks.size && marks[m].first == i) {
                if (!sameText(marks[m].second, p)) { all += marks[m].second; headings += (all.size - 1) to marks[m].second }
                else headings += all.size to marks[m].second
                m++
            }
            all += p
        }
        val sentencesBefore = IntArray(all.size + 1) // Sentences speech makes of the paragraphs before each one, so a heading's index survives the splitting of long paragraphs.
        for ((i, p) in all.withIndex()) sentencesBefore[i + 1] = sentencesBefore[i] + speakable(listOf(p)).size
        return EpubBook(opf.title, opf.creators.joinToString(", "), all, marks.map { it.second }, headings.map { (i, title) -> ChapterMark(sentencesBefore[i], title) }, docs.flatMap { it.second.footnotes })
    }

    /** Paragraphs a speech engine takes: any longer than [MAX_PARAGRAPH] is split into sentences. */
    fun speakable(paragraphs: List<String>): List<String> = paragraphs.flatMap { if (it.length > MAX_PARAGRAPH) Text.sentences(listOf(it), MAX_PARAGRAPH) else listOf(it) }

    private fun sameText(a: String, b: String) = a.equals(b, ignoreCase = true)

    /** Chapter entries with the path of the file their links are relative to: the nav document, or else the NCX. */
    private fun chapterEntries(zip: ZipFile, opf: Opf, opfPath: String): List<Pair<String, Entry>> {
        opf.manifest.values.firstOrNull { "nav" in it.properties }?.let { item ->
            val path = resolve(opfPath, item.href).first
            val entries = zip.text(path)?.let { Nav().also { n -> parse(it, n) }.entries }.orEmpty()
            if (entries.isNotEmpty()) return entries.map { path to it }
        }
        val ncx = opf.manifest[opf.tocId] ?: opf.manifest.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
        if (ncx != null) {
            val path = resolve(opfPath, ncx.href).first
            return zip.text(path)?.let { Ncx().also { n -> parse(it, n) }.entries }.orEmpty().map { path to it }
        }
        return emptyList()
    }

    /** [href] relative to the file [from]: the zip path, and the fragment after `#` if any. */
    private fun resolve(from: String, href: String): Pair<String, String?> {
        val (file, fragment) = href.substringBefore('#') to href.substringAfter('#', "").ifEmpty { null }
        val decode = { s: String -> try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { s } }
        val parts = ArrayList<String>()
        val joined = if (file.isEmpty()) from else if (file.startsWith("/")) file else from.substringBeforeLast('/', "") + "/" + decode(file)
        for (s in joined.split('/')) when (s) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts += s }
        return parts.joinToString("/") to fragment?.let(decode)
    }

    private fun ZipFile.text(path: String): String? {
        val e = getEntry(path) ?: return null
        if (e.size > MAX_XML) throw EpubException("This EPUB is damaged: $path is too large.")
        return getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }.removePrefix("\uFEFF")
    }

    private val entities = mapOf(
        "nbsp" to "\u00A0", "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•", "laquo" to "«", "raquo" to "»",
        "eacute" to "é", "egrave" to "è", "agrave" to "à", "acirc" to "â", "ccedil" to "ç", "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß",
    )
    private val doctype = Regex("<!DOCTYPE[^>\\[]*(\\[[^\\]]*])?\\s*>", RegexOption.IGNORE_CASE)
    private val entity = Regex("&([A-Za-z][A-Za-z0-9]*);")

    /**
     * SAX over the XML. The DOCTYPE goes first, since XHTML 1.1 names a DTD that a parser would fetch or lack, and the HTML entities
     * XML does not define (`&nbsp;`) become their characters.
     */
    private fun parse(xml: String, handler: DefaultHandler) {
        val clean = xml.replace(doctype, "").replace(entity) {
            when (val n = it.groupValues[1]) { "amp", "lt", "gt", "quot", "apos" -> it.value; else -> entities[n] ?: "" }
        }
        try {
            SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().parse(InputSource(StringReader(clean)), handler)
        } catch (e: SAXException) {
            throw EpubException("This EPUB is damaged: ${e.message?.lineSequence()?.firstOrNull() ?: "invalid XML"}")
        } catch (e: IOException) {
            throw EpubException("This EPUB is damaged: ${e.message}")
        }
    }

    /** The element or attribute name without its prefix; a parser with namespaces on gives the local name, one without gives only the qualified name. */
    private fun name(local: String, qName: String) = local.ifEmpty { qName }.substringAfter(':')

    private fun Attributes.attr(wanted: String): String? {
        for (i in 0 until length) if (name(getLocalName(i), getQName(i)) == wanted) return getValue(i)
        return null
    }

    private class Entry(val title: String, val href: String)
    private class Item(val href: String, val type: String, val properties: List<String>)
    private class Itemref(val idref: String, val linear: Boolean)

    private class Container : DefaultHandler() {
        var opf: String? = null
        override fun startElement(uri: String, local: String, qName: String, atts: Attributes) {
            if (opf == null && name(local, qName) == "rootfile") opf = atts.attr("full-path")
        }
    }

    /** The package file: title, authors, the manifest of files, and the spine, which is their reading order. */
    private class Opf : DefaultHandler() {
        val creators = ArrayList<String>()
        val manifest = LinkedHashMap<String, Item>()
        val spine = ArrayList<Itemref>()
        var title = ""
        var tocId: String? = null
        private var text: StringBuilder? = null

        override fun startElement(uri: String, local: String, qName: String, atts: Attributes) {
            when (name(local, qName)) {
                "title" -> if (title.isEmpty()) text = StringBuilder()
                "creator" -> text = StringBuilder()
                "item" -> {
                    val id = atts.attr("id")
                    val href = atts.attr("href")
                    if (id != null && href != null) manifest[id] = Item(href, atts.attr("media-type").orEmpty(), atts.attr("properties").orEmpty().split(' ').filter { it.isNotEmpty() })
                }
                "spine" -> tocId = atts.attr("toc")
                "itemref" -> atts.attr("idref")?.let { spine += Itemref(it, atts.attr("linear") != "no") }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { text?.append(ch, start, length) }

        override fun endElement(uri: String, local: String, qName: String) {
            val t = text?.toString()?.let(::tidy) ?: return
            when (name(local, qName)) {
                "title" -> title = t
                "creator" -> if (t.isNotEmpty()) creators += t
            }
            text = null
        }
    }

    /** The EPUB 3 nav document: the links of its table of contents, nested lists read in order. */
    private class Nav : DefaultHandler() {
        val entries = ArrayList<Entry>()
        private var inToc = false
        private var done = false
        private var href: String? = null
        private var text = StringBuilder()

        override fun startElement(uri: String, local: String, qName: String, atts: Attributes) {
            when (name(local, qName)) {
                "nav" -> if (!done) { val type = atts.attr("type"); inToc = type == null || "toc" in type.split(' ') }
                "a" -> if (inToc) { href = atts.attr("href"); text = StringBuilder() }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { if (href != null) text.append(ch, start, length) }

        override fun endElement(uri: String, local: String, qName: String) {
            when (name(local, qName)) {
                "a" -> href?.let { h -> tidy(text.toString()).takeIf { it.isNotEmpty() }?.let { entries += Entry(it, h) }; href = null }
                "nav" -> if (inToc) { inToc = false; done = true }
            }
        }
    }

    /** The EPUB 2 NCX: its navPoints in document order, a nested one after its parent. */
    private class Ncx : DefaultHandler() {
        val entries = ArrayList<Entry>()
        private val labels = ArrayList<String>()
        private var text: StringBuilder? = null

        override fun startElement(uri: String, local: String, qName: String, atts: Attributes) {
            when (name(local, qName)) {
                "navPoint" -> labels += ""
                "text" -> if (labels.isNotEmpty()) text = StringBuilder()
                "content" -> if (labels.isNotEmpty()) atts.attr("src")?.let { src -> tidy(labels.last()).takeIf { it.isNotEmpty() }?.let { entries += Entry(it, src) } }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { text?.append(ch, start, length) }

        override fun endElement(uri: String, local: String, qName: String) {
            when (name(local, qName)) {
                "text" -> text?.let { if (labels.last().isEmpty()) labels[labels.lastIndex] = it.toString(); text = null }
                "navPoint" -> labels.removeAt(labels.lastIndex)
            }
        }
    }

    private val space = Regex("[\\s\\u00A0]+")
    private fun tidy(s: String) = s.replace("\u00AD", "").replace(space, " ").trim()

    /** One content document: its paragraphs, and where each `id` sits among them. */
    private class Doc(private val withNotes: Boolean) : DefaultHandler() {
        val paragraphs = ArrayList<String>()
        /** The notes the document marks as footnotes, when asked for: kept out of [paragraphs], each matched to the paragraph before it that cites it. */
        val footnotes = ArrayList<Footnote>()
        private val noted = ArrayList<Pair<Int, String>>()
        private var inNote = 0
        val anchors = HashMap<String, Int>()
        /** The first h1 to h3 paragraph, for a book whose nav gives no chapters. */
        var firstHeading: Int? = null
        private val buf = StringBuilder()
        private var skip = 0
        private var inHeading = false

        private companion object {
            val skipped = setOf("head", "script", "style", "svg", "math", "rt", "rp")
            val blocks = setOf(
                "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dt", "dd", "blockquote", "section", "article", "aside", "header", "footer",
                "figure", "figcaption", "table", "tr", "td", "th", "caption", "pre", "hr", "body", "nav", "address",
            )
            val headings = setOf("h1", "h2", "h3")
            val noteWords = setOf("footnote", "endnote", "rearnote")
        }

        override fun startElement(uri: String, local: String, qName: String, atts: Attributes) {
            val n = name(local, qName)
            atts.attr("id")?.let { anchors.putIfAbsent(it, paragraphs.size) }
            if (n in skipped) skip++
            if (skip > 0) return
            if (inNote > 0) { inNote++; return }
            if (withNotes && n in blocks && isNote(atts)) { flush(); inNote = 1; return }
            if (n in blocks) flush()
            if (n in headings) inHeading = true
            if (n == "br") buf.append(' ')
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { if (skip == 0) buf.append(ch, start, length) }

        override fun endElement(uri: String, local: String, qName: String) {
            val n = name(local, qName)
            if (n in skipped) { skip--; return }
            if (skip > 0) return
            if (inNote > 0) { if (--inNote == 0) flushNote(); return }
            if (n in blocks) flush()
            if (n in headings) inHeading = false
        }

        private fun flush() {
            val p = tidy(buf.toString())
            buf.setLength(0)
            if (p.none { it.isLetterOrDigit() }) return
            if (inHeading && firstHeading == null) firstHeading = paragraphs.size
            paragraphs += p
        }

        private fun flushNote() {
            val p = tidy(buf.toString())
            buf.setLength(0)
            if (p.any { it.isLetterOrDigit() }) noted += paragraphs.size to p
        }

        /** A block the book marks as a footnote, by class (Project Gutenberg), `epub:type` (EPUB 3) or ARIA role. */
        private fun isNote(atts: Attributes): Boolean =
            atts.attr("class")?.split(' ')?.any { it in noteWords } == true ||
                atts.attr("type")?.split(' ')?.any { it in noteWords } == true || atts.attr("role")?.removePrefix("doc-") in noteWords

        fun finish() {
            flush()
            for ((before, text) in noted) footnotes += Footnotes.cite(paragraphs, text, before - 1, nearest = true)
        }
    }
}
