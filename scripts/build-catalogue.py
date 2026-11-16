#!/usr/bin/env python3
"""Reduces Project Gutenberg's bulk RDF feed to the small file the app searches.

Usage: build-catalogue.py rdf-files.tar.bz2 catalogue.tsv.xz --build-date 2026-11-14 [--ratings ol-ratings.tsv.xz]

Input is `feeds/rdf-files.tar.bz2` (one request, updated daily; see docs/gutenberg.md). Output is xz-compressed tab-separated text:

    line 1   `catalogue-format 2 build <date>`   the version line: format number, then the date the feed was built
    line 2   `tags<TAB>tag<TAB>tag...`           every bookshelf and subject used below, once each
    line 3+  `id<TAB>title<TAB>authors<TAB>languages<TAB>downloads<TAB>tag numbers<TAB>rating x10<TAB>rating count`

`authors` are "Last, First" names joined with "; ", `languages` are codes joined with ",", and `tag numbers` are positions in line 2
joined with ",". `rating x10` is the average reader rating times ten (42 is 4.2 stars) and `rating count` how many readers gave one; both are
empty for a book nobody rated. They come from Open Library (see build-ratings.py and docs/gutenberg.md). Format 1, which the first app
version reads, had no last two columns. Only books that are text, public domain in the USA and have an EPUB are kept. The output is the same bytes for the
same feed (rows by id; xz has no timestamp), so a caller can compare hashes to see whether anything changed.
"""
import argparse
import lzma
import re
import sys
import tarfile
import unicodedata
import xml.etree.ElementTree as ET

NS = {
    "pg": "http://www.gutenberg.org/2009/pgterms/",
    "dc": "http://purl.org/dc/terms/",
    "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "dcam": "http://purl.org/dc/dcam/",
}
RDF_ABOUT = "{%s}about" % NS["rdf"]
RDF_RESOURCE = "{%s}resource" % NS["rdf"]
FORMAT_VERSION = 2


def clean(text):
    """One line, no tabs: the file is tab separated and read line by line."""
    return " ".join((text or "").split())


MAX_TITLE = 80


def short(title):
    """The first line of the title, at most MAX_TITLE characters. The feed puts a subtitle on later lines, and long subtitles were 40% of the file."""
    first = clean(title.split("\n")[0])
    return first if len(first) <= MAX_TITLE else first[: MAX_TITLE - 1].rstrip() + "…"


def values(ebook, path):
    """The `rdf:value` text under each `path` element (a subject, a language, a type, a bookshelf)."""
    out = []
    for node in ebook.findall(path, NS):
        value = node.find(".//rdf:value", NS)
        if value is not None and clean(value.text):
            out.append(clean(value.text))
    return out


def subjects(ebook):
    """Library of Congress subject headings only, each split at " -- " so "England -- Fiction" gives the tags "England" and "Fiction":
    the parts repeat across books, which keeps the file small. The call-number subjects (LCC) say nothing a reader would search."""
    out = []
    for node in ebook.findall("dc:subject", NS):
        member = node.find(".//dcam:memberOf", NS)
        value = node.find(".//rdf:value", NS)
        if member is not None and member.get(RDF_RESOURCE, "").endswith("/LCSH") and value is not None and clean(value.text):
            out.extend(part for part in (p.strip() for p in clean(value.text).split(" -- ")) if part)
    return out


def book(ebook):
    """One row for an `ebook` element, or None when the app cannot offer it."""
    about = ebook.get(RDF_ABOUT, "")
    if not about.startswith("ebooks/"):
        return None
    ident = int(about.split("/")[1])
    rights = clean(ebook.findtext("dc:rights", "", NS))
    if not rights.startswith("Public domain in the USA"):
        return None
    if values(ebook, "dc:type") != ["Text"]:
        return None
    # The app builds the link as https://www.gutenberg.org/ebooks/<id>.epub3.images, so the feed must list that file.
    epub = "https://www.gutenberg.org/ebooks/%d.epub3.images" % ident
    if not any(f.get(RDF_ABOUT) == epub for f in ebook.iterfind("dc:hasFormat/pg:file", NS)):
        return None
    title = short(ebook.findtext("dc:title", "", NS) or "")
    if not title:
        return None
    authors = [clean(n.text) for n in ebook.iterfind("dc:creator/pg:agent/pg:name", NS) if clean(n.text)]
    languages = values(ebook, "dc:language")
    downloads = int(ebook.findtext("pg:downloads", "0", NS) or 0)
    tags = values(ebook, "pg:bookshelf") + subjects(ebook)
    return ident, title, "; ".join(authors), ",".join(languages), downloads, list(dict.fromkeys(tags))


