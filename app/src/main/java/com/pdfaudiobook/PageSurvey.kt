/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Reads the first pages of a PDF with OCR and keeps every line with its box, for [ChapterEstimator]. Pages count from 1. */
object PageSurvey {
    private const val WIDTH = 1600

    suspend fun read(pdf: File, count: Int = ChapterEstimator.PAGES, from: Int = 1): List<OcrPage> = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val renderer = PdfRenderer(fd)
                try {
                    (from..minOf(renderer.pageCount, from + count - 1)).map { n ->
                        // Stopping the book (deleted, or put behind another) takes effect between pages, not after the whole survey.
                        currentCoroutineContext().ensureActive()
                        val page = renderer.openPage(n - 1)
                        val h = WIDTH * page.height / page.width
                        val bitmap = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                            OcrPage(n, h, text.textBlocks.map { b ->
                                b.lines.mapNotNull { l -> l.boundingBox?.let { OcrLine(it.top, it.bottom, l.text, it.left) } }
                            }.filter { it.isNotEmpty() })
                        } finally {
                            bitmap.recycle()
                        }
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
