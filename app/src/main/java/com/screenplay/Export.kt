/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A stretch of the audio under one title, in milliseconds. */
data class Span(val title: String, val start: Long, val end: Long)

/**
 * The finished book's audio as files other apps play. The book's `audio.m4a` is already AAC in MP4, so nothing is re-encoded:
 * the samples are copied (a book is hours long, and a second encode would cost minutes and quality), then the chapter marks
 * and the title and author are added to the MP4.
 *
 * A book still being made has no `audio.m4a`; its export waits until it is [Status.READY].
 */
object Export {
    private const val CHAPTER_LIMIT = 255 // What the chapter list ("chpl") format can count.

    /** The version of the export's layout, so a tool reading `metadata.json` can tell which fields to expect. Raise it when the file's fields change. */
    const val FORMAT = "0.0.1"
    const val METADATA_FILE = "metadata.json"

    /** What goes into `metadata.json`: the format version, title, author and the chapters with their start times. Apart from Android so a JVM test can read it back. */
    fun metadata(title: String, author: String, chapters: List<Span>): JSONObject = JSONObject()
        .put("export_format", FORMAT)
        .put("title", title)
        .put("author", author)
        .put("chapters", JSONArray(chapters.map { JSONObject().put("title", it.title).put("start_ms", it.start) }))

    private fun writeMetadata(dir: File, meta: JSONObject, title: String, chapters: List<Span>) =
        File(dir, METADATA_FILE).also { it.writeText(metadata(title, meta.optString("author"), chapters).toString(2)) }

    /** The chapters to mark, the heading sentences found in the transcript. Audio before the first heading is "Start"; a book with no headings is one chapter. */
    fun spans(segments: List<Segment>, chapters: List<Chapter>, title: String): List<Span> {
        val total = segments.lastOrNull()?.end ?: 0L
        val found = chapters.distinctBy { it.start }.take(CHAPTER_LIMIT - 1)
        val starts = when {
            found.isEmpty() -> listOf(Chapter(0, 0, title))
            found.first().start > 0 -> listOf(Chapter(0, 0, "Start")) + found
            else -> found
        }
        return starts.mapIndexed { i, c -> Span(c.title, c.start, starts.getOrNull(i + 1)?.start ?: total) }
    }

    /** The comment tag: which app build made the audio; empty for a book that does not record it. */
    private fun built(meta: JSONObject) = meta.optString("commit").let { if (it.isEmpty()) "" else "Made by Screenplay, commit $it" }

    /** One M4B: all the audio, with chapter marks, title and author, then the `metadata.json` that goes with it. */
    fun m4b(ctx: Context, id: String): List<File> {
        val meta = meta(ctx, id)
        val title = meta.getString("title")
        val dir = File(exportDir(ctx), "${fileName(title)} (M4B)").also { it.deleteRecursively(); it.mkdirs() }
        val out = File(dir, "${fileName(title)}.m4b")
        val chapters = spans(Library.segments(ctx, id), Library.chapters(ctx, id), title)
        copy(audio(ctx, id), out, 0, Long.MAX_VALUE)
        Mp4Tags.add(out, title, meta.optString("author"), chapters, comment = built(meta))
        return listOf(out, writeMetadata(dir, meta, title, chapters))
    }

    /** One M4A per chapter, numbered so they sort in order, then the `metadata.json` that goes with them. */
    fun chapterFiles(ctx: Context, id: String): List<File> {
        val meta = meta(ctx, id)
        val title = meta.getString("title")
        val dir = File(exportDir(ctx), fileName(title)).also { it.deleteRecursively(); it.mkdirs() }
        val chapters = spans(Library.segments(ctx, id), Library.chapters(ctx, id), title)
        return chapters.mapIndexed { i, s ->
            File(dir, "%02d %s.m4a".format(i + 1, fileName(s.title))).also {
                copy(audio(ctx, id), it, s.start, s.end)
                Mp4Tags.add(it, s.title, meta.optString("author"), emptyList(), album = title, comment = built(meta))
            }
        } + writeMetadata(dir, meta, title, chapters)
    }

