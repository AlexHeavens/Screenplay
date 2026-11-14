/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * Exports a short three-chapter book through the Export menu: the real importer, AAC encoder, muxer and share sheet, with a fake
 * speech engine and fixed page text (the emulator has neither a voice nor time for OCR). The file is read back with the
 * platform's own MP4 readers and with a separate chapter-list parser.
 */
@RunWith(AndroidJUnit4::class)
class ExportTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun bookWithThreeChapters() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Engines.synthesizer = { FakeSynthesizer() }
        Engines.recognisePage = {
            listOf("Chapter 1", "The quick brown fox jumps over the lazy dog.", "Pack my box with five dozen liquor jugs.",
                "Chapter 2", "How vexingly quick daft zebras jump.", "The five boxing wizards jump quickly today.",
                "Chapter 3", "Sphinx of black quartz, judge my vow.", "Two driven jocks help fax my big quiz.")
        }
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(Fixtures.pdf(ctx), "application/pdf"),
        )
        waitFor(hasContentDescription("Listened"))
        // The ISBN lookup stores the author in the book's metadata.
        val meta = File(Library.dir(ctx, Library.books.value.single().id), Files.META)
        meta.writeText(JSONObject(meta.readText()).put("author", "Ada Author").toString())
        Library.refresh(ctx)
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
    }

    @After
    fun restore() {
        Engines.recognisePage = null
        scenario?.close()
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(120_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun sharedFiles(item: String): List<File> {
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            compose.onNodeWithTag("export").performClick()
            compose.onNodeWithTag(item).performClick()
            compose.waitUntil(60_000) { Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER } }
            @Suppress("DEPRECATION")
            val send = Intents.getIntents().first { it.action == Intent.ACTION_CHOOSER }.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            @Suppress("DEPRECATION")
            val uris = if (send.action == Intent.ACTION_SEND) listOf(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!)
            else send.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!.toList()
            // What the receiving app would read, through the share's own grant.
            return uris.mapIndexed { i, u -> File(ctx.cacheDir, "got$i-${u.lastPathSegment!!.substringAfterLast('/')}").also { f -> ctx.contentResolver.openInputStream(u)!!.use { it.copyTo(f.outputStream()) } } }
        } finally {
            Intents.release()
        }
    }

    /** The `metadata.json` the export shares beside the audio, with the audio files left. */
    private fun split(files: List<File>): Pair<List<File>, JSONObject> {
        val json = files.single { it.name.endsWith("-metadata.json") }
        return files - json to JSONObject(json.readText())
    }

    private fun metadata(f: File) = MediaMetadataRetriever().also { it.setDataSource(f.path) }

    /** The Nero chapter list, parsed independently of the writer: (start ms, title). */
    private fun chapterList(f: File): List<Pair<Long, String>> {
        val bytes = f.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("chpl")
        assertTrue("no chpl box", at > 0)
        val b = java.nio.ByteBuffer.wrap(bytes)
        b.position(at + 4 + 4 + 4) // Past the name, the version and flags, the reserved word.
        return List(b.get().toInt() and 0xFF) {
            val start = b.getLong() / 10_000
            val name = ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }
            start to String(name)
        }
    }

    @Smoke @Test
    fun exportsOneM4bWithChaptersTitleAndAuthor() {
        val segments = Library.segments(ctx, Library.books.value.single().id)
        val expected = Chapters.find(segments)
        assertEquals("fixture should hold three chapters", listOf("Chapter 1", "Chapter 2", "Chapter 3"), expected.map { it.title })

        val (audio, json) = split(sharedFiles("export-m4b"))
        val file = audio.single()
        assertEquals("0.0.1", json.getString("export_format"))
        assertEquals(expected.map { it.title }, json.getJSONArray("chapters").let { a -> List(a.length()) { a.getJSONObject(it).getString("title") } })

        val ex = MediaExtractor().also { it.setDataSource(file.path) }
        assertEquals(1, ex.trackCount)
        assertEquals("audio/mp4a-latm", ex.getTrackFormat(0).getString("mime"))
        ex.release()
        val meta = metadata(file)
        assertEquals(Fixtures.TITLE, meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
        assertEquals("Ada Author", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
        val total = segments.last().end
        val duration = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        assertTrue("audio is $duration ms, transcript says $total ms", kotlin.math.abs(duration - total) < 200)

        val chapters = chapterList(file)
        assertEquals(expected.map { it.title }, chapters.map { it.second })
        assertEquals(expected.map { it.start }, chapters.map { it.first })
    }

    @Test
    fun exportsOneM4aPerChapterWithMatchingDurations() {
        val segments = Library.segments(ctx, Library.books.value.single().id)
        val spans = Export.spans(segments, Chapters.find(segments), Fixtures.TITLE)
        val (files, json) = split(sharedFiles("export-chapters"))
        assertEquals("0.0.1", json.getString("export_format"))
        assertEquals(spans.size, files.size)
        files.zip(spans).forEach { (f, span) ->
            val d = metadata(f).extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("${span.title}: ${d} ms vs ${span.end - span.start} ms", kotlin.math.abs(d - (span.end - span.start)) < 100)
            assertEquals(span.title, metadata(f).extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
        }
    }

    @Test
    fun exportWaitsUntilTheBookIsReady() {
        val id = Library.books.value.single().id
        Library.update(ctx, id) { it.copy(status = Status.SPEAKING) }
        File(Library.dir(ctx, id), Files.AUDIO).delete()
        val failure = runCatching { Export.m4b(ctx, id) }.exceptionOrNull()
        assertTrue("$failure", failure?.message?.contains("not finished") == true)
    }
}
