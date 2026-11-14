"""Prints the word errors of one case in a run file against its truth, grouped into runs of errors.
Usage: python -I diff.py <run.json> <case>"""
import os
import sys

import jiwer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus  # noqa: E402
import runfile  # noqa: E402
from metrics import norm_words  # noqa: E402

run, case = runfile.read(sys.argv[1]), sys.argv[2]
truth, hyp = norm_words(corpus.truth(case)), norm_words(run["cases"][case]["text"])
out = jiwer.process_words(truth, hyp or "∅")
r, h = truth.split(), hyp.split()
for chunk in out.alignments[0]:
    if chunk.type == "equal":
        continue
    ref = " ".join(r[chunk.ref_start_idx:chunk.ref_end_idx])
    got = " ".join(h[chunk.hyp_start_idx:chunk.hyp_end_idx])
    ctx = " ".join(r[max(0, chunk.ref_start_idx - 3):chunk.ref_start_idx])
    print(f"{chunk.type:10} …{ctx} [{ref[:150]}] -> [{got[:150]}]")
