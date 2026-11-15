/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

object Fixtures {
    const val TITLE = "fixture"
    val sentences = listOf(
        "The quick brown fox jumps over the lazy dog.",
        "Pack my box with five dozen liquor jugs.",
        "How vexingly quick daft zebras jump.",
    )

    /** A PDF of [pages] pages of big black text on white: the shape of a clean scan, without needing a scanner. */
    fun pdf(ctx: Context, pages: Int = 1): Uri {
        val doc = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 20f; isAntiAlias = true }
        repeat(pages) { n ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n + 1).create())
            sentences.forEachIndexed { i, s -> page.canvas.drawText(s, 40f, 200f + i * 120f, paint) }
            doc.finishPage(page)
        }
        val file = File(ctx.cacheDir, "$TITLE.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    }

    /**
     * Writes a READY book the way an import leaves it (source PDF, audio, transcript, notes, details), without running the import,
     * and refreshes the shelf. [audio] is stored as is: pass real AAC when the book will be played, see [seedSpokenBook].
     */
    fun seedBook(
        ctx: Context,
        id: String,
        title: String,
        segments: List<Segment>,
        audio: ByteArray,
        author: String = "",
        contents: List<String> = emptyList(),
        rate: Int = 0,
    ): String {
        val dir = Library.dir(ctx, id).apply { mkdirs() }
        File(dir, Files.PDF).writeBytes(ctx.contentResolver.openInputStream(pdf(ctx))!!.use { it.readBytes() })
        File(dir, Files.AUDIO).writeBytes(audio)
        File(dir, Files.NOTES).writeText("[]")
        File(dir, Files.META).writeText(
            JSONObject().put("title", title).put("author", author).put("status", Status.READY.name).put("duration", segments.last().end)
                .put("created", 1L).put("rate", rate).put("contents", JSONArray(contents)).toString(),
        )
        Library.saveSegments(ctx, id, segments)
        Library.refresh(ctx)
        return id
    }

    /**
     * A READY book of [sentences] with audio the real player can play: each sentence is spoken by [FakeSynthesizer] and encoded
     * by the production [Speech.render] and [AacEncoder], as in an import, but without OCR. Its chapters are detected and stored too.
 * The id is random, as an import's is: a playback service left over from an earlier test must not recognise the book.
     */
    fun seedSpokenBook(ctx: Context, id: String = java.util.UUID.randomUUID().toString().take(8), title: String = TITLE, sentences: List<String> = this.sentences): String {
        val scratch = File(ctx.cacheDir, "seed-audio").apply { deleteRecursively(); mkdirs() }
        val pcm = File(scratch, "audio.pcm")
        val (segments, rate) = runBlocking { Speech.render(listOf(FakeSynthesizer()), sentences, File(scratch, "sentence.wav"), pcm) {} }
        val m4a = File(scratch, Files.AUDIO)
        AacEncoder.encode(pcm, rate, m4a)
        seedBook(ctx, id, title, segments, m4a.readBytes(), rate = rate)
        Library.detectChapters(ctx, id)
        scratch.deleteRecursively()
        return id
    }
}

/**
 * Real OCR text without real OCR: a fixture's saved OCR paragraphs (`ocr/<name>.ocr.json`) dealt out in order over [pages] pages,
 * for [Engines.recognisePage].
 */
fun cannedOcr(assets: android.content.res.AssetManager, name: String, pages: Int): (Int) -> List<String> {
    val all = OcrFixtures.parseDump(assets.open("ocr/$name.ocr.json").bufferedReader().readText())
    return { page -> all.subList(all.size * page / pages, all.size * (page + 1) / pages) }
}

/** Stands in for the speech engine, which the emulator image lacks: a tone [msPerWord] ms long per word. */
class FakeSynthesizer(private val msPerWord: Int = 400) : Synthesizer {
    override suspend fun speak(text: String, wav: File) {
        val rate = 16000
        val n = rate * msPerWord / 1000 * text.split(" ").size
        val pcm = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (sin(2 * PI * 440 * i / rate) * 3000).toInt().toShort().toInt()
            pcm[2 * i] = v.toByte()
            pcm[2 * i + 1] = (v shr 8).toByte()
        }
        Wav.write(wav, rate, pcm)
    }

    override fun close() = Unit
}
