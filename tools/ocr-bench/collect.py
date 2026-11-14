"""Turns OcrBenchmarkTest's output (one JSON per PDF, width and mode) into run files: one with ML Kit's raw lines
and one with Layout's paragraphs (what the app speaks) for each width and mode.
Usage: python -I collect.py <ocr-bench-out-dir> [--host "emulator x86_64 2 cores"] [--out results]
"""
import argparse
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus  # noqa: E402
import runfile  # noqa: E402


def main():
    p = argparse.ArgumentParser()
    p.add_argument("dir")
    p.add_argument("--host", default="emulator-5580 x86_64")
    p.add_argument("--out", default=runfile.RESULTS)
    a = p.parse_args()
    runs = {}
    for f in sorted(glob.glob(os.path.join(a.dir, "*-*-*.json"))):
        if f.endswith("-production.json"):
            continue
        with open(f, encoding="utf-8") as fh:
            d = json.load(fh)
        case = os.path.basename(f).rsplit("-", 2)[0]
        if case not in corpus.CASES:
            continue
        t = d["totals"]
        base = {"pages": t["pages"], "render_s": t["render_ms"] / 1000, "recognise_s": t["recognise_ms"] / 1000}
        for kind in ("raw", "layout"):
            text = "\n".join("\n".join(p[kind]) for p in d["pages"])
            layout_ms = t["layout_ms"] if kind == "layout" else 0
            runs.setdefault((kind, d["width"], d["mode"]), {})[case] = base | {"layout_ms": layout_ms, "text": text}
    for (kind, width, mode), cases in sorted(runs.items()):
        name = f"mlkit-{width}-{mode}" + ("-raw" if kind == "raw" else "")
        print(runfile.write(name, "mlkit-bundled-latin", {"width": width, "mode": mode, "output": kind}, a.host, cases, a.out))


if __name__ == "__main__":
    main()
