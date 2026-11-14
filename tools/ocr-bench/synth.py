"""Builds synthetic test pages with exact ground truth.

Each case is a PDF in <outdir>/<case>.pdf and its body text in <outdir>/<case>.txt. The committed corpus/ was built
with this script; rebuild it only on purpose, since a new build changes the pages every stored result was scored on.
Text comes from Project Gutenberg plain-text files (passed as arguments) or is written here.
Scanned cases are vector pages rasterised, degraded and embedded as an image with no text layer.
Usage: python -I synth.py <pg1342.txt> <pg17989.txt> <outdir>
"""
import io
import random
import re
import sys

import fitz
import numpy as np
from PIL import Image, ImageFilter

SERIF = "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"
W, H = 6 * 72, 9 * 72  # a 6 x 9 in book page

RUSSIAN = (
    "Утром над рекой стоял густой туман, и старый паромщик долго не решался отвязать лодку. "
    "Он знал каждый камень у берега, но в такую погоду даже знакомая вода казалась чужой. "
    "Когда солнце наконец поднялось над лесом, туман начал редеть, и на другом берегу показались крыши деревни. "
    "Паромщик перекрестился, взял весло и медленно оттолкнулся от причала. "
    "На середине реки течение стало сильнее, лодку понесло в сторону, и ему пришлось грести изо всех сил. "
    "Вскоре он услышал голоса: на пристани его ждали трое путников с тяжёлыми мешками. "
    "Один из них, высокий человек в сером пальто, спросил, сколько стоит переправа. "
    "Паромщик назвал цену, и путник, не торгуясь, отсчитал несколько монет. "
    "Пока они плыли обратно, никто не произнёс ни слова, только вода тихо плескалась о борт. "
    "У самого берега высокий человек вдруг улыбнулся и сказал, что когда-то сам жил в этой деревне, "
    "но уехал отсюда ещё мальчиком и с тех пор ни разу не возвращался. "
    "Старик внимательно посмотрел на него, но так и не смог вспомнить его лица. "
    "Вечером в деревне говорили только о приезжих, и каждый рассказывал свою историю о том, кто они такие и зачем пришли."
)


def gutenberg(path, marker, words):
    t = open(path, encoding="utf-8").read()
    t = t[t.find(marker):]
    t = re.sub(r"\[Illustration[^\]]*\]", " ", t)
    t = re.sub(r"_", "", t)
    paras = [re.sub(r"\s+", " ", p).strip() for p in re.split(r"\n\s*\n", t)]
    return " ".join(p for p in paras if p and not p.startswith("CHAPTER")).split()[:words]


def fit(words, rect, size):
    """The largest prefix of words that fits in rect at this size; returns (count, text)."""
    lo, hi = 1, len(words)
    while lo < hi:
        mid = (lo + hi + 1) // 2
        doc = fitz.open()
        p = doc.new_page(width=W * 2, height=H * 2)
        if p.insert_textbox(rect, " ".join(words[:mid]), fontsize=size, fontfile=SERIF, fontname="F0") >= 0:
            lo = mid
        else:
            hi = mid - 1
    return lo, " ".join(words[:lo])


def vector_page(doc, w, h, boxes, extras=()):
    page = doc.new_page(width=w, height=h)
    for rect, text, size in list(boxes) + list(extras):
        assert page.insert_textbox(rect, text, fontsize=size, fontfile=SERIF, fontname="F0") >= 0
    return page


def raster(pdf_bytes, dpi, degrade=False, seed=1):
    """Renders page 1 to grayscale at dpi; with degrade, skews, blurs, adds noise and JPEG artefacts at 150 dpi."""
    src = fitz.open("pdf", pdf_bytes)
    pix = src[0].get_pixmap(dpi=dpi, colorspace=fitz.csGRAY)
    img = Image.frombytes("L", (pix.width, pix.height), pix.samples)
    quality = 85
    if degrade:
        img = img.rotate(2.0, resample=Image.BICUBIC, expand=False, fillcolor=255)
        img = img.filter(ImageFilter.GaussianBlur(1.2))
        a = np.asarray(img).astype(np.float32)
        a += np.random.default_rng(seed).normal(0, 18, a.shape)
        a = a * 0.85 + 30  # greyer paper, weaker ink
        img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
        img = img.resize((img.width // 2, img.height // 2), Image.BILINEAR)
        quality = 25
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=quality)
    w, h = src[0].rect.width, src[0].rect.height
    out = fitz.open()
    out.new_page(width=w, height=h).insert_image(fitz.Rect(0, 0, w, h), stream=buf.getvalue())
    return out.tobytes(deflate=True)


def main(pp, mc, outdir):
    import os
    os.makedirs(outdir, exist_ok=True)
    eng = gutenberg(pp, "CHAPTER XX.", 4000)
    fra = gutenberg(mc, "Le 24 février 1815", 1500)
    rus = RUSSIAN.split()
    margin = fitz.Rect(54, 54, W - 54, H - 54)
    cases = {}

    def single(words, size, rect=margin, w=W, h=H, extras=()):
        n, text = fit(words, rect, size)
        doc = fitz.open()
        vector_page(doc, w, h, [(rect, text, size)], extras)
        return doc.tobytes(), text, words[n:]

    rest = eng
    pdf, text, rest = single(rest, 11)
    cases["clean-scan"] = (raster(pdf, 300), text)
    cases["text-layer"] = (pdf, text)
    pdf, text, rest = single(rest, 11)
    cases["poor-scan"] = (raster(pdf, 300, degrade=True), text)
    pdf, text, rest = single(rest, 7)
    cases["small-print"] = (raster(pdf, 300), text)
    # Running head, page number and a footnote: only the body is ground truth.
    body = fitz.Rect(54, 72, W - 54, H - 130)
    n, text = fit(rest, body, 11)
    rest = rest[n:]
    note = "1 The first edition of 1813 prints this sentence without the comma; later editions add it."
    extras = [
        (fitz.Rect(54, 36, W - 54, 54), "PRIDE AND PREJUDICE", 9),
        (fitz.Rect(54, H - 120, W - 54, H - 70), note, 8),
        (fitz.Rect(W / 2 - 20, H - 50, W / 2 + 20, H - 36), "137", 9),
    ]
    doc = fitz.open()
    vector_page(doc, W, H, [(body, text, 11)], extras)
    cases["headers-footnotes"] = (raster(doc.tobytes(), 300), text)
    # Two columns on a letter page.
    lw, lh = 8.5 * 72, 11 * 72
    left, right = fitz.Rect(54, 54, lw / 2 - 9, lh - 54), fitz.Rect(lw / 2 + 9, 54, lw - 54, lh - 54)
    n1, t1 = fit(rest, left, 10)
    n2, t2 = fit(rest[n1:], right, 10)
    rest = rest[n1 + n2:]
    doc = fitz.open()
    vector_page(doc, lw, lh, [(left, t1, 10), (right, t2, 10)])
    cases["two-column"] = (raster(doc.tobytes(), 300), t1 + " " + t2)
    pdf, text, _ = single(fra, 11)
    cases["french"] = (raster(pdf, 300), text)
    pdf, text, _ = single(rus, 11)
    cases["russian"] = (raster(pdf, 300), text)

    for name, (pdf, text) in cases.items():
        open(f"{outdir}/{name}.pdf", "wb").write(pdf)
        open(f"{outdir}/{name}.txt", "w", encoding="utf-8").write(text + "\n")
        print(name, len(text.split()), "words", len(pdf) // 1024, "KiB")


if __name__ == "__main__":
    main(*sys.argv[1:4])
