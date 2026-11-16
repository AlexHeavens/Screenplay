/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads a PDF by rendering each page and running OCR on it, so scanned and text PDFs take one path.
 * [Layout] decides which blocks of a page are read.
 */
object PdfText {
    private const val WIDTH = 1600
    private val PARALLEL = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    /** Every page's paragraphs, cleaned and with paragraphs cut by a page break joined. */
    suspend fun read(pdf: File, onPage: (done: Int, total: Int) -> Unit): List<String> = Text.fromOcr(ocr(pdf, onPage))

    /** Every page's paragraphs as OCR and [Layout] return them, before any cleaning. */
    suspend fun ocr(pdf: File, onPage: (done: Int, total: Int) -> Unit): List<String> {
        val paragraphs = ArrayList<String>()
        pages(pdf, footnotes = false, onPage) { paragraphs += it.paragraphs }
        return paragraphs
    }

    /**
     * Reads the pages and hands each page's paragraphs to [onText], in page order, as soon as that page and every page
     * before it are done, so a reader need not wait for the last page. [onPage] counts pages as each finishes.
     * At most a few pages are rendered or waiting at a time, whatever the length of the book. With [footnotes], each page's footnotes come
     * with its paragraphs ([Layout.page]); without, they are dropped or read as they always were.
     */
    suspend fun pages(pdf: File, footnotes: Boolean, onPage: (done: Int, total: Int) -> Unit, onText: suspend (PageText) -> Unit) =
        withContext(Dispatchers.Default) {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    val renderer = PdfRenderer(fd)
                    try {
                        // PdfRenderer opens one page at a time, so pages render in turn while up to PARALLEL are recognised at once.
                        coroutineScope {
                            val gate = Semaphore(PARALLEL)
                            val done = AtomicInteger()
                            val total = renderer.pageCount
                            // Pages wait here in order; when [onText] is slow, the renderer waits too.
                            val inOrder = Channel<Deferred<PageText>>(PARALLEL)
                            launch {
                                for (p in 0 until total) {
                                    gate.acquire()
                                    val page = renderer.openPage(p)
                                    val h = WIDTH * page.height / page.width
                                    val bitmap = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    page.close()
                                    inOrder.send(async {
                                        try {
                                            Engines.beforeRecognise(p, total)
                                            Engines.recognisePage?.let { return@async it(p).let { text -> (if (footnotes) Footnotes.split(text) else PageText(text)).copy(lines = text) } }
                                            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                                            val blocks = text.textBlocks.map { block ->
                                                block.lines.mapNotNull { l -> l.boundingBox?.let { OcrLine(it.top, it.bottom, l.text, it.left) } }
                                            }.filter { it.isNotEmpty() }
                                            Layout.page(blocks, h, footnotes)
                                        } finally {
                                            bitmap.recycle()
                                            gate.release()
                                            synchronized(this@PdfText) { onPage(done.incrementAndGet(), total) }
                                        }
                                    })
                                }
                                inOrder.close()
                            }
                            for (page in inOrder) onText(page.await())
                        }
                    } finally {
                        renderer.close()
                    }
                }
            } finally {
                recognizer.close()
            }
        }
}