    /** The book as an EPUB 3 ([EpubWriter]): text only, with the chapters of the reader. Needs no audio. */
    fun epub(ctx: Context, id: String): List<File> {
        val meta = meta(ctx, id)
        val title = meta.getString("title")
        val out = File(exportDir(ctx), "${fileName(title)}.epub")
        out.outputStream().buffered().use {
            EpubWriter.write(
                it, title, meta.optString("author"), "en", "urn:uuid:${UUID.nameUUIDFromBytes(id.toByteArray())}",
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), EpubWriter.chapters(Library.segments(ctx, id), contents(meta), title),
            )
        }
        return listOf(out)
    }

    fun share(ctx: Context, files: List<File>, chooserTitle: String) {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("*/*") // The audio and its metadata.json differ in type.
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) send.putExtra(Intent.EXTRA_STREAM, uris[0]) else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        ctx.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun exportDir(ctx: Context) = File(ctx.cacheDir, "export").also { it.mkdirs() }

    private fun fileName(s: String) = s.replace(Regex("[^\\w .-]"), "_").trim().take(80).ifEmpty { "audiobook" }

    private fun audio(ctx: Context, id: String) = File(Library.dir(ctx, id), Files.AUDIO).also { require(it.exists()) { "The book's audio is not finished yet" } }

    /**
     * The book's metadata file: title, and the author the ISBN lookup stores there. Read from it, never from the PDF's file name.
     * The author is read here as a plain key so the export does not depend on how the lookup models it.
     */
    private fun meta(ctx: Context, id: String) = JSONObject(File(Library.dir(ctx, id), Files.META).readText())

    private fun contents(meta: JSONObject): List<String> = meta.optJSONArray("contents")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()

    /** Copies the AAC samples between [startMs] and [endMs] into a new MP4, times counted from zero. */
    private fun copy(src: File, out: File, startMs: Long, endMs: Long) {
        val ex = MediaExtractor()
        ex.setDataSource(src.path)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            ex.selectTrack(0)
            val track = muxer.addTrack(ex.getTrackFormat(0))
            muxer.start()
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var first = -1L
            while (true) {
                val t = ex.sampleTime
                if (t < 0 || t >= endMs.coerceAtMost(Long.MAX_VALUE / 1000) * 1000) break
                if (first < 0) first = t
                info.set(0, ex.readSampleData(buf, 0), t - first, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(track, buf, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            muxer.release()
            ex.release()
        }
    }
}

/**
 * Title, author and chapter marks added to an MP4 the platform muxer wrote. Tags are iTunes `ilst` items, which every audiobook player reads; chapters are the Nero `chpl` list, which
 * Audiobookshelf, VLC, ffmpeg and most Android players read.
 */
object Mp4Tags {
    fun add(file: File, title: String, author: String, chapters: List<Span>, album: String = title, comment: String = "") {
        val udta = box("udta", (if (chapters.isNotEmpty()) chpl(chapters) else ByteArray(0)) + meta(title, author, album, comment))
        val tmp = File(file.path + ".tmp")
        RandomAccessFile(file, "r").use { src ->
            val (moovAt, moovSize, mdatAfter) = findMoov(src)
            val moov = ByteArray(moovSize).also { src.seek(moovAt); src.readFully(it) }
            // The muxer writes moov before the audio, so a longer moov moves every sample: the chunk offsets move with it.
            val patched = (moov + udta).also { ByteBuffer.wrap(it).apply { putInt(0, it.size); if (mdatAfter) shiftOffsets(this, 8, moovSize, udta.size) } }
            tmp.outputStream().buffered().use { out ->
                src.seek(0)
                copyBytes(src, out, moovAt)
                out.write(patched)
                src.seek(moovAt + moovSize)
                copyBytes(src, out, src.length() - moovAt - moovSize)
            }
        }
        check(tmp.renameTo(file)) { "Could not replace ${file.name}" }
    }

    private fun copyBytes(src: RandomAccessFile, out: java.io.OutputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            check(n > 0) { "MP4 ended early" }
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** Where the `moov` box starts, its size, and whether the audio (`mdat`) comes after it. */
    private fun findMoov(f: RandomAccessFile): Triple<Long, Int, Boolean> {
        var at = 0L
        var moov = -1L
        var moovSize = 0L
        var mdat = -1L
        while (at + 8 <= f.length()) {
            f.seek(at)
            var size = f.readInt().toLong() and 0xFFFFFFFFL
            val name = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
            if (size == 1L) size = f.readLong() // The muxer gives a large mdat a 64-bit size.
            require(size >= 8) { "MP4 box $name has an unsupported size" }
            if (name == "moov") { moov = at; moovSize = size }
            if (name == "mdat") mdat = at
            at += size
        }
        require(moov >= 0 && moovSize < Int.MAX_VALUE) { "MP4 has no usable moov box" }
        return Triple(moov, moovSize.toInt(), mdat > moov)
    }

    /** Adds [delta] to every sample-chunk offset (`stco`, `co64`) in the boxes between [from] and [to]. */
    private fun shiftOffsets(b: ByteBuffer, from: Int, to: Int, delta: Int) {
        var at = from
        while (at + 8 <= to) {
            val size = b.getInt(at)
            when (String(b.array(), at + 4, 4, Charsets.ISO_8859_1)) {
                "trak", "mdia", "minf", "stbl" -> shiftOffsets(b, at + 8, at + size, delta)
                "stco" -> for (i in 0 until b.getInt(at + 12)) b.putInt(at + 16 + 4 * i, b.getInt(at + 16 + 4 * i) + delta)
                "co64" -> for (i in 0 until b.getInt(at + 12)) b.putLong(at + 16 + 8 * i, b.getLong(at + 16 + 8 * i) + delta)
            }
            at += size
        }
    }

    private fun box(type: String, body: ByteArray): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeInt(8 + body.size); writeBytes(type); write(body) }
    }.toByteArray()

    private fun chpl(chapters: List<Span>): ByteArray = ByteArrayOutputStream().also { b ->
        DataOutputStream(b).apply {
            writeInt(0x01000000) // Version 1, no flags.
            writeInt(0)
            writeByte(chapters.size)
            for (c in chapters) {
                writeLong(c.start * 10_000) // Units of 100 ns.
                val name = c.title.toByteArray().let { if (it.size > 255) it.copyOf(255) else it }
                writeByte(name.size)
                write(name)
            }
        }
    }.toByteArray().let { box("chpl", it) }

    private fun meta(title: String, author: String, album: String, comment: String): ByteArray {
        fun text(type: String, s: String) = box(type, box("data", ByteBuffer.allocate(8).putInt(1).putInt(0).array() + s.toByteArray()))
        val items = text("©nam", title) + text("©alb", album) + (if (author.isNotEmpty()) text("©ART", author) else ByteArray(0)) +
            (if (comment.isNotEmpty()) text("©cmt", comment) else ByteArray(0)) +
            // stik 2 marks the file as an audiobook; its data is a one-byte integer (type 21).
            box("stik", box("data", ByteBuffer.allocate(9).putInt(21).putInt(0).put(2).array()))
        val hdlr = box("hdlr", ByteBuffer.allocate(25).putInt(0).putInt(0).put("mdir".toByteArray()).put(ByteArray(13)).array())
        return box("meta", ByteBuffer.allocate(4).putInt(0).array() + hdlr + box("ilst", items))
    }
}
