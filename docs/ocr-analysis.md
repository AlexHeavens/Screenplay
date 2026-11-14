# OCR analysis: is ML Kit at 1600 px the best balance?

*Claude Code authored*

## TL;DR

- **Yes, for scanned Latin-script pages.** On these 15 test cases, the bundled ML Kit Latin recogniser at 1600 px loses the fewest body words of the engines that can run on the phone. It loses 0.9% raw and 1.3% after `Layout`. Tesseract loses 1.8–1.9% and RapidOCR (PP-OCRv6) loses 12.2%. 1600 px is better than 1000 px (2.0%) and 2400 px (1.4%). Recognition time changes by only 5–10% across these widths.
- **The largest gain is to skip OCR when the PDF has a real text layer.** Text extraction from a text-layer PDF gave 0% error and took 0.02 s per page. ML Kit took about 13 s per page on the emulator. Android exposes this text through `PdfRenderer.Page.getTextContents()` (API 35) and `PdfRendererPreV` (API 30–34 with the SDK extension). It adds nothing to the APK, but it needs `compileSdk` 35.
- **Most of the errors that a listener hears come from layout, not from recognition.** The examples are a contents page read aloud, margin sidenotes read aloud, a German verse dropped by `Layout`, and footnote markers read as numbers. A different engine does not fix these.
- **Two gaps remain.** No ML Kit recogniser reads Cyrillic or Greek; Tesseract with the `rus` model read the Russian page with 0% error. Phone timings were not measured.

## Repeat or extend the runs

[`tools/ocr-bench`](../tools/ocr-bench/README.md) has the corpus, one entry script (`run.sh`) and the stored results. The baseline is [`results/mlkit-1600-argb.json`](../tools/ocr-bench/results/mlkit-1600-argb.json), the current production path. Every run in this document is stored in [`results/`](../tools/ocr-bench/results/), so a new experiment is scored against the same pages and the same metric:

```bash
tools/ocr-bench/run.sh emulator 1600 argb
tools/ocr-bench/run.sh score tools/ocr-bench/results/mlkit-1600-argb.json tools/ocr-bench/results/<new-run>.json
```

## Answer and recommendations

| # | Recommendation | Evidence | Effort | Risk | What would change it |
|---|---|---|---|---|---|
| 1 | Add a text-layer fast path. Use `getTextContents()` on API 35+, `PdfRendererPreV` on API 30–34, and OCR on API 26–29. Use the text layer only when it passes a per-page check (enough words, mostly letters, most words in a word list). Otherwise, run OCR. | The text-layer case: 0.0% error and 0.02 s per page from the text layer. ML Kit gave 0.5% and 13.3 s per page on the same page. | Medium: `compileSdk` 35, a mapper from text-content bounds to `Layout`'s line model, the per-page check, and a fixture PDF with a text layer. | Low for scans, which have no text layer and so keep the current path. Medium for PDFs with a poor hidden OCR layer (Internet Archive PDFs have one); the per-page check exists for this case. | Evidence that most user PDFs are image-only scans would make this gain small. |
| 2 | Keep ML Kit bundled Latin at 1600 px. | The lowest body error of the on-device engines (table 1). 1600 px is the best of the three widths (table 3). | None | None | Phone timings show that width costs much more on arm64 than on the emulator. A larger corpus could show a different best width. |
| 3 | Fix the layout errors that a listener hears: contents and index pages, margin sidenotes, and footnote markers inside the body text. Also check why `Layout` drops the German verse in `james`. | `varieties-preface`: 273 inserted words after `Layout` (a contents page). `gibbon`: sidenotes read aloud. `james`: 2.1% raw, 7.6% after `Layout`. | Small to medium for each item | Low, with the existing fixtures as regression tests | – |
| 4 | Optional: add Tesseract as a second engine for non-Latin scripts (Cyrillic, Greek) only. | `russian`: 100% loss with every Latin model. 0% with Tesseract `rus`, at 1.6 s per page. | Large: native library, about 4 MB for each `tessdata_fast` language, and a language choice in the UI | Medium: APK size, and Tesseract drops whole lines on poor scans | No demand for non-Latin books. |
| 5 | Do not adopt RapidOCR, PaddleOCR or `tessdata_best`. | RapidOCR: 12.2% total loss, and it mixes the two columns. `tessdata_best`: no better than `fast` (1.8% against 1.9%), 3.5 times slower, and 15.4 MB. | – | – | A newer PP-OCR model that reads multi-column pages in order. |

