# OCR analysis: is ML Kit at 1600 px the best balance?

*Claude Code authored*

## TL;DR

- **Yes, for scanned Latin-script pages, but Tesseract is now level with it.** On these 13 test cases (11 of them scored), the bundled ML Kit Latin recogniser at 1600 px loses 1.5% of body words raw and 2.7% after `Layout`. Tesseract loses 1.5–1.6% and RapidOCR (PP-OCRv6) loses 11.5%. Tesseract is better on the real book scans; ML Kit is better on the poor scan and on accented French. 1600 px is better than 1000 px (2.3%) and slightly better than 2400 px (1.7%). Recognition time does not grow with width: 12.2–13.7 s median per page across the three widths.
- **The largest gain is to skip OCR when the PDF has a real text layer.** Text extraction from a text-layer PDF gave 0% error and took 0.02 s per page. ML Kit took about 14 s per page on the emulator. Android exposes this text through `PdfRenderer.Page.getTextContents()` (API 35) and `PdfRendererPreV` (API 30–34 with the SDK extension). It adds nothing to the APK, but it needs `compileSdk` 35.
- **Most of the errors that a listener hears come from layout, not from recognition.** The examples are a contents page read aloud, margin sidenotes read aloud, a long footnote read aloud, a drop cap lost, a German verse dropped by `Layout`, and footnote markers read as numbers. On `bell`, `Layout` raises the loss from 4.4% raw to 9.3%. A different engine does not fix these.
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
| 2 | Keep ML Kit bundled Latin at 1600 px. | Raw, it is level with Tesseract (1.5%, table 1) and it needs no new native library. 1600 px is the best of the three widths (table 3). | None | None | Phone timings show that width costs much more on arm64 than on the emulator. A larger corpus could show a different best width. |
| 3 | Fix the layout errors that a listener hears: contents and index pages, margin sidenotes, long footnotes, drop caps that OCR orders before the byline, and footnote markers inside the body text. Also check why `Layout` drops the German verse in `james` and the last lines of the third page in `bell`. | `varieties-preface`: 273 inserted words after `Layout` (a contents page). `gibbon`: sidenotes read aloud. `james`: 2.1% raw, 7.6% after `Layout`. `bell`: 4.4% raw, 9.3% after `Layout`, and 287 inserted words (a 276-word footnote). | Small to medium for each item | Low, with the existing fixtures as regression tests | – |
| 4 | Optional: add Tesseract as a second engine for non-Latin scripts (Cyrillic, Greek) only. | `russian`: 100% loss with every Latin model. 0% with Tesseract `rus`, at 1.6 s per page. | Large: native library, about 4 MB for each `tessdata_fast` language, and a language choice in the UI | Medium: APK size, and Tesseract drops whole lines on poor scans | No demand for non-Latin books. |
| 5 | Do not adopt RapidOCR, PaddleOCR or `tessdata_best`. | RapidOCR: 11.5% total loss, and it mixes the two columns. `tessdata_best`: no better than `fast` (1.5% against 1.6%), 3.8 times slower, and 15.4 MB. | – | – | A newer PP-OCR model that reads multi-column pages in order. |

## Method

