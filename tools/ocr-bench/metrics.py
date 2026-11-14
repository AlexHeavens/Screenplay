"""Word and character error rates against ground truth, normalised the same way for every engine."""
import re
import unicodedata

import jiwer

QUOTES = str.maketrans({"“": '"', "”": '"', "„": '"', "«": '"', "»": '"', "‘": "'", "’": "'", "—": " ", "–": " ", "­": ""})
# Same join as Layout.softHyphen: a lowercase letter, a hyphen and a line break, then a lowercase letter.
SOFT_HYPHEN = re.compile(r"(?<=[^\W\d_])-\s*\n\s*(?=[a-zà-ÿа-я])")


def norm_chars(s):
    s = unicodedata.normalize("NFKC", s).translate(QUOTES)
    s = SOFT_HYPHEN.sub("", s)
    return re.sub(r"\s+", " ", s).strip()


def norm_words(s):
    s = norm_chars(s).lower()
    s = re.sub(r"[^\w\s'-]", " ", s)
    s = re.sub(r"(?<!\w)['-]|['-](?!\w)", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def score(truth, hyp):
    """WER and CER, plus the alignment counts. body_err ignores inserted words (running heads, notes the
    truth leaves out), so it measures how much of the body text is lost or wrong."""
    r, h = norm_words(truth), norm_words(hyp) or "∅"
    w = jiwer.process_words(r, h)
    n = w.hits + w.substitutions + w.deletions
    c = jiwer.process_characters(norm_chars(truth), norm_chars(hyp) or "∅")
    return {
        "words": n,
        "wer": (w.substitutions + w.deletions + w.insertions) / n,
        "body_err": (w.substitutions + w.deletions) / n,
        "sub": w.substitutions,
        "del": w.deletions,
        "ins": w.insertions,
        "cer": c.cer,
    }
