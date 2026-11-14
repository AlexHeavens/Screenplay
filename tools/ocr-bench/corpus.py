"""The benchmark corpus: each case is a PDF and the body text a listener should hear.

Real cases are the app's OCR fixtures (app/src/androidTest/assets/ocr), scans whose truth is the proofread
Wikisource transcription. Synthetic cases (corpus/) were built once by synth.py and are committed, so every run
reads the same pages.
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(HERE, "..", "..", "app", "src", "androidTest", "assets", "ocr")
SYNTH_DIR = os.path.join(HERE, "corpus")

REAL = ["origin", "gibbon", "james", "varieties-preface"]
SYNTH = ["clean-scan", "poor-scan", "text-layer", "small-print", "headers-footnotes", "two-column", "french", "russian"]
CASES = REAL + SYNTH
# Left out of the totals: text-layer is not a scan, and no Latin recogniser can read russian.
TOTAL = [c for c in CASES if c not in ("text-layer", "russian")]
LANG = {"french": "fra", "russian": "rus"}


def directory(case):
    return FIXTURES if case in REAL else SYNTH_DIR


def pdf(case):
    return os.path.join(directory(case), f"{case}.pdf")


def truth(case):
    with open(os.path.join(directory(case), f"{case}.txt"), encoding="utf-8") as f:
        return f.read()


if __name__ == "__main__":
    print("\n".join(pdf(c) for c in CASES))
