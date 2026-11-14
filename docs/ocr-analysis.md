# OCR analysis: is ML Kit at 1600 px the best balance?

*Claude Code authored*

## TL;DR

- **Yes, for scanned Latin-script pages.** On these 12 test cases (10 of them scored), the bundled ML Kit Latin recogniser at 1600 px loses the fewest body words of the engines that can run on the phone. It loses 1.1% raw and 1.6% after `Layout`. Tesseract loses 1.5–1.7% and RapidOCR (PP-OCRv6) loses 13.2%. 1600 px is better than 1000 px (2.1%) and slightly better than 2400 px (1.2%). Recognition time changes by only 5–10% across these widths.
- **The largest gain is to skip OCR when the PDF has a real text layer.** Text extraction from a text-layer PDF gave 0% error and took 0.02 s per page. ML Kit took about 14 s per page on the emulator. Android exposes this text through `PdfRenderer.Page.getTextContents()` (API 35) and `PdfRendererPreV` (API 30–34 with the SDK extension). It adds nothing to the APK, but it needs `compileSdk` 35.
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
| 1 | Add a text-layer fast path. Use `getTextContents()` on API 35+, `PdfRendererPreV` on API 30–34, and OCR on API 26–29. Use the text layer only when it passes a per-page check (enough words, mostly letters, most words in a word list). Otherwise, run OCR. | The text-layer case: 0.0% error and 0.02 s per page from the text layer. ML Kit gave 0.5% and 29.4 s per page on the same page (13.3 s in an earlier run on a less loaded host). | Medium: `compileSdk` 35, a mapper from text-content bounds to `Layout`'s line model, the per-page check, and a fixture PDF with a text layer. | Low for scans, which have no text layer and so keep the current path. Medium for PDFs with a poor hidden OCR layer (Internet Archive PDFs have one); the per-page check exists for this case. | Evidence that most user PDFs are image-only scans would make this gain small. |
| 2 | Keep ML Kit bundled Latin at 1600 px. | The lowest body error of the on-device engines (table 1). 1600 px is the best of the three widths, and 2400 px is nearly equal (table 3). | None | None | Phone timings show that width costs much more on arm64 than on the emulator. A larger corpus could show a different best width. |
| 3 | Fix the layout errors that a listener hears: contents and index pages, margin sidenotes, and footnote markers inside the body text. Also check why `Layout` drops the German verse in `james`. | `varieties-preface`: 273 inserted words after `Layout` (a contents page). `gibbon`: sidenotes read aloud. `james`: 2.1% raw, 7.6% after `Layout`. | Small to medium for each item | Low, with the existing fixtures as regression tests | – |
| 4 | Optional: add Tesseract as a second engine for non-Latin scripts (Cyrillic, Greek) only. | `russian`: 100% loss with every Latin model. 0% with Tesseract `rus`, at 1.6 s per page. | Large: native library, about 4 MB for each `tessdata_fast` language, and a language choice in the UI | Medium: APK size, and Tesseract drops whole lines on poor scans | No demand for non-Latin books. |
| 5 | Do not adopt RapidOCR, PaddleOCR or `tessdata_best`. | RapidOCR: 13.2% total loss, and it mixes the two columns. `tessdata_best`: only slightly better than `fast` (1.5% against 1.7%), 3.5 times slower, and 15.4 MB. | – | – | A newer PP-OCR model that reads multi-column pages in order. |

## Method

