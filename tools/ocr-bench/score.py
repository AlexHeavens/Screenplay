"""Scores run files against the truth and compares them with a baseline.

The first run file is the baseline; each later one gets a delta column. body_err is the share of body words lost or
wrong (what a listener misses), ins is words spoken that are not body text (running heads, notes, contents pages).
Usage: python -I score.py <baseline.json> [<run.json> ...]
"""
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus  # noqa: E402
import runfile  # noqa: E402
from metrics import score  # noqa: E402


def scored(path):
    run = runfile.read(path)
    rows = {c: v | score(corpus.truth(c), v["text"]) for c, v in run["cases"].items() if c in corpus.CASES}
    return run, rows


def total(rows):
    m = [rows[c] for c in corpus.TOTAL if c in rows]
    words = sum(r["words"] for r in m)
    return sum(r["sub"] + r["del"] for r in m) / words if words else float("nan")


def s_per_page(r):
    return r["render_s"] + r["recognise_s"] + r["layout_ms"] / 1000


def main(paths):
    runs = [scored(p) for p in paths]
    names = [r["name"] for r, _ in runs]
    print("Body words lost or wrong (body_err), words inserted (ins), seconds per page (s)\n")
    print("| case | " + " | ".join(f"{n} body_err | ins | s" for n in names) + " |")
    print("|---|" + "---|---|---|" * len(names))
    base = runs[0][1]
    for c in corpus.CASES:
        cells = []
        for i, (_, rows) in enumerate(runs):
            r = rows.get(c)
            if r is None:
                cells += ["", "", ""]
                continue
            delta = f" ({r['body_err'] - base[c]['body_err']:+.1%})" if i and c in base else ""
            cells += [f"{r['body_err']:.1%}{delta}", str(r["ins"]), f"{s_per_page(r):.2f}"]
        print(f"| {c} | " + " | ".join(cells) + " |")
    cells = []
    for i, (_, rows) in enumerate(runs):
        delta = f" ({total(rows) - total(base):+.1%})" if i else ""
        med = statistics.median(s_per_page(rows[c]) for c in corpus.TOTAL if c in rows)
        cells += [f"**{total(rows):.1%}**{delta}", str(sum(rows[c]["ins"] for c in corpus.TOTAL if c in rows)), f"{med:.2f} median"]
    print("| **total, scans only** | " + " | ".join(cells) + " |")
    print()
    for run, _ in runs:
        print(f"- {run['name']}: {run['engine']} {run['settings']}, {run['host']}, commit {run['commit']}, {run['date']}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
