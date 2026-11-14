"""Runs one desktop OCR engine over the corpus and writes a run file to results/.

Desktop timings are a rough proxy only: they are not phone timings. To try a new engine, add a function to
ENGINES that returns a callable taking a grey PIL image and a Tesseract language code and returning the text.
Usage: python -I desktop.py <engine> [--width 1600] [--lang eng|auto] [--out results]
Engines: text-layer, tesseract-fast, tesseract-best, rapidocr. Tesseract reads its models from
$OCR_BENCH_HOME/tessdata_fast and tessdata_best (default ~/.cache/ocr-bench).
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus  # noqa: E402
import runfile  # noqa: E402

HOME = os.environ.get("OCR_BENCH_HOME", os.path.expanduser("~/.cache/ocr-bench"))


def render(page, width):
    """A grey render at a fixed pixel width, as PdfText does with PdfRenderer (which renders ARGB)."""
    import pymupdf as fitz
    from PIL import Image

    zoom = width / page.rect.width
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), colorspace=fitz.csGRAY)
    return Image.frombytes("L", (pix.width, pix.height), pix.samples)


def tesseract(models):
    import tesserocr

    apis = {}

    def run(img, lang):
        if lang not in apis:
            apis[lang] = tesserocr.PyTessBaseAPI(path=os.path.join(HOME, models), lang=lang, psm=tesserocr.PSM.AUTO)
        apis[lang].SetImage(img)
        return apis[lang].GetUTF8Text()

    return run


def rapidocr():
    import numpy as np
    from rapidocr import RapidOCR

    engine = RapidOCR(params={"Global.log_level": "error"})

    def run(img, lang):
        return "\n".join(engine(np.asarray(img.convert("RGB"))).txts or ())

    return run


ENGINES = {
    "tesseract-fast": lambda: tesseract("tessdata_fast"),
    "tesseract-best": lambda: tesseract("tessdata_best"),
    "rapidocr": rapidocr,
}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("engine", choices=["text-layer", *ENGINES])
    p.add_argument("--width", type=int, default=1600)
    p.add_argument("--lang", default="eng", help="eng, or auto to use each case's language (Tesseract only)")
    p.add_argument("--out", default=runfile.RESULTS)
    a = p.parse_args()
    import pymupdf as fitz

    run = None if a.engine == "text-layer" else ENGINES[a.engine]()
    cases = {}
    for case in corpus.CASES:
        doc = fitz.open(corpus.pdf(case))
        n = doc.page_count
        lang = corpus.LANG.get(case, "eng") if a.lang == "auto" else a.lang
        texts, rend, rec = [], 0.0, 0.0
        for page in doc:
            t = time.perf_counter()
            if run is None:
                texts.append(page.get_text())
                rec += time.perf_counter() - t
                continue
            img = render(page, a.width)
            r = time.perf_counter()
            texts.append(run(img, lang))
            rend, rec = rend + r - t, rec + time.perf_counter() - r
        cases[case] = {"pages": n, "render_s": rend / n, "recognise_s": rec / n, "layout_ms": 0, "text": "\n".join(texts)}
        print(f"{case:18} {(rend + rec) / n:6.2f} s/page", flush=True)
    if run is None:
        name, settings = "text-layer", {}
    else:
        name = f"{a.engine}-{a.width}" + ("-auto" if a.lang == "auto" else "")
        settings = {"width": a.width, "lang": a.lang}
    print(runfile.write(name, a.engine, settings, runfile.desktop_host(), cases, a.out))


if __name__ == "__main__":
    main()