**Ground truth.** The four real fixtures in `app/src/androidTest/assets/ocr` are scans from old printed books. Their truth files are the proofread Wikisource transcriptions of the body text. The truth has no running heads, page numbers, sidenotes or footnotes, so a correct engine-plus-layout output inserts no words. The eight synthetic cases are in `tools/ocr-bench/corpus/`. They were built once with `tools/ocr-bench/synth.py`. It typesets text from two Project Gutenberg books (#1342 and #17989) and a Russian paragraph with DejaVu Serif on a 6 × 9 inch page. The truth is the text that the script typeset.

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

**Metrics.** `body_err` is (substitutions + deletions) / truth words, on lower-case words without punctuation. It is the share of body text that the listener does not hear correctly. Insertions are counted separately, because a raw engine also reads running heads and footnotes, which `Layout` removes. `metrics.py` also computes WER and CER, but `score.py` does not print them. Soft hyphens at line ends are joined the same way as `Layout` joins them. Em dashes become spaces. `tools/ocr-bench/metrics.py` has the details.

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
| **Total, word-weighted, without text-layer and russian** | **1.6%** | **1.1%** | **1.7%** | **1.5%** | **13.2%** | – |

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
| 1000 px | 2.1% | 13.33 | 1.6% | 2.29 |
| 1600 px | **1.1%** | 14.26 | 1.7% | 2.93 |
| 2400 px | 1.2% | 15.33 | 1.5% | 3.53 |

At 1000 px, ML Kit loses more text on `small-print` (2.1%) and `two-column` (3.9%). This agrees with the ML Kit guidance that characters should be at least 16 × 16 px ([ML Kit text recognition v2, Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)). At 2400 px, it gains nothing (1.2% against 1.1%): the guidance says that characters larger than 24 × 24 px give no accuracy benefit.

### 4. Where the time goes (emulator, 2 cores, `results/mlkit-1600-argb.json`, median over the 10 scored cases)

| Stage | Median per page |
|---|---|
| Render (`PdfRenderer`, 1600 px, ARGB_8888) | 0.10 s |
| ML Kit recognition | 12.86 s |
| `Layout.paragraphs` | 3.6 ms |

Recognition takes more than 99% of the time. Production `PdfText.ocr` took 8.9–31.3 s per page; dense pages took the most time (`small-print` 31.3 s, `two-column` 28.0 s). The pages run in parallel, but ML Kit already uses both emulator cores, so more parallelism gives little. A sibling measurement found the same: `PARALLEL` 1, 2 and 3 took 81.7, 73.7 and 70.8 s. On a phone with 8 cores, parallelism can help more. This was not measured.

**Caveat.** ML Kit took about 5 times longer per page on the emulator than single-threaded Tesseract took on the desktop. This does not show that Tesseract is faster on a phone. The ML Kit x86_64 library possibly does not have the optimisations of the arm64 library, and the host was under load. Only a timing on a phone can settle this.

## What a listener hears

**Errors that change the speech** (from `tools/ocr-bench/diff.py`):

- *Layout:* a contents page is read aloud (`varieties-preface`). Margin sidenotes are read aloud (`gibbon`: "Of the people"). Footnote markers are read as numbers ("8", "4" in `james`). `Layout` drops a German verse in `james` (2.1% → 7.6%).
- *ML Kit recognition:* lost ligatures ("efects", "dificulty", "refections"), rn and m confused ("conmprehensible", "armnateur"), lost first letters on a poor scan ("derstanding", "nd"), and a hyphenated word split across a line break ("super" … "lucent").
- *Tesseract:* drops whole lines on the poor scan ("unconcern which was not in the least altered by her communication…"). Without the `fra` model, it misreads accents ("aussité6t").
- *RapidOCR:* reads across the two columns, so each line mixes both columns.

**Cosmetic errors** (a TTS voice reads them the same way): "l'ile" for "l'île", an em dash read as a hyphen, and a straight quote for a curly quote.

## Alternatives

| Option | Accuracy (body lost) | Speed | Size added to APK | Licence | Offline | Scripts | Integration |
|---|---|---|---|---|---|---|---|
| ML Kit bundled Latin, 1600 px (current) | 1.1% raw | 14.3 s/page (emulator) | 0 (already 12.6 MB for each ABI: `libmlkit_google_ocr_pipeline.so` 11.1 MB arm64 + 1.5 MB models) | Google ML Kit terms (proprietary, free) | Yes | Latin only in this recogniser. Chinese, Devanagari, Japanese and Korean are separate recognisers. No Cyrillic or Greek. | – |
| ML Kit unbundled (Play services) | Same model (not measured) | Not measured | About 260 KB, but the model downloads on first use | Same | After the download | Same | Small. It needs Google Play services. |
| Text layer: `getTextContents()` / `PdfRendererPreV` | 0.0% when a real layer exists | 0.02 s/page (desktop, PyMuPDF; the Android API was not timed) | 0 | Android platform | Yes | All | Medium. `compileSdk` 35. API 30+ only. |
| Text layer: PdfBox-Android | Not measured | Not measured | Several MB (not measured) | Apache-2.0 | Yes | All | Medium |
| Text layer: PDFium | Not measured | Not measured | Native library (not measured) | BSD-3-Clause / Apache-2.0 | Yes | All | Large |
| Text layer: MuPDF | – | – | – | **AGPL or commercial: not allowed** | – | – | – |
| Tesseract 5, `tessdata_fast` (Tesseract4Android) | 1.7% | 2.9 s/page (desktop, 1 thread) | Native library (not measured) + 4.1 MB `eng` | Apache-2.0 (Leptonica and the image libraries have their own licences) | Yes | More than 100, with one model for each language | Large |
| Tesseract 5, `tessdata_best` | 1.5% | 10.1 s/page (desktop) | + 15.4 MB `eng` | Apache-2.0 | Yes | As above | Large |
| RapidOCR / PP-OCRv6 small (ONNX) | 13.2% | 14.5 s/page (desktop, ONNX Runtime with many threads) | 31.7 MB of models. The app already has ONNX Runtime for the voice, but the ORT AAR possibly adds a second copy. | Apache-2.0, MIT (ONNX Runtime) | Yes | Latin, Chinese, Japanese and others | Large |
| PaddleOCR / NCNN builds | Not measured. The same model family as RapidOCR. | Not measured | Not measured | Apache-2.0, BSD-3-Clause (NCNN) | Yes | As RapidOCR | Large |
| ML Kit document scanner | – | – | – | – | – | – | Not applicable: it captures pages with the camera, and it does not read PDFs. |

**Upper bound.** No desktop-only model (for example a large vision-language model) was run. On these Latin pages, the best on-device result (1.1% raw ML Kit) is mostly lost ligatures and confused letters, and most of what a listener hears comes from layout. A stronger recogniser therefore cannot remove much more.

**Literature.** No published benchmark compares ML Kit with Tesseract on scanned book pages. The comparisons found are small or old, so they are only weak evidence. Some are blog posts from the ML Kit v1 era ([Heartbeat](https://heartbeat.comet.ml/comparing-on-device-text-recognition-ocr-sdks-24f8a0423461), [Fritz](https://fritz.ai/choose-the-right-on-device-text-recognition-sdk-on-android/)), and one is a thesis ([CORE 516510868](https://core.ac.uk/download/516510868.pdf)). A receipt benchmark ranks Tesseract last (CER 33.6%) and PaddleOCR at 20.5% ([imagetotable.ai](https://imagetotable.ai/references/receipt-ocr-benchmark-results)), but receipts are not book pages. The PP-OCRv5 paper reports that its mobile model reads more than 370 characters per second on a Xeon CPU, and that v5 is slower than v4 ([arXiv 2603.24373](https://arxiv.org/html/2603.24373v1)). No published arm64 phone timings for RapidOCR were found.

## What could not be measured

- **Phone timings.** The task did not allow runs on Alex's phone. All ML Kit timings are from the x86_64 emulator with 2 cores, and they show only relative differences between widths.
- **Parallelism on a phone with 8 cores.**
- **`getTextContents()` on a device.** The emulator has API 34, and the app compiles against SDK 34.
- **Tesseract and RapidOCR on Android.** They ran on the desktop only.
- **Binarised or deskewed input to ML Kit.** Grey input was measured later (`results/mlkit-1600-gray.json`). It gave the same text as ARGB on every case (1.6% total) and took the same time (12.40 s against 12.36 s median per page in the same run), so it is not worth a change.
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
<summary>Full output of <code>run.sh score</code> (verbatim)</summary>

Command, run in `tools/ocr-bench/results`: `../run.sh score mlkit-1600-argb.json mlkit-1600-argb-raw.json mlkit-1000-argb-raw.json mlkit-2400-argb-raw.json mlkit-1600-gray.json tesseract-fast-1000.json tesseract-fast-1600.json tesseract-fast-2400.json tesseract-best-1600.json rapidocr-1600.json text-layer.json`. The change in brackets is against the first run, the production baseline. The timings come from separate runs on a loaded host, so compare them only roughly.

```
Body words lost or wrong (body_err), words inserted (ins), seconds per page (s)

| case | mlkit-1600-argb body_err | ins | s | mlkit-1600-argb-raw body_err | ins | s | mlkit-1000-argb-raw body_err | ins | s | mlkit-2400-argb-raw body_err | ins | s | mlkit-1600-gray body_err | ins | s | tesseract-fast-1000 body_err | ins | s | tesseract-fast-1600 body_err | ins | s | tesseract-fast-2400 body_err | ins | s | tesseract-best-1600 body_err | ins | s | rapidocr-1600 body_err | ins | s | text-layer body_err | ins | s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| origin | 2.1% | 3 | 10.57 | 2.1% (+0.0%) | 743 | 10.56 | 1.9% (-0.3%) | 739 | 10.22 | 2.4% (+0.3%) | 741 | 10.59 | 2.1% (+0.0%) | 3 | 10.25 | 0.0% (-2.1%) | 738 | 1.62 | 0.1% (-2.0%) | 739 | 2.43 | 0.1% (-2.0%) | 739 | 2.38 | 0.0% (-2.1%) | 739 | 10.16 | 2.6% (+0.4%) | 740 | 26.40 | 100.0% (+97.9%) | 0 | 0.00 |
| gibbon | 0.8% | 12 | 15.21 | 0.8% (+0.0%) | 226 | 15.21 | 2.7% (+1.9%) | 230 | 13.85 | 1.4% (+0.6%) | 226 | 15.58 | 0.8% (+0.0%) | 12 | 13.59 | 0.6% (-0.2%) | 223 | 2.43 | 0.3% (-0.5%) | 224 | 3.21 | 0.3% (-0.5%) | 221 | 3.53 | 0.2% (-0.6%) | 226 | 9.89 | 0.5% (-0.3%) | 228 | 14.77 | 100.0% (+99.2%) | 0 | 0.00 |
| james | 7.6% | 11 | 10.89 | 2.1% (-5.5%) | 132 | 10.88 | 2.3% (-5.3%) | 131 | 9.92 | 2.1% (-5.5%) | 134 | 10.88 | 7.6% (+0.0%) | 11 | 10.95 | 2.1% (-5.5%) | 116 | 1.90 | 2.1% (-5.5%) | 116 | 2.32 | 3.2% (-4.4%) | 119 | 2.88 | 1.3% (-6.3%) | 117 | 6.65 | 2.3% (-5.3%) | 130 | 9.97 | 100.0% (+92.4%) | 0 | 0.00 |
| varieties-preface | 3.9% | 273 | 9.87 | 3.3% (-0.6%) | 290 | 9.86 | 2.2% (-1.7%) | 288 | 9.47 | 2.8% (-1.1%) | 289 | 9.72 | 3.9% (+0.0%) | 273 | 9.44 | 1.1% (-2.8%) | 277 | 1.31 | 0.9% (-3.0%) | 281 | 1.64 | 1.2% (-2.6%) | 277 | 2.60 | 1.1% (-2.8%) | 278 | 5.24 | 4.8% (+0.9%) | 276 | 11.02 | 100.0% (+96.1%) | 0 | 0.00 |
| clean-scan | 0.3% | 0 | 21.22 | 0.3% (+0.0%) | 0 | 21.22 | 0.3% (+0.0%) | 0 | 29.22 | 0.3% (+0.0%) | 0 | 23.53 | 0.3% (+0.0%) | 0 | 12.79 | 0.5% (+0.3%) | 0 | 2.34 | 0.5% (+0.3%) | 0 | 2.83 | 0.0% (-0.3%) | 0 | 3.68 | 0.0% (-0.3%) | 0 | 10.42 | 7.9% (+7.7%) | 0 | 12.91 | 100.0% (+99.7%) | 0 | 0.00 |
| poor-scan | 1.4% | 0 | 13.32 | 1.4% (+0.0%) | 0 | 13.32 | 0.7% (-0.7%) | 0 | 12.06 | 1.7% (+0.2%) | 0 | 15.07 | 1.4% (+0.0%) | 0 | 12.40 | 6.7% (+5.3%) | 0 | 1.78 | 8.6% (+7.2%) | 0 | 3.03 | 5.3% (+3.8%) | 0 | 3.53 | 8.6% (+7.2%) | 0 | 8.12 | 6.7% (+5.3%) | 0 | 12.76 | 100.0% (+98.6%) | 0 | 0.00 |
| text-layer | 0.5% | 0 | 29.38 | 0.5% (+0.0%) | 0 | 29.38 | 0.3% (-0.3%) | 0 | 26.08 | 0.0% (-0.5%) | 0 | 17.30 | 0.5% (+0.0%) | 0 | 14.06 | 0.5% (+0.0%) | 0 | 2.24 | 0.0% (-0.5%) | 0 | 2.77 | 0.0% (-0.5%) | 0 | 3.63 | 0.0% (-0.5%) | 0 | 9.74 | 6.9% (+6.4%) | 0 | 13.73 | 0.0% (-0.5%) | 0 | 0.01 |
| small-print | 0.0% | 0 | 31.92 | 0.0% (+0.0%) | 0 | 31.92 | 2.1% (+2.1%) | 10 | 36.77 | 0.2% (+0.2%) | 0 | 36.71 | 0.0% (+0.0%) | 0 | 32.12 | 0.0% (+0.0%) | 0 | 3.89 | 0.2% (+0.2%) | 0 | 6.42 | 0.2% (+0.2%) | 0 | 7.06 | 0.0% (+0.0%) | 0 | 24.13 | 0.0% (+0.0%) | 0 | 31.28 | 100.0% (+100.0%) | 0 | 0.00 |
| headers-footnotes | 0.0% | 0 | 12.62 | 0.0% (+0.0%) | 20 | 12.61 | 0.6% (+0.6%) | 20 | 12.82 | 0.3% (+0.3%) | 20 | 12.51 | 0.0% (+0.0%) | 0 | 11.96 | 0.6% (+0.6%) | 20 | 2.24 | 0.3% (+0.3%) | 20 | 3.17 | 0.0% (+0.0%) | 20 | 3.10 | 0.0% (+0.0%) | 20 | 12.99 | 2.8% (+2.8%) | 21 | 14.24 | 100.0% (+100.0%) | 0 | 0.00 |
| two-column | 0.0% | 0 | 27.91 | 0.0% (+0.0%) | 0 | 27.90 | 3.9% (+3.9%) | 17 | 37.41 | 0.2% (+0.2%) | 0 | 27.90 | 0.0% (+0.0%) | 0 | 27.28 | 0.2% (+0.2%) | 0 | 4.20 | 0.2% (+0.2%) | 0 | 5.86 | 0.2% (+0.2%) | 0 | 7.92 | 0.0% (+0.0%) | 0 | 20.59 | 69.5% (+69.5%) | 196 | 29.43 | 100.0% (+100.0%) | 0 | 0.00 |
| french | 1.9% | 0 | 30.00 | 1.9% (+0.0%) | 0 | 29.99 | 1.1% (-0.8%) | 0 | 21.77 | 1.9% (+0.0%) | 0 | 32.27 | 1.9% (+0.0%) | 0 | 12.92 | 10.1% (+8.2%) | 0 | 4.02 | 9.8% (+7.9%) | 0 | 2.70 | 10.1% (+8.2%) | 0 | 4.07 | 10.3% (+8.5%) | 0 | 10.12 | 0.0% (-1.9%) | 0 | 15.55 | 100.0% (+98.1%) | 0 | 0.00 |
| russian | 100.0% | 0 | 10.44 | 100.0% (+0.0%) | 0 | 10.44 | 100.0% (+0.0%) | 0 | 9.63 | 100.0% (+0.0%) | 0 | 10.44 | 100.0% (+0.0%) | 0 | 9.14 | 100.0% (+0.0%) | 3 | 1.00 | 100.0% (+0.0%) | 1 | 1.36 | 100.0% (+0.0%) | 2 | 4.16 | 100.0% (+0.0%) | 2 | 5.04 | 100.0% (+0.0%) | 0 | 10.15 | 100.0% (+0.0%) | 0 | 0.00 |
| **total, scans only** | **1.6%** | 299 | 14.27 median | **1.1%** (-0.5%) | 1411 | 14.26 median | **2.1%** (+0.4%) | 1435 | 13.33 median | **1.2%** (-0.4%) | 1410 | 15.33 median | **1.6%** (+0.0%) | 299 | 12.60 median | **1.6%** (-0.1%) | 1374 | 2.29 median | **1.7%** (+0.0%) | 1380 | 2.93 median | **1.5%** (-0.1%) | 1376 | 3.53 median | **1.5%** (-0.1%) | 1380 | 10.14 median | **13.2%** (+11.6%) | 1591 | 14.51 median | **100.0%** (+98.4%) | 0 | 0.00 median |

- mlkit-1600-argb: mlkit-bundled-latin {'width': 1600, 'mode': 'argb', 'output': 'layout'}, emulator emulator-5580 x86_64 2 cores, commit bed37a4, 2026-11-14
- mlkit-1600-argb-raw: mlkit-bundled-latin {'width': 1600, 'mode': 'argb', 'output': 'raw'}, emulator emulator-5580 x86_64 2 cores, commit bed37a4, 2026-11-14
- mlkit-1000-argb-raw: mlkit-bundled-latin {'width': 1000, 'mode': 'argb', 'output': 'raw'}, emulator emulator-5580 x86_64 2 cores, commit bed37a4, 2026-11-14
- mlkit-2400-argb-raw: mlkit-bundled-latin {'width': 2400, 'mode': 'argb', 'output': 'raw'}, emulator emulator-5580 x86_64 2 cores, commit bed37a4, 2026-11-14
- mlkit-1600-gray: mlkit-bundled-latin {'width': 1600, 'mode': 'gray', 'output': 'layout'}, emulator emulator-5580 x86_64 2 cores, commit 94d4b39, 2026-11-14
- tesseract-fast-1000: tesseract-fast {'width': 1000, 'lang': 'eng'}, desktop x86_64 4 cores, under load from other sessions, commit bed37a4, 2026-11-14
- tesseract-fast-1600: tesseract-fast {'width': 1600, 'lang': 'eng'}, desktop x86_64 4 cores, under load from other sessions, commit bed37a4, 2026-11-14
- tesseract-fast-2400: tesseract-fast {'width': 2400, 'lang': 'eng'}, desktop x86_64 4 cores, under load from other sessions, commit bed37a4, 2026-11-14
- tesseract-best-1600: tesseract-best {'width': 1600, 'lang': 'eng'}, desktop x86_64 4 cores, under load from other sessions, commit bed37a4, 2026-11-14
- rapidocr-1600: rapidocr {'width': 1600, 'lang': 'eng'}, desktop x86_64 4 cores, under load from other sessions, commit bed37a4, 2026-11-14
- text-layer: text-layer {}, desktop x86_64 4 cores, commit 94d4b39, 2026-11-14
```

</details>
