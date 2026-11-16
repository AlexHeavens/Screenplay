/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Measures the OCR path stage by stage (render, ML Kit, [Layout]) for docs/ocr-analysis.md. Not a check: it is skipped
 * unless run with `-e ocrBench true`, so neither the smoke set nor the full suite pays for it.
 *
 * Push PDFs to the app's external files directory, sub-directory `ocr-bench`, run, then pull `ocr-bench-out`:
 * one JSON file per PDF, width and mode, with each page's timings, ML Kit's raw lines and Layout's paragraphs.
 * `-e widths 1000,1600,2400` and `-e modes argb,gray` pick the sweep (defaults: 1600, argb). tools/ocr-bench/README.md
 * has the full commands and the desktop scorer.
 */
@RunWith(AndroidJUnit4::class)
class OcrBenchmarkTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test fun stages() {
        assumeTrue("run with -e ocrBench true", args.getString("ocrBench") == "true")
        val input = File(ctx.getExternalFilesDir(null), "ocr-bench")
        val out = File(ctx.getExternalFilesDir(null), "ocr-bench-out").apply { mkdirs() }
        val widths = (args.getString("widths") ?: "1600").split(",").map { it.trim().toInt() }
        val modes = (args.getString("modes") ?: "argb").split(",").map { it.trim() }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            // One warm-up page so model loading is not charged to the first case.
            input.listFiles { f -> f.extension == "pdf" }!!.sorted().firstOrNull()?.let { measure(it, 1000, "argb", recognizer, warmUp = true) }
            for (pdf in input.listFiles { f -> f.extension == "pdf" }!!.sorted()) for (w in widths) for (m in modes) {
                val result = measure(pdf, w, m, recognizer)
                File(out, "${pdf.nameWithoutExtension}-$w-$m.json").writeText(result.toString(1))
                Log.i(TAG, "${pdf.name} $w $m ${result.getJSONObject("totals")}")
            }
            // The production path end to end, pages in parallel, for throughput.
            for (pdf in input.listFiles { f -> f.extension == "pdf" }!!.sorted()) {
                val t = SystemClock.elapsedRealtime()
                val n = runBlocking { var total = 0; PdfText.ocr(pdf) { _, all -> total = all }; total }
                val ms = SystemClock.elapsedRealtime() - t
                File(out, "${pdf.nameWithoutExtension}-production.json").writeText(
                    JSONObject().put("pages", n).put("wall_ms", ms).put("cores", Runtime.getRuntime().availableProcessors()).toString(1),
                )
                Log.i(TAG, "${pdf.name} production $n pages in $ms ms")
            }
        } finally {
            recognizer.close()
        }
    }

    private fun measure(pdf: File, width: Int, mode: String, recognizer: com.google.mlkit.vision.text.TextRecognizer, warmUp: Boolean = false): JSONObject {
        val pages = JSONArray()
        var render = 0L; var recognise = 0L; var layout = 0L
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (p in 0 until if (warmUp) 1 else renderer.pageCount) {
                    var t = SystemClock.elapsedRealtimeNanos()
                    val page = renderer.openPage(p)
                    val h = width * page.height / page.width
                    var bitmap = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    if (mode == "gray") bitmap = gray(bitmap)
                    val renderNs = SystemClock.elapsedRealtimeNanos() - t
                    t = SystemClock.elapsedRealtimeNanos()
                    val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                    val recogniseNs = SystemClock.elapsedRealtimeNanos() - t
                    t = SystemClock.elapsedRealtimeNanos()
                    val blocks = text.textBlocks.map { b -> b.lines.mapNotNull { l -> l.boundingBox?.let { OcrLine(it.top, it.bottom, l.text) } } }
                        .filter { it.isNotEmpty() }
                    val paragraphs = Layout.paragraphs(blocks, h)
                    val layoutNs = SystemClock.elapsedRealtimeNanos() - t
                    bitmap.recycle()
                    render += renderNs; recognise += recogniseNs; layout += layoutNs
                    pages.put(
                        JSONObject().put("page", p).put("height", h).put("render_ms", renderNs / 1e6).put("recognise_ms", recogniseNs / 1e6)
                            .put("layout_ms", layoutNs / 1e6).put("raw", JSONArray(blocks.map { b -> b.joinToString("\n") { it.text } }))
                            .put("layout", JSONArray(paragraphs)),
                    )
                }
            }
        }
        val n = pages.length().coerceAtLeast(1)
        return JSONObject().put("pdf", pdf.name).put("width", width).put("mode", mode).put("pages", pages)
            .put("totals", JSONObject().put("pages", n).put("render_ms", render / 1e6 / n).put("recognise_ms", recognise / 1e6 / n).put("layout_ms", layout / 1e6 / n))
    }

    private fun gray(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
        src.recycle()
        return out
    }

    private companion object {
        const val TAG = "OcrBenchmarkTest"
    }
}
