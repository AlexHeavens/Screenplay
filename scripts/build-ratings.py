#!/usr/bin/env python3
"""Reduces Open Library's monthly dumps to the ratings of Project Gutenberg books. Three steps, run by scripts/publish-catalogue.sh:

  build-ratings.py work-keys ol_dump_ratings.txt.gz work-keys.txt
      the keys of works with at least MIN_RATINGS ratings, one per line (the job greps the works dump for them)
  build-ratings.py author-keys works.txt author-keys.txt
      the author keys those works name (the job greps the authors dump for them)
  build-ratings.py build ol_dump_ratings.txt.gz editions.txt works.txt authors.txt ol-ratings.tsv.xz --dump-date 2026-10-31
      the file build-catalogue.py reads

Inputs for `build` (the job keeps only the lines it needs while it streams the dumps, so nothing large is stored; each is
`type<TAB>key<TAB>JSON record`):
  ratings dump  one row per rating: `work key<TAB>edition key or \\N<TAB>rating 1-5<TAB>date`
  editions      the editions that mention `project_gutenberg` (12.6 GB dump; the strongest link, but only about 2,400 editions carry it)
  works         the works with at least MIN_RATINGS ratings (about 19,000): title and author keys
  authors       the authors of those works: name
Output is xz-compressed text. Line 1 `openlibrary-ratings 2 dump <date>`, then two kinds of row, each with `average x10<TAB>rating count`
at the end (an average x10 is a whole number from 10 to 50, 4.2 is 42):
  `g<TAB>gutenberg id<TAB>...`            a Gutenberg id named by an edition: the ratings of the work that edition belongs to
  `w<TAB>title<TAB>authors<TAB>...`      a work with MIN_RATINGS ratings or more, for build-catalogue.py to match by title and author name
If several works claim one Gutenberg id, the one with most ratings wins.
"""
import argparse
import gzip
import json
import lzma
import sys

FORMAT_VERSION = 2
MIN_RATINGS = 5  # The app ranks a book by its average only from this many ratings (MIN_RATINGS in Catalogue.kt).


def read_ratings(path):
    """work key -> (sum, count). The work's rows are summed, whatever edition they name."""
    totals = {}
    with gzip.open(path, "rt", encoding="utf-8") as lines:
        for line in lines:
            fields = line.rstrip("\n").split("\t")
            if len(fields) < 3 or not fields[2].isdigit():
                continue
            total, count = totals.get(fields[0], (0, 0))
            totals[fields[0]] = (total + int(fields[2]), count + 1)
    return totals


def records(path, kind):
    """(key, record) for each line of a dump extract that has the type `kind`."""
    with open(path, encoding="utf-8") as lines:
        for line in lines:
            fields = line.rstrip("\n").split("\t")
            if len(fields) == 3 and fields[0] == kind:
                yield fields[1], json.loads(fields[2])


def gutenberg_works(path):
    """Gutenberg id -> work keys, from the edition records that name a Project Gutenberg id."""
    out = {}
    for _, record in records(path, "/type/edition"):
        ids = (record.get("identifiers") or {}).get("project_gutenberg") or []
        works = [w["key"] for w in record.get("works") or [] if "key" in w]
        for ident in ids:
            if str(ident).isdigit():
                out.setdefault(int(ident), set()).update(works)
    return out


def author_keys_of(record):
    return [a["author"]["key"] for a in record.get("authors") or [] if "key" in (a.get("author") or {})]


def one_line(text):
    """Any run of whitespace, tabs and line breaks (even \\r) included, becomes one space, so a value cannot split a row."""
    return " ".join(text.split())


def author_name(record):
    return one_line(record.get("name") or record.get("personal_name") or "")


def by_id(totals, works):
    """(Gutenberg id, average x10, count) for each id whose work has ratings; the work with most ratings wins."""
    rows = []
    for ident, keys in works.items():
        rated = [totals[k] for k in keys if k in totals]
        if rated:
            total, count = max(rated, key=lambda t: t[1])
            rows.append((ident, round(total * 10 / count), count))
    return sorted(rows)


def by_title(totals, works, authors):
    """(title, authors, average x10, count) for each work with MIN_RATINGS ratings or more, so that build-catalogue.py can match
    it to a book by title and author name. `works` is key -> record, `authors` is key -> name."""
    rows = []
    for key, record in works.items():
        total, count = totals.get(key, (0, 0))
        names = [authors[a] for a in author_keys_of(record) if authors.get(a)]
        title = one_line(record.get("title") or "")
        if count >= MIN_RATINGS and title and names:
            rows.append((title, "; ".join(one_line(n) for n in names), round(total * 10 / count), count))
    return sorted(rows)


def write(id_rows, title_rows, path, dump_date):
    text = "openlibrary-ratings %d dump %s\n" % (FORMAT_VERSION, dump_date)
    text += "".join("g\t%d\t%d\t%d\n" % row for row in id_rows)
    text += "".join("w\t%s\t%s\t%d\t%d\n" % row for row in title_rows)
    with lzma.open(path, "wb", format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC32, preset=9 | lzma.PRESET_EXTREME) as out:
        out.write(text.encode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    steps = parser.add_subparsers(dest="step", required=True)
    step = steps.add_parser("work-keys")
    step.add_argument("ratings")
    step.add_argument("output")
    step = steps.add_parser("author-keys")
    step.add_argument("works")
    step.add_argument("output")
    step = steps.add_parser("build")
    for name in ("ratings", "editions", "works", "authors", "output"):
        step.add_argument(name)
    step.add_argument("--dump-date", required=True, help="the date in the dump's file names, YYYY-MM-DD")
    args = parser.parse_args()
    if args.step == "work-keys":
        totals = read_ratings(args.ratings)
        with open(args.output, "w", encoding="utf-8") as out:
            out.write("".join(k + "\n" for k, (_, count) in sorted(totals.items()) if count >= MIN_RATINGS))
    elif args.step == "author-keys":
        keys = {a for _, record in records(args.works, "/type/work") for a in author_keys_of(record)}
        with open(args.output, "w", encoding="utf-8") as out:
            out.write("".join(k + "\n" for k in sorted(keys)))
    else:
        totals = read_ratings(args.ratings)
        works = dict(records(args.works, "/type/work"))
        authors = {k: author_name(r) for k, r in records(args.authors, "/type/author")}
        id_rows = by_id(totals, gutenberg_works(args.editions))
        title_rows = by_title(totals, works, authors)
        if len(id_rows) < 100 or len(title_rows) < 1_000:
            sys.exit("only %d ids and %d titles have ratings: the dump has changed shape, so the cached ratings stay" % (len(id_rows), len(title_rows)))
        write(id_rows, title_rows, args.output, args.dump_date)
        print("%d Gutenberg ids and %d titled works with ratings -> %s" % (len(id_rows), len(title_rows), args.output))


if __name__ == "__main__":
    main()