def read(archive):
    rows = []
    with tarfile.open(archive, "r|bz2") as tar:
        for member in tar:
            if not member.isfile() or not member.name.endswith(".rdf"):
                continue
            root = ET.parse(tar.extractfile(member)).getroot()
            ebook = root.find("pg:ebook", NS)
            row = book(ebook) if ebook is not None else None
            if row:
                rows.append(row)
    rows.sort(key=lambda r: r[0])
    return rows


def fold(text):
    return "".join(c for c in unicodedata.normalize("NFD", text.lower()) if unicodedata.category(c) != "Mn")


def title_key(title):
    """The title as books of different editions share it: lower case, no accents, no subtitle or "or, ...", no leading article,
    letters and digits only."""
    title = re.split(r"[:;(]| or,? ", fold(title))[0].strip()
    return re.sub(r"[^a-z0-9]", "", re.sub(r"^(the|a|an) ", "", title))


def surname_key(name):
    """The last word of "Lewis Carroll" (Open Library) or the part before the comma of "Carroll, Lewis" (Gutenberg), letters only."""
    return re.sub(r"[^a-z]", "", fold(name.split(",")[0] if "," in name else name.split()[-1] if name.split() else ""))


def read_ratings(path, books):
    """Gutenberg id -> (average x10, count) for the books in `books` (rows from read()), from the file build-ratings.py wrote.
    A `g` row names the Gutenberg id. Otherwise a `w` row (a work) rates the book with the same title and an author with the same
    surname; if several works match, the one with most ratings."""
    by_id, by_title = {}, {}
    with lzma.open(path, "rt", encoding="utf-8") as lines:
        next(lines)  # The version line, which names the dump the ratings are from.
        for line in lines:
            kind, *fields = line.rstrip("\n").split("\t")
            if kind == "g":
                by_id[int(fields[0])] = (int(fields[1]), int(fields[2]))
            elif kind == "w":
                title, authors, average, count = fields[0], fields[1], int(fields[2]), int(fields[3])
                for author in authors.split("; "):
                    key = (title_key(title), surname_key(author))
                    if not all(key):
                        continue
                    if key not in by_title or by_title[key][1] < count:
                        by_title[key] = (average, count)
    out = dict(by_id)
    for ident, title, authors, *_ in books:
        if ident not in out:
            matches = [by_title[k] for a in authors.split("; ") if (k := (title_key(title), surname_key(a))) in by_title]
            if matches:
                out[ident] = max(matches, key=lambda m: m[1])
    return out


def write(rows, path, build_date, ratings):
    # Most used tags first, so the numbers that appear most are the shortest.
    counts = {}
    for row in rows:
        for tag in row[5]:
            counts[tag] = counts.get(tag, 0) + 1
    tags = sorted(counts, key=lambda t: (-counts[t], t))
    number = {t: str(i) for i, t in enumerate(tags)}
    lines = ["catalogue-format %d build %s" % (FORMAT_VERSION, build_date), "tags\t" + "\t".join(tags)]
    for ident, title, authors, languages, downloads, row_tags in rows:
        average, count = ratings.get(ident, ("", ""))
        lines.append("\t".join([str(ident), title, authors, languages, str(downloads), ",".join(number[t] for t in row_tags), str(average), str(count)]))
    # CRC32 is enough for a file the app also parses; the app's xz decoder reads it.
    with lzma.open(path, "wb", format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC32, preset=9 | lzma.PRESET_EXTREME) as out:
        out.write(("\n".join(lines) + "\n").encode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("archive")
    parser.add_argument("output")
    parser.add_argument("--build-date", required=True, help="the date the feed was built, YYYY-MM-DD")
    parser.add_argument("--ratings", help="ol-ratings.tsv.xz from build-ratings.py; without it no book has a rating")
    parser.add_argument("--min-books", type=int, default=10_000, help="fewer books than this means the feed changed shape and nothing is written; a test fixture lowers it")
    args = parser.parse_args()
    rows = read(args.archive)
    if len(rows) < args.min_books:
        sys.exit("only %d books survived: the feed has changed shape, so the old catalogue stays" % len(rows))
    ratings = read_ratings(args.ratings, rows) if args.ratings else {}
    write(rows, args.output, args.build_date, ratings)
    print("%d books, %d with a rating -> %s" % (len(rows), sum(1 for r in rows if r[0] in ratings), args.output))


if __name__ == "__main__":
    main()