**Ground truth.** The five real fixtures in `app/src/androidTest/assets/ocr` are scans from old printed books. Their truth files are the proofread Wikisource transcriptions of the body text. The truth has no running heads, page numbers, sidenotes or footnotes, so a correct engine-plus-layout output inserts no words. The eight synthetic cases are in `tools/ocr-bench/corpus/`. They were built once with `tools/ocr-bench/synth.py`. It typesets text from two Project Gutenberg books (#1342 and #17989) and a Russian paragraph with DejaVu Serif on a 6 × 9 inch page. The truth is the text that the script typeset.

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
| bell | 9.3% | 4.4% | 1.3% | 1.5% | 0.8% | none |
| clean-scan | 0.3% | 0.3% | 0.5% | 0.0% | 7.9% | none |
| poor-scan | 1.4% | 1.4% | 8.6% | 8.6% | 6.7% | none |
| text-layer | 0.5% | 0.5% | 0.0% | 0.0% | 6.9% | **0.0%** |
| small-print | 0.0% | 0.0% | 0.2% | 0.0% | 0.0% | none |
| headers-footnotes | 0.0% | 0.0% | 0.3% | 0.0% | 2.8% | none |
| two-column | 0.0% | 0.0% | 0.2% | 0.0% | 69.5% | none |
| french | 1.9% | 1.9% | 9.8% (`fra`: 0.0%) | 10.3% | 0.0% | none |
| russian | 100% | 100% | 100% (`rus`: 0.0%) | 100% | 100% | none |
| **Total, word-weighted, without text-layer and russian** | **2.7%** | **1.5%** | **1.6%** | **1.5%** | **11.5%** | – |

"none" means that the PDF has no text layer. The `fra` and `rus` values come from separate runs with those Tesseract models.

### 2. Words inserted (read aloud but not in the body text)

| case | ML Kit + Layout | ML Kit raw | Tesseract fast |
|---|---|---|---|
| origin (includes index pages) | 3 | 743 | 739 |
| gibbon (sidenotes, footnotes) | 12 | 226 | 224 |
| varieties-preface (contents page) | 273 | 290 | 281 |
| bell (footnotes, running heads) | 287 | 375 | 377 |
| headers-footnotes | 0 | 20 | 20 |

`Layout` removes almost all running heads, page numbers, footnotes and index pages. It does not remove a contents page, the margin sidenotes in `gibbon`, or the long footnote on the first page of `bell`.

### 3. Render width, ML Kit raw against Tesseract fast (total body text lost)

| Width | ML Kit raw | ML Kit, median s/page (emulator) | Tesseract fast | Tesseract fast, median s/page (desktop, 1 thread) |
|---|---|---|---|---|
| 1000 px | 2.3% | 12.16 | 1.5% | 1.43 |
| 1600 px | **1.5%** | 13.69 | 1.6% | 1.80 |
| 2400 px | 1.7% | 13.14 | 1.5% | 2.50 |

At 1000 px, ML Kit loses more text on `small-print` (2.1%) and `two-column` (3.9%). This agrees with the ML Kit guidance that characters should be at least 16 × 16 px ([ML Kit text recognition v2, Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)). At 2400 px, it gains nothing (1.7% against 1.5%): the guidance says that characters larger than 24 × 24 px give no accuracy benefit.

### 4. Where the time goes (emulator, 2 cores, `results/mlkit-1600-argb.json`, median over the 11 scored cases)

| Stage | Median per page |
|---|---|
| Render (`PdfRenderer`, 1600 px, ARGB_8888) | 0.09 s |
| ML Kit recognition | 13.60 s |
| `Layout.paragraphs` | 4.1 ms |

Recognition takes more than 99% of the time. In the earlier 10-case run, production `PdfText.ocr` took 8.9–31.3 s per page; dense pages took the most time (`small-print` 31.3 s, `two-column` 28.0 s). The pages run in parallel, but ML Kit already uses both emulator cores, so more parallelism gives little. A sibling measurement found the same: `PARALLEL` 1, 2 and 3 took 81.7, 73.7 and 70.8 s. On a phone with 8 cores, parallelism can help more. This was not measured.

**Caveat.** ML Kit took about 5 times longer per page on the emulator than single-threaded Tesseract took on the desktop. This does not show that Tesseract is faster on a phone. The ML Kit x86_64 library possibly does not have the optimisations of the arm64 library, and the host was under load. Only a timing on a phone can settle this.

## What a listener hears

**Errors that change the speech** (from `tools/ocr-bench/diff.py`):

- *Layout:* a contents page is read aloud (`varieties-preface`). Margin sidenotes are read aloud (`gibbon`: "Of the people"). Footnote markers are read as numbers ("8", "4" in `james`). `Layout` drops a German verse in `james` (2.1% → 7.6%). In `bell`, a 276-word footnote is read aloud, the drop cap "A" is lost ("T the time") because OCR orders it before the byline, and the last four lines of the third page are lost.
- *ML Kit recognition:* the footnote markers † and ‡ read as "+" and "t" (`bell`), lost ligatures ("efects", "dificulty", "refections"), rn and m confused ("conmprehensible", "armnateur"), lost first letters on a poor scan ("derstanding", "nd"), and a hyphenated word split across a line break ("super" … "lucent").
- *Tesseract:* drops whole lines on the poor scan ("unconcern which was not in the least altered by her communication…"). Without the `fra` model, it misreads accents ("aussité6t").
- *RapidOCR:* reads across the two columns, so each line mixes both columns.

**Cosmetic errors** (a TTS voice reads them the same way): "l'ile" for "l'île", an em dash read as a hyphen, and a straight quote for a curly quote.

## Alternatives

| Option | Accuracy (body lost) | Speed | Size added to APK | Licence | Offline | Scripts | Integration |
|---|---|---|---|---|---|---|---|
| ML Kit bundled Latin, 1600 px (current) | 1.5% raw | 13.7 s/page (emulator) | 0 (already 12.6 MB for each ABI: `libmlkit_google_ocr_pipeline.so` 11.1 MB arm64 + 1.5 MB models) | Google ML Kit terms (proprietary, free) | Yes | Latin only in this recogniser. Chinese, Devanagari, Japanese and Korean are separate recognisers. No Cyrillic or Greek. | – |
| ML Kit unbundled (Play services) | Same model (not measured) | Not measured | About 260 KB, but the model downloads on first use | Same | After the download | Same | Small. It needs Google Play services. |
| Text layer: `getTextContents()` / `PdfRendererPreV` | 0.0% when a real layer exists | 0.02 s/page (desktop, PyMuPDF; the Android API was not timed) | 0 | Android platform | Yes | All | Medium. `compileSdk` 35. API 30+ only. |
| Text layer: PdfBox-Android | Not measured | Not measured | Several MB (not measured) | Apache-2.0 | Yes | All | Medium |
| Text layer: PDFium | Not measured | Not measured | Native library (not measured) | BSD-3-Clause / Apache-2.0 | Yes | All | Large |
| Text layer: MuPDF | – | – | – | **AGPL or commercial: not allowed** | – | – | – |
| Tesseract 5, `tessdata_fast` (Tesseract4Android) | 1.6% | 1.8 s/page (desktop, 1 thread) | Native library (not measured) + 4.1 MB `eng` | Apache-2.0 (Leptonica and the image libraries have their own licences) | Yes | More than 100, with one model for each language | Large |
| Tesseract 5, `tessdata_best` | 1.5% | 6.8 s/page (desktop) | + 15.4 MB `eng` | Apache-2.0 | Yes | As above | Large |
| RapidOCR / PP-OCRv6 small (ONNX) | 11.5% | 8.6 s/page (desktop, ONNX Runtime with many threads) | 31.7 MB of models. The app already has ONNX Runtime for the voice, but the ORT AAR possibly adds a second copy. | Apache-2.0, MIT (ONNX Runtime) | Yes | Latin, Chinese, Japanese and others | Large |
| PaddleOCR / NCNN builds | Not measured. The same model family as RapidOCR. | Not measured | Not measured | Apache-2.0, BSD-3-Clause (NCNN) | Yes | As RapidOCR | Large |
| ML Kit document scanner | – | – | – | – | – | – | Not applicable: it captures pages with the camera, and it does not read PDFs. |

**Upper bound.** No desktop-only model (for example a large vision-language model) was run. On these Latin pages, the best on-device result (1.5%, ML Kit raw or Tesseract) is mostly lost ligatures and confused letters, and most of what a listener hears comes from layout. A stronger recogniser therefore cannot remove much more.

**Literature.** No published benchmark compares ML Kit with Tesseract on scanned book pages. The comparisons found are small or old, so they are only weak evidence. Some are blog posts from the ML Kit v1 era ([Heartbeat](https://heartbeat.comet.ml/comparing-on-device-text-recognition-ocr-sdks-24f8a0423461), [Fritz](https://fritz.ai/choose-the-right-on-device-text-recognition-sdk-on-android/)), and one is a thesis ([CORE 516510868](https://core.ac.uk/download/516510868.pdf)). A receipt benchmark ranks Tesseract last (CER 33.6%) and PaddleOCR at 20.5% ([imagetotable.ai](https://imagetotable.ai/references/receipt-ocr-benchmark-results)), but receipts are not book pages. The PP-OCRv5 paper reports that its mobile model reads more than 370 characters per second on a Xeon CPU, and that v5 is slower than v4 ([arXiv 2603.24373](https://arxiv.org/html/2603.24373v1)). No published arm64 phone timings for RapidOCR were found.

## What could not be measured

- **Phone timings.** The task did not allow runs on Alex's phone. All ML Kit timings are from the x86_64 emulator with 2 cores, and they show only relative differences between widths.
- **Parallelism on a phone with 8 cores.**
- **`getTextContents()` on a device.** The emulator has API 34, and the app compiles against SDK 34.
- **Tesseract and RapidOCR on Android.** They ran on the desktop only.
- **Binarised or deskewed input to ML Kit.** Grey input was measured later (`results/mlkit-1600-gray.json`). It gave the same text as ARGB on every case (2.7% total) and took about the same time (12.97 s against 13.70 s median per page, in separate runs), so it is not worth a change.
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
| origin | 2.1% | 3 | 10.30 | 2.1% (+0.0%) | 743 | 10.30 | 1.9% (-0.3%) | 739 | 10.45 | 2.4% (+0.3%) | 741 | 10.76 | 2.1% (+0.0%) | 3 | 10.51 | 0.0% (-2.1%) | 738 | 1.01 | 0.1% (-2.0%) | 739 | 1.31 | 0.1% (-2.0%) | 739 | 1.75 | 0.0% (-2.1%) | 739 | 4.76 | 2.6% (+0.4%) | 740 | 6.98 | 100.0% (+97.9%) | 0 | 0.00 |
| gibbon | 0.8% | 12 | 14.99 | 0.8% (+0.0%) | 226 | 14.98 | 2.7% (+1.9%) | 230 | 12.99 | 1.4% (+0.6%) | 226 | 15.23 | 0.8% (+0.0%) | 12 | 13.96 | 0.6% (-0.2%) | 223 | 1.57 | 0.3% (-0.5%) | 224 | 1.98 | 0.3% (-0.5%) | 221 | 2.66 | 0.2% (-0.6%) | 226 | 7.35 | 0.5% (-0.3%) | 228 | 8.77 | 100.0% (+99.2%) | 0 | 0.00 |
| james | 7.6% | 11 | 10.54 | 2.1% (-5.5%) | 132 | 10.53 | 2.3% (-5.3%) | 131 | 10.05 | 2.1% (-5.5%) | 134 | 11.55 | 7.6% (+0.0%) | 11 | 10.64 | 2.1% (-5.5%) | 116 | 1.13 | 2.1% (-5.5%) | 116 | 1.45 | 3.2% (-4.4%) | 119 | 2.05 | 1.3% (-6.3%) | 117 | 5.12 | 2.3% (-5.3%) | 130 | 7.35 | 100.0% (+92.4%) | 0 | 0.00 |
| varieties-preface | 3.9% | 273 | 8.72 | 3.3% (-0.6%) | 290 | 8.72 | 2.2% (-1.7%) | 288 | 8.17 | 2.8% (-1.1%) | 289 | 9.53 | 3.9% (+0.0%) | 273 | 8.95 | 1.1% (-2.8%) | 277 | 0.86 | 0.9% (-3.0%) | 281 | 1.16 | 1.2% (-2.6%) | 277 | 1.63 | 1.1% (-2.8%) | 278 | 3.92 | 4.8% (+0.9%) | 276 | 5.89 | 100.0% (+96.1%) | 0 | 0.00 |
| bell | 9.3% | 287 | 13.70 | 4.4% (-4.9%) | 375 | 13.69 | 4.0% (-5.3%) | 371 | 13.88 | 4.3% (-5.0%) | 375 | 13.49 | 9.3% (+0.0%) | 287 | 14.08 | 1.0% (-8.2%) | 367 | 1.73 | 1.3% (-8.0%) | 377 | 2.13 | 1.5% (-7.8%) | 370 | 2.89 | 1.5% (-7.8%) | 378 | 7.58 | 0.8% (-8.4%) | 364 | 8.62 | 100.0% (+90.7%) | 0 | 0.00 |
| clean-scan | 0.3% | 0 | 14.34 | 0.3% (+0.0%) | 0 | 14.33 | 0.3% (+0.0%) | 0 | 12.26 | 0.3% (+0.0%) | 0 | 13.66 | 0.3% (+0.0%) | 0 | 13.01 | 0.5% (+0.3%) | 0 | 1.46 | 0.5% (+0.3%) | 0 | 1.80 | 0.0% (-0.3%) | 0 | 2.50 | 0.0% (-0.3%) | 0 | 7.25 | 7.9% (+7.7%) | 0 | 8.60 | 100.0% (+99.7%) | 0 | 0.00 |
| poor-scan | 1.4% | 0 | 12.37 | 1.4% (+0.0%) | 0 | 12.37 | 0.7% (-0.7%) | 0 | 11.90 | 1.7% (+0.2%) | 0 | 12.84 | 1.4% (+0.0%) | 0 | 12.59 | 6.7% (+5.3%) | 0 | 1.41 | 8.6% (+7.2%) | 0 | 2.03 | 5.3% (+3.8%) | 0 | 2.47 | 8.6% (+7.2%) | 0 | 5.82 | 6.7% (+5.3%) | 0 | 7.84 | 100.0% (+98.6%) | 0 | 0.00 |
| text-layer | 0.5% | 0 | 12.53 | 0.5% (+0.0%) | 0 | 12.53 | 0.3% (-0.3%) | 0 | 12.25 | 0.0% (-0.5%) | 0 | 13.42 | 0.5% (+0.0%) | 0 | 13.64 | 0.5% (+0.0%) | 0 | 1.42 | 0.0% (-0.5%) | 0 | 1.69 | 0.0% (-0.5%) | 0 | 2.15 | 0.0% (-0.5%) | 0 | 6.74 | 6.9% (+6.4%) | 0 | 8.48 | 0.0% (-0.5%) | 0 | 0.01 |
| small-print | 0.0% | 0 | 27.42 | 0.0% (+0.0%) | 0 | 27.41 | 2.1% (+2.1%) | 10 | 26.57 | 0.2% (+0.2%) | 0 | 28.29 | 0.0% (+0.0%) | 0 | 29.38 | 0.0% (+0.0%) | 0 | 2.94 | 0.2% (+0.2%) | 0 | 4.01 | 0.2% (+0.2%) | 0 | 5.00 | 0.0% (+0.0%) | 0 | 16.20 | 0.0% (+0.0%) | 0 | 21.35 | 100.0% (+100.0%) | 0 | 0.00 |
| headers-footnotes | 0.0% | 0 | 12.62 | 0.0% (+0.0%) | 20 | 12.62 | 0.6% (+0.6%) | 20 | 11.36 | 0.3% (+0.3%) | 20 | 12.00 | 0.0% (+0.0%) | 0 | 11.81 | 0.6% (+0.6%) | 20 | 1.32 | 0.3% (+0.3%) | 20 | 1.64 | 0.0% (+0.0%) | 20 | 2.28 | 0.0% (+0.0%) | 20 | 6.13 | 2.8% (+2.8%) | 21 | 9.28 | 100.0% (+100.0%) | 0 | 0.00 |
| two-column | 0.0% | 0 | 26.41 | 0.0% (+0.0%) | 0 | 26.39 | 3.9% (+3.9%) | 17 | 25.15 | 0.2% (+0.2%) | 0 | 26.91 | 0.0% (+0.0%) | 0 | 29.11 | 0.2% (+0.2%) | 0 | 2.67 | 0.2% (+0.2%) | 0 | 3.57 | 0.2% (+0.2%) | 0 | 4.34 | 0.0% (+0.0%) | 0 | 14.42 | 69.5% (+69.5%) | 196 | 17.21 | 100.0% (+100.0%) | 0 | 0.00 |
| french | 1.9% | 0 | 13.79 | 1.9% (+0.0%) | 0 | 13.79 | 1.1% (-0.8%) | 0 | 12.16 | 1.9% (+0.0%) | 0 | 13.14 | 1.9% (+0.0%) | 0 | 12.97 | 10.1% (+8.2%) | 0 | 1.43 | 9.8% (+7.9%) | 0 | 1.78 | 10.1% (+8.2%) | 0 | 2.52 | 10.3% (+8.5%) | 0 | 6.78 | 0.0% (-1.9%) | 0 | 8.77 | 100.0% (+98.1%) | 0 | 0.00 |
| russian | 100.0% | 0 | 9.26 | 100.0% (+0.0%) | 0 | 9.26 | 100.0% (+0.0%) | 0 | 8.64 | 100.0% (+0.0%) | 0 | 9.65 | 100.0% (+0.0%) | 0 | 9.72 | 100.0% (+0.0%) | 3 | 0.86 | 100.0% (+0.0%) | 1 | 1.06 | 100.0% (+0.0%) | 2 | 1.50 | 100.0% (+0.0%) | 2 | 3.79 | 100.0% (+0.0%) | 0 | 6.33 | 100.0% (+0.0%) | 0 | 0.00 |
| **total, scans only** | **2.7%** | 586 | 13.70 median | **1.5%** (-1.1%) | 1786 | 13.69 median | **2.3%** (-0.4%) | 1806 | 12.16 median | **1.7%** (-1.0%) | 1785 | 13.14 median | **2.7%** (+0.0%) | 586 | 12.97 median | **1.5%** (-1.2%) | 1741 | 1.43 median | **1.6%** (-1.1%) | 1757 | 1.80 median | **1.5%** (-1.2%) | 1746 | 2.50 median | **1.5%** (-1.2%) | 1758 | 6.78 median | **11.5%** (+8.8%) | 1955 | 8.62 median | **100.0%** (+97.3%) | 0 | 0.00 median |

- mlkit-1600-argb: mlkit-bundled-latin {'width': 1600, 'mode': 'argb', 'output': 'layout'}, emulator emulator-5580 x86_64 2 cores, commit c15d1f0, 2026-11-14
- mlkit-1600-argb-raw: mlkit-bundled-latin {'width': 1600, 'mode': 'argb', 'output': 'raw'}, emulator emulator-5580 x86_64 2 cores, commit c15d1f0, 2026-11-14
- mlkit-1000-argb-raw: mlkit-bundled-latin {'width': 1000, 'mode': 'argb', 'output': 'raw'}, emulator emulator-5580 x86_64 2 cores, commit c15d1f0, 2026-11-14
- mlkit-2400-argb-raw: mlkit-bundled-latin {'width': 2400, 'mode': 'argb', 'output': 'raw'}, emulator emulator-5580 x86_64 2 cores, commit c15d1f0, 2026-11-14
- mlkit-1600-gray: mlkit-bundled-latin {'width': 1600, 'mode': 'gray', 'output': 'layout'}, emulator emulator-5580 x86_64 2 cores, commit c15d1f0, 2026-11-14
- tesseract-fast-1000: tesseract-fast {'width': 1000, 'lang': 'eng'}, desktop x86_64 4 cores, commit c15d1f0, 2026-11-14
- tesseract-fast-1600: tesseract-fast {'width': 1600, 'lang': 'eng'}, desktop x86_64 4 cores, commit c15d1f0, 2026-11-14
- tesseract-fast-2400: tesseract-fast {'width': 2400, 'lang': 'eng'}, desktop x86_64 4 cores, commit c15d1f0, 2026-11-14
- tesseract-best-1600: tesseract-best {'width': 1600, 'lang': 'eng'}, desktop x86_64 4 cores, commit c15d1f0, 2026-11-14
- rapidocr-1600: rapidocr {'width': 1600, 'lang': 'eng'}, desktop x86_64 4 cores, commit c15d1f0, 2026-11-14
- text-layer: text-layer {}, desktop x86_64 4 cores, commit c15d1f0, 2026-11-14
```

</details>