## Method

**Ground truth.** The seven real fixtures in `app/src/androidTest/assets/ocr` are scans from old printed books. Their truth files are the proofread Wikisource transcriptions of the body text. The truth has no running heads, page numbers, sidenotes or footnotes, so a correct engine-plus-layout output inserts no words. The eight synthetic cases are in `tools/ocr-bench/corpus/`. They were built once with `tools/ocr-bench/synth.py`. It typesets text from two Project Gutenberg books (#1342 and #17989) and a Russian paragraph with DejaVu Serif on a 6 × 9 inch page. The truth is the text that the script typeset.

| Synthetic case | Words | What it tests |
|---|---|---|
| clean-scan | 392 | 300 dpi grey JPEG, quality 85 |
| poor-scan | 416 | Rotation 2°, blur, noise, low contrast, 150 dpi, JPEG quality 25 |
| text-layer | 392 | The clean-scan text as vector text (a real text layer) |
| small-print | 1040 | 7 pt type at 300 dpi |
| headers-footnotes | 321 | A running head, a page number and an 8 pt footnote. The truth has the body only. |
| two-column | 954 | Two 10 pt columns on a letter page |
| french | 378 | Accented Latin (Monte-Cristo) |
| russian | 183 | Cyrillic |

**Metrics.** `body_err` is (substitutions + deletions) / truth words, on lower-case words without punctuation. It is the share of body text that the listener does not hear correctly. Insertions are counted separately, because a raw engine also reads running heads and footnotes, which `Layout` removes. WER and CER are in the full output below. Soft hyphens at line ends are joined the same way as `Layout` joins them. Em dashes become spaces. `tools/ocr-bench/metrics.py` has the details.

**ML Kit runs.** `OcrBenchmarkTest` ran on the shared x86_64 emulator (`emulator-5580`, 2 cores, KVM). For each page it records the render time, the recognition time, the `Layout` time, the raw ML Kit lines and the `Layout` paragraphs. It also times the production `PdfText.ocr` path. The test runs only with `-e ocrBench true` and is not in the smoke set.

**Desktop runs.** Tesseract 5.5.4 (through `tesserocr`, one thread), RapidOCR 3.10.0 (PP-OCRv6 small, ONNX Runtime 1.32) and PyMuPDF text extraction ran on the same host as the emulator. The pages were rendered to grey at a fixed width. Other sessions used the host during the runs, so all timings are rough. Desktop timings and emulator timings are comparable only roughly, because the CPU is the same but the thread counts and builds are different. **No timing here is a phone timing.**

## Results

### 1. Body text lost, at 1600 px (extract from the full output)

| case | ML Kit + Layout | ML Kit raw | Tesseract fast | Tesseract best | RapidOCR | Text layer |
|---|---|---|---|---|---|---|
| origin | 2.1% | 2.1% | 0.1% | 0.0% | 2.6% | none |
| gibbon | 0.8% | 0.8% | 0.3% | 0.2% | 0.5% | none |
| james | 7.6% | 2.1% | 2.1% | 1.3% | 2.3% | none |
| varieties-preface | 3.9% | 3.3% | 0.9% | 1.1% | 4.8% | none |
| clean-scan | 0.3% | 0.3% | 0.5% | 0.0% | 7.9% | none |
| poor-scan | 1.4% | 1.4% | 8.6% | 8.6% | 6.7% | none |
| text-layer | 0.5% | 0.5% | 0.0% | 0.0% | 6.9% | **0.0%** |
| small-print | 0.0% | 0.0% | 0.2% | 0.0% | 0.0% | none |
| headers-footnotes | 0.0% | 0.0% | 0.3% | 0.0% | 2.8% | none |
| two-column | 0.0% | 0.0% | 0.2% | 0.0% | 69.5% | none |
| french | 1.9% | 1.9% | 9.8% (`fra`: 0.0%) | 10.3% | 0.0% | none |
| russian | 100% | 100% | 100% (`rus`: 0.0%) | 100% | 100% | none |
| **Total, word-weighted, without text-layer and russian** | **1.3%** | **0.9%** | **1.9%** | **1.8%** | **12.2%** | – |

"none" means that the PDF has no text layer. The `fra` and `rus` values come from separate runs with those Tesseract models.

### 2. Words inserted (read aloud but not in the body text)

| case | ML Kit + Layout | ML Kit raw | Tesseract fast |
|---|---|---|---|
| origin (includes index pages) | 3 | 743 | 739 |
| gibbon (sidenotes, footnotes) | 12 | 226 | 224 |
| varieties-preface (contents page) | 273 | 290 | 281 |
| headers-footnotes | 0 | 20 | 20 |

`Layout` removes almost all running heads, page numbers, footnotes and index pages. It does not remove a contents page or the margin sidenotes in `gibbon`.

### 3. Render width, ML Kit raw against Tesseract fast (total body text lost)

| Width | ML Kit raw | ML Kit, median s/page (emulator) | Tesseract fast | Tesseract fast, median s/page (desktop, 1 thread) |
|---|---|---|---|---|
| 1000 px | 2.0% | 12.44 | 1.7% | 2.07 |
| 1600 px | **0.9%** | 12.97 | 1.9% | 2.70 |
| 2400 px | 1.4% | 13.79 | 1.6% | 3.31 |

At 1000 px, ML Kit loses more text on `small-print` (2.1%) and `two-column` (3.9%). This agrees with the ML Kit guidance that characters should be at least 16 × 16 px ([ML Kit text recognition v2, Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)). At 2400 px, it gains nothing: the guidance says that characters larger than 24 × 24 px give no accuracy benefit.

### 4. Where the time goes (emulator, 2 cores)

| Stage | Median per page |
|---|---|
| Render (`PdfRenderer`, 1600 px, ARGB_8888) | 0.09 s |
| ML Kit recognition | 12.86 s |
| `Layout.paragraphs` | 4.3 ms |

Recognition takes more than 99% of the time. Production `PdfText.ocr` took 8.9–31.3 s per page; dense pages took the most time (`small-print` 31.3 s, `two-column` 28.0 s). The pages run in parallel, but ML Kit already uses both emulator cores, so more parallelism gives little. A sibling measurement found the same: `PARALLEL` 1, 2 and 3 took 81.7, 73.7 and 70.8 s. On a phone with 8 cores, parallelism can help more. This was not measured.

**Caveat.** ML Kit took about 5 times longer per page on the emulator than single-threaded Tesseract took on the desktop. This does not show that Tesseract is faster on a phone. The ML Kit x86_64 library possibly does not have the optimisations of the arm64 library, and the host was under load. Only a timing on a phone can settle this.

## What a listener hears

**Errors that change the speech** (from `tools/ocr-bench/diff.py`):

- *Layout:* a contents page is read aloud (`varieties-preface`). Margin sidenotes are read aloud (`gibbon`: "Of the people"). Footnote markers are read as numbers ("8", "4" in `james`). `Layout` drops a German verse in `james` (2.1% → 7.6%).
- *ML Kit recognition:* lost ligatures ("efects", "dificulty", "refections"), rn and m confused ("conmprehensible", "armnateur"), lost first letters on a poor scan ("derstanding", "nd"), and a hyphenated word split across a line break ("super" … "lucent").
- *Tesseract:* drops whole lines on the poor scan ("unconcern which was not in the least altered by her communication…"). It also moves a paragraph that starts with a drop cap to the wrong place (`miti`). Without the `fra` model, it misreads accents ("aussité6t").
- *RapidOCR:* reads across the two columns, so each line mixes both columns.

**Cosmetic errors** (a TTS voice reads them the same way): "l'ile" for "l'île", an em dash read as a hyphen, and a straight quote for a curly quote.

## Alternatives

| Option | Accuracy (body lost) | Speed | Size added to APK | Licence | Offline | Scripts | Integration |
|---|---|---|---|---|---|---|---|
| ML Kit bundled Latin, 1600 px (current) | 0.9% raw | 12.9 s/page (emulator) | 0 (already 12.6 MB for each ABI: `libmlkit_google_ocr_pipeline.so` 11.1 MB arm64 + 1.5 MB models) | Google ML Kit terms (proprietary, free) | Yes | Latin only in this recogniser. Chinese, Devanagari, Japanese and Korean are separate recognisers. No Cyrillic or Greek. | – |
| ML Kit unbundled (Play services) | Same model (not measured) | Not measured | About 260 KB, but the model downloads on first use | Same | After the download | Same | Small. It needs Google Play services. |
| Text layer: `getTextContents()` / `PdfRendererPreV` | 0.0% when a real layer exists | 0.02 s/page (desktop, PyMuPDF; the Android API was not timed) | 0 | Android platform | Yes | All | Medium. `compileSdk` 35. API 30+ only. |
| Text layer: PdfBox-Android | Not measured | Not measured | Several MB (not measured) | Apache-2.0 | Yes | All | Medium |
| Text layer: PDFium | Not measured | Not measured | Native library (not measured) | BSD-3-Clause / Apache-2.0 | Yes | All | Large |
| Text layer: MuPDF | – | – | – | **AGPL or commercial: not allowed** | – | – | – |
| Tesseract 5, `tessdata_fast` (Tesseract4Android) | 1.9% | 2.7 s/page (desktop, 1 thread) | Native library (not measured) + 4.1 MB `eng` | Apache-2.0 (Leptonica and the image libraries have their own licences) | Yes | More than 100, with one model for each language | Large |
| Tesseract 5, `tessdata_best` | 1.8% | 9.5 s/page (desktop) | + 15.4 MB `eng` | Apache-2.0 | Yes | As above | Large |
| RapidOCR / PP-OCRv6 small (ONNX) | 12.2% | 13.6 s/page (desktop, ONNX Runtime with many threads) | 31.7 MB of models. The app already has ONNX Runtime for the voice, but the ORT AAR possibly adds a second copy. | Apache-2.0, MIT (ONNX Runtime) | Yes | Latin, Chinese, Japanese and others | Large |
| PaddleOCR / NCNN builds | Not measured. The same model family as RapidOCR. | Not measured | Not measured | Apache-2.0, BSD-3-Clause (NCNN) | Yes | As RapidOCR | Large |
| ML Kit document scanner | – | – | – | – | – | – | Not applicable: it captures pages with the camera, and it does not read PDFs. |

**Upper bound.** No desktop-only model (for example a large vision-language model) was run. On these Latin pages, the best on-device result (0.9% raw ML Kit) is mostly lost ligatures and confused letters, and most of what a listener hears comes from layout. A stronger recogniser therefore cannot remove much more.

**Literature.** No published benchmark compares ML Kit with Tesseract on scanned book pages. The comparisons found are small or old, so they are only weak evidence. Some are blog posts from the ML Kit v1 era ([Heartbeat](https://heartbeat.comet.ml/comparing-on-device-text-recognition-ocr-sdks-24f8a0423461), [Fritz](https://fritz.ai/choose-the-right-on-device-text-recognition-sdk-on-android/)), and one is a thesis ([CORE 516510868](https://core.ac.uk/download/516510868.pdf)). A receipt benchmark ranks Tesseract last (CER 33.6%) and PaddleOCR at 20.5% ([imagetotable.ai](https://imagetotable.ai/references/receipt-ocr-benchmark-results)), but receipts are not book pages. The PP-OCRv5 paper reports that its mobile model reads more than 370 characters per second on a Xeon CPU, and that v5 is slower than v4 ([arXiv 2603.24373](https://arxiv.org/html/2603.24373v1)). No published arm64 phone timings for RapidOCR were found.

## What could not be measured

- **Phone timings.** The task did not allow runs on Alex's phone. All ML Kit timings are from the x86_64 emulator with 2 cores, and they show only relative differences between widths.
- **Parallelism on a phone with 8 cores.**
- **`getTextContents()` on a device.** The emulator has API 34, and the app compiles against SDK 34.
- **Tesseract and RapidOCR on Android.** They ran on the desktop only.
- **Binarised or deskewed input to ML Kit.** Grey input was measured later (`results/mlkit-1600-gray.json`). It gave the same text as ARGB on every case (1.3% total) and took the same time (12.40 s against 12.36 s median per page in the same run), so it is not worth a change.
- **The size of the hidden OCR layer in real Internet Archive PDFs**, and how often user PDFs have a real text layer.

## Sources

- ML Kit text recognition v2 for Android: <https://developers.google.com/ml-kit/vision/text-recognition/v2/android>
- `PdfRenderer.Page.getTextContents()`: <https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page>
- `PdfRendererPreV`: <https://developer.android.com/reference/android/graphics/pdf/PdfRendererPreV>
- Android PDF viewer guide: <https://developer.android.com/media/grow/pdf-viewer>
- MuPDF licensing: <https://mupdf.com/r/Licensing>
- PDFium licence: <https://pdfium.googlesource.com/pdfium/+/refs/heads/master/LICENSE>
- PdfBox-Android: <https://github.com/TomRoush/PdfBox-Android>
- Tesseract4Android: <https://github.com/adaptech-cz/Tesseract4Android>
- Tesseract benchmarks: <https://tesseract-ocr.github.io/tessdoc/Benchmarks.html>
- PP-OCRv5: <https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html>
- PaddleOCR on Android: <https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/cross_platform/android_deployment.html>

The code paths that were analysed are [`PdfText.kt`](https://github.com/AlexHeavens/Screenplay/blob/bed37a41732b0f8ccd59833a2cd17b5d271ed5b0/app/src/main/java/com/pdfaudiobook/PdfText.kt), [`Layout.kt`](https://github.com/AlexHeavens/Screenplay/blob/bed37a41732b0f8ccd59833a2cd17b5d271ed5b0/app/src/main/java/com/pdfaudiobook/Layout.kt) and [`Text.kt`](https://github.com/AlexHeavens/Screenplay/blob/bed37a41732b0f8ccd59833a2cd17b5d271ed5b0/app/src/main/java/com/pdfaudiobook/Text.kt). The scripts and steps to repeat the runs are in [`tools/ocr-bench`](../tools/ocr-bench/README.md).

<details>
<summary>Full output of <code>report.py</code> (verbatim)</summary>

Command: `python -I report.py out/bench.jsonl emu/run1/ocr-bench-out app/src/androidTest/assets/ocr out/synth`. `report.py` was the first version of `score.py`. The same runs are stored in `tools/ocr-bench/results/`.

```

body_err by case (rows) and engine@width (columns)

| case | mlkit-saved-dump@ | mlkit-layout-argb@1600 | mlkit-raw-argb@1600 | tesseract-fast@1600 | tesseract-best@1600 | rapidocr-ppocr@1600 | text-layer@ |
|---|---|---|---|---|---|---|---|
| origin | 2.1% | 2.1% | 2.1% | 0.1% | 0.0% | 2.6% | 100.0% |
| gibbon | 0.8% | 0.8% | 0.8% | 0.3% | 0.2% | 0.5% | 100.0% |
| james | 7.6% | 7.6% | 2.1% | 2.1% | 1.3% | 2.3% | 100.0% |
| varieties-preface | 3.9% | 3.9% | 3.3% | 0.9% | 1.1% | 4.8% | 100.0% |
| clean-scan |  | 0.3% | 0.3% | 0.5% | 0.0% | 7.9% | 100.0% |
| poor-scan |  | 1.4% | 1.4% | 8.6% | 8.6% | 6.7% | 100.0% |
| text-layer |  | 0.5% | 0.5% | 0.0% | 0.0% | 6.9% | 0.0% |
| small-print |  | 0.0% | 0.0% | 0.2% | 0.0% | 0.0% | 100.0% |
| headers-footnotes |  | 0.0% | 0.0% | 0.3% | 0.0% | 2.8% | 100.0% |
| two-column |  | 0.0% | 0.0% | 0.2% | 0.0% | 69.5% | 100.0% |
| french |  | 1.9% | 1.9% | 9.8% | 10.3% | 0.0% | 100.0% |
| russian |  | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% |
| **word-weighted total** | 2.0% | 1.3% | 0.9% | 1.9% | 1.8% | 12.2% | 100.0% |

wer by case (rows) and engine@width (columns)

| case | mlkit-saved-dump@ | mlkit-layout-argb@1600 | mlkit-raw-argb@1600 | tesseract-fast@1600 | tesseract-best@1600 | rapidocr-ppocr@1600 | text-layer@ |
|---|---|---|---|---|---|---|---|
| origin | 2.6% | 2.6% | 108.1% | 105.6% | 105.4% | 108.1% | 100.0% |
| gibbon | 2.7% | 2.7% | 36.2% | 35.4% | 35.6% | 36.2% | 100.0% |
| james | 9.7% | 9.7% | 27.2% | 24.1% | 23.6% | 27.0% | 100.0% |
| varieties-preface | 46.3% | 46.3% | 48.4% | 44.6% | 44.3% | 47.7% | 100.0% |
| clean-scan |  | 0.3% | 0.3% | 0.5% | 0.0% | 7.9% | 100.0% |
| poor-scan |  | 1.4% | 1.4% | 8.6% | 8.6% | 6.7% | 100.0% |
| text-layer |  | 0.5% | 0.5% | 0.0% | 0.0% | 6.9% | 0.0% |
| small-print |  | 0.0% | 0.0% | 0.2% | 0.0% | 0.0% | 100.0% |
| headers-footnotes |  | 0.0% | 6.2% | 6.5% | 6.2% | 9.3% | 100.0% |
| two-column |  | 0.0% | 0.0% | 0.2% | 0.0% | 90.0% | 100.0% |
| french |  | 1.9% | 1.9% | 9.8% | 10.3% | 0.0% | 100.0% |
| russian |  | 100.0% | 100.0% | 100.5% | 101.1% | 100.0% | 100.0% |
| **word-weighted total** | 8.6% | 5.0% | 18.6% | 19.6% | 19.5% | 32.3% | 100.0% |

cer by case (rows) and engine@width (columns)

| case | mlkit-saved-dump@ | mlkit-layout-argb@1600 | mlkit-raw-argb@1600 | tesseract-fast@1600 | tesseract-best@1600 | rapidocr-ppocr@1600 | text-layer@ |
|---|---|---|---|---|---|---|---|
| origin | 0.025 | 0.025 | 1.193 | 1.189 | 1.191 | 1.198 | 1.000 |
| gibbon | 0.021 | 0.021 | 0.336 | 0.337 | 0.338 | 0.335 | 1.000 |
| james | 0.081 | 0.081 | 0.283 | 0.258 | 0.260 | 0.281 | 1.000 |
| varieties-preface | 0.446 | 0.446 | 0.482 | 0.462 | 0.462 | 0.487 | 1.000 |
| clean-scan |  | 0.001 | 0.001 | 0.000 | 0.000 | 0.057 | 1.000 |
| poor-scan |  | 0.004 | 0.004 | 0.091 | 0.091 | 0.043 | 1.000 |
| text-layer |  | 0.002 | 0.002 | 0.000 | 0.000 | 0.057 | 0.000 |
| small-print |  | 0.001 | 0.001 | 0.000 | 0.000 | 0.000 | 1.000 |
| headers-footnotes |  | 0.001 | 0.062 | 0.062 | 0.061 | 0.083 | 1.000 |
| two-column |  | 0.001 | 0.001 | 0.000 | 0.000 | 0.730 | 1.000 |
| french |  | 0.004 | 0.004 | 0.017 | 0.018 | 0.000 | 1.000 |
| russian |  | 0.881 | 0.881 | 0.894 | 0.921 | 0.945 | 1.000 |
| **word-weighted total** |  |  |  |  |  |  |  |

ins by case (rows) and engine@width (columns)

| case | mlkit-saved-dump@ | mlkit-layout-argb@1600 | mlkit-raw-argb@1600 | tesseract-fast@1600 | tesseract-best@1600 | rapidocr-ppocr@1600 | text-layer@ |
|---|---|---|---|---|---|---|---|
| origin | 3 | 3 | 743 | 739 | 739 | 740 | 0 |
| gibbon | 12 | 12 | 226 | 224 | 226 | 228 | 0 |
| james | 11 | 11 | 132 | 116 | 117 | 130 | 0 |
| varieties-preface | 273 | 273 | 290 | 281 | 278 | 276 | 0 |
| clean-scan |  | 0 | 0 | 0 | 0 | 0 | 0 |
| poor-scan |  | 0 | 0 | 0 | 0 | 0 | 0 |
| text-layer |  | 0 | 0 | 0 | 0 | 0 | 0 |
| small-print |  | 0 | 0 | 0 | 0 | 0 | 0 |
| headers-footnotes |  | 0 | 20 | 20 | 20 | 21 | 0 |
| two-column |  | 0 | 0 | 0 | 0 | 196 | 0 |
| french |  | 0 | 0 | 0 | 0 | 0 | 0 |
| russian |  | 0 | 0 | 1 | 2 | 0 | 0 |
| **word-weighted total** |  |  |  |  |  |  |  |

body_err by case (rows) and engine@width (columns)

| case | mlkit-raw-argb@1000 | mlkit-raw-argb@1600 | mlkit-raw-argb@2400 | tesseract-fast@1000 | tesseract-fast@1600 | tesseract-fast@2400 |
|---|---|---|---|---|---|---|
| origin | 1.9% | 2.1% | 2.4% | 0.0% | 0.1% | 0.1% |
| gibbon | 2.7% | 0.8% | 1.4% | 0.6% | 0.3% | 0.3% |
| james | 2.3% | 2.1% | 2.1% | 2.1% | 2.1% | 3.2% |
| varieties-preface | 2.2% | 3.3% | 2.8% | 1.1% | 0.9% | 1.2% |
| clean-scan | 0.3% | 0.3% | 0.3% | 0.5% | 0.5% | 0.0% |
| poor-scan | 0.7% | 1.4% | 1.7% | 6.7% | 8.6% | 5.3% |
| text-layer | 0.3% | 0.5% | 0.0% | 0.5% | 0.0% | 0.0% |
| small-print | 2.1% | 0.0% | 0.2% | 0.0% | 0.2% | 0.2% |
| headers-footnotes | 0.6% | 0.0% | 0.3% | 0.6% | 0.3% | 0.0% |
| two-column | 3.9% | 0.0% | 0.2% | 0.2% | 0.2% | 0.2% |
| french | 1.1% | 1.9% | 1.9% | 10.1% | 9.8% | 10.1% |
| russian | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% |
| **word-weighted total** | 2.0% | 0.9% | 1.4% | 1.7% | 1.9% | 1.6% |

Timings, seconds per page (median over cases)

| engine@width | render | recognise | layout ms | total |
|---|---|---|---|---|
| mlkit-layout-argb@1000 | 0.05 | 12.38 | 3.8 | 12.44 |
| mlkit-layout-argb@1600 | 0.09 | 12.86 | 4.3 | 12.97 |
| mlkit-layout-argb@2400 | 0.17 | 13.60 | 3.8 | 13.80 |
| mlkit-raw-argb@1000 | 0.05 | 12.38 | 3.8 | 12.44 |
| mlkit-raw-argb@1600 | 0.09 | 12.86 | 4.3 | 12.97 |
| mlkit-raw-argb@2400 | 0.17 | 13.60 | 3.8 | 13.79 |
| rapidocr-ppocr@1600 | 0.08 | 13.53 | 0.0 | 13.58 |
| tesseract-best@1600 | 0.08 | 9.39 | 0.0 | 9.48 |
| tesseract-fast-fra@1600 | 0.05 | 4.28 | 0.0 | 4.33 |
| tesseract-fast-rus@1600 | 0.04 | 1.55 | 0.0 | 1.59 |
| tesseract-fast@1000 | 0.05 | 2.02 | 0.0 | 2.07 |
| tesseract-fast@1600 | 0.08 | 2.63 | 0.0 | 2.70 |
| tesseract-fast@2400 | 0.18 | 3.22 | 0.0 | 3.31 |
production clean-scan-production.json: 1 pages, 15.4 s wall, 15.44 s/page, 2 cores
production french-production.json: 1 pages, 17.4 s wall, 17.38 s/page, 2 cores
production gibbon-production.json: 2 pages, 29.3 s wall, 14.67 s/page, 2 cores
production headers-footnotes-production.json: 1 pages, 13.0 s wall, 12.97 s/page, 2 cores
production james-production.json: 2 pages, 24.9 s wall, 12.45 s/page, 2 cores
production origin-production.json: 5 pages, 56.5 s wall, 11.29 s/page, 2 cores
production poor-scan-production.json: 1 pages, 15.3 s wall, 15.32 s/page, 2 cores
production russian-production.json: 1 pages, 10.3 s wall, 10.35 s/page, 2 cores
production small-print-production.json: 1 pages, 31.3 s wall, 31.30 s/page, 2 cores
production text-layer-production.json: 1 pages, 13.3 s wall, 13.35 s/page, 2 cores
production two-column-production.json: 1 pages, 28.0 s wall, 28.03 s/page, 2 cores
production varieties-preface-production.json: 4 pages, 35.5 s wall, 8.87 s/page, 2 cores
```

</details>
