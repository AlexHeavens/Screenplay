#!/usr/bin/env python3
"""Reduces Project Gutenberg's bulk RDF feed to the small file the app searches.

Usage: build-catalogue.py rdf-files.tar.bz2 catalogue.tsv.gz --build-date 2026-11-14

Input is `feeds/rdf-files.tar.bz2` (one request, updated daily; see docs/gutenberg.md). Output is xz-compressed tab-separated text:

    line 1   `catalogue-format 1 build <date>`   the version line: format number, then the date the feed was built
    line 2   `tags<TAB>tag<TAB>tag...`           every bookshelf and subject used below, once each
    line 3+  `id<TAB>title<TAB>authors<TAB>languages<TAB>downloads<TAB>tag numbers`

`authors` are "Last, First" names joined with "; ", `languages` are codes joined with ",", and `tag numbers` are positions in line 2
joined with ",". Only books that are text, public domain in the USA and have an EPUB are kept. The output is the same bytes for the
same feed (rows by id; xz has no timestamp), so a caller can compare hashes to see whether anything changed.
"""
import argparse
import lzma
import sys
import tarfile
import xml.etree.ElementTree as ET

NS = {
    "pg": "http://www.gutenberg.org/2009/pgterms/",
    "dc": "http://purl.org/dc/terms/",
    "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "dcam": "http://purl.org/dc/dcam/",
}
RDF_ABOUT = "{%s}about" % NS["rdf"]
RDF_RESOURCE = "{%s}resource" % NS["rdf"]
FORMAT_VERSION = 1


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


def write(rows, path, build_date):
    # Most used tags first, so the numbers that appear most are the shortest.
    counts = {}
    for row in rows:
        for tag in row[5]:
            counts[tag] = counts.get(tag, 0) + 1
    tags = sorted(counts, key=lambda t: (-counts[t], t))
    number = {t: str(i) for i, t in enumerate(tags)}
    lines = ["catalogue-format %d build %s" % (FORMAT_VERSION, build_date), "tags\t" + "\t".join(tags)]
    for ident, title, authors, languages, downloads, row_tags in rows:
        lines.append("\t".join([str(ident), title, authors, languages, str(downloads), ",".join(number[t] for t in row_tags)]))
    # CRC32 is enough for a file the app also parses; the app's xz decoder reads it.
    with lzma.open(path, "wb", format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC32, preset=9 | lzma.PRESET_EXTREME) as out:
        out.write(("\n".join(lines) + "\n").encode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("archive")
    parser.add_argument("output")
    parser.add_argument("--build-date", required=True, help="the date the feed was built, YYYY-MM-DD")
    args = parser.parse_args()
    rows = read(args.archive)
    if len(rows) < 10_000:
        sys.exit("only %d books survived: the feed has changed shape, so the old catalogue stays" % len(rows))
    write(rows, args.output, args.build_date)
    print("%d books -> %s" % (len(rows), args.output))


if __name__ == "__main__":
    main()
