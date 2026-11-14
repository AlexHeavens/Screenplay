# OCR benchmark

An on-demand benchmark for experiments with the OCR path. It runs one fixed corpus through an engine, stores the output as a run file, and scores runs against the current solution. It is not part of the build, the unit tests or `scripts/ui-tests.sh`.

[docs/ocr-analysis.md](../../docs/ocr-analysis.md) has the analysis of the options and the conclusions from the first runs.

## What is here

| Path | Contents |
|---|---|
| `corpus.py` | The 15 cases. Seven real scans come from `app/src/androidTest/assets/ocr`. Eight synthetic pages are in `corpus/`. |
| `corpus/` | The synthetic PDFs and their exact truth. `synth.py` built them once from Project Gutenberg #1342 and #17989. They are committed so that every run reads the same pages. |
| `results/` | Stored run files. **`mlkit-1600-argb.json` is the baseline: the current production path.** |
| `run.sh` | The entry point (see below). |
| `desktop.py` | Desktop engines: `text-layer`, `tesseract-fast`, `tesseract-best`, `rapidocr`. |
| `collect.py` | Converts the output of `OcrBenchmarkTest` (on the emulator) into run files. |
| `score.py` | Scores run files and compares each one with the first. |
| `diff.py` | Prints the word errors of one case in one run. |
| `metrics.py` | The normalisation and the error rates. |

## Set up

```bash
tools/ocr-bench/run.sh setup
```

This makes a Python venv and downloads the `eng`, `fra` and `rus` Tesseract models into `$OCR_BENCH_HOME` (default `~/.cache/ocr-bench`). Nothing is downloaded into the repository. PyMuPDF is AGPL. It is used here as a desktop tool only, and must not go into the app.

## Run

ML Kit, as the app runs it, on the shared emulator (`JAVA_HOME` and `ANDROID_HOME` must be set):

```bash
tools/ocr-bench/run.sh emulator 1600 argb
```

The script builds the APKs, waits until no `scripts/ui-tests.sh` run is active, and holds the same run lock. One width and one mode take about 8 minutes. For each width and mode, it writes two run files: `mlkit-<width>-<mode>.json` (after `Layout`, which is what the app speaks) and `mlkit-<width>-<mode>-raw.json` (the raw ML Kit lines). Set `OCR_BENCH_OUT` to write somewhere other than `results/`.

A desktop engine:

```bash
tools/ocr-bench/run.sh desktop tesseract-fast --width 2400
```

Compare runs with the baseline:

```bash
tools/ocr-bench/run.sh score tools/ocr-bench/results/mlkit-1600-argb.json tools/ocr-bench/results/tesseract-fast-1600.json
```

`body_err` is the share of body words that are lost or wrong, which is what a listener misses. `ins` is the count of words that are spoken but are not body text (running heads, notes, contents pages). The total leaves out `text-layer` (not a scan) and `russian` (no Latin recogniser can read it).

## Add an experiment

- **A change to the app's OCR path** (for example the render width, preprocessing, or `Layout`): make the change, run `run.sh emulator`, and score the new run against `results/mlkit-1600-argb.json`. To compare preprocessing without changing the app, add a mode to `OcrBenchmarkTest.measure` next to `gray`, and pass it with `run.sh emulator 1600 argb,<mode>`.
- **Another engine on the desktop**: add a function to `ENGINES` in `desktop.py`. It returns a callable that takes a grey PIL image and a Tesseract language code and returns the text.
- **Another engine on Android**: write a run file with the same format (`runfile.py`), then score it.
- **A new case**: add the PDF and its truth to `corpus/`, add the name to `corpus.SYNTH`, and rerun the baseline so that the totals stay comparable.

Commit a run file when it supports a decision, and add the result to `docs/ocr-analysis.md`. When a change to the app becomes the production path, replace the baseline in the same commit.

## Limits

- Emulator and desktop timings are not phone timings. They are useful only to compare runs on the same host. The desktop runs from 2026-11-14 ran while other sessions used the host.
- The synthetic pages use one font (DejaVu Serif). The real scans are seven pages from old English books.
