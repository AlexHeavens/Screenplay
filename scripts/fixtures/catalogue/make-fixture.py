#!/usr/bin/env python3
"""Writes the small inputs golden.yml feeds to scripts/build-catalogue.py: rdf-sample.tar.bz2 (a few Gutenberg-shaped RDF records) and
ol-ratings-sample.tsv.xz (ratings in build-ratings.py's output format). Same bytes every run. Usage: make-fixture.py OUTDIR"""
import io
import lzma
import os
import sys
import tarfile

BOOKS = [
    # id, title, authors, languages, downloads, bookshelves, subjects
    (1342, "Pride and Prejudice", ["Austen, Jane"], ["en"], 90000, ["Category: Novels", "Best Books Ever Listings"], ["England -- Fiction", "Love stories"]),
    (84, "Frankenstein; Or, The Modern Prometheus", ["Shelley, Mary Wollstonecraft"], ["en"], 80000, ["Category: Science Fiction"], ["Science fiction", "Monsters -- Fiction"]),
    (1184, "Le Comte de Monte-Cristo", ["Dumas, Alexandre"], ["fr"], 30000, ["Category: Adventure"], ["Adventure stories"]),
    (2701, "Moby Dick; Or, The Whale", ["Melville, Herman"], ["en"], 70000, ["Category: American Literature"], ["Whaling -- Fiction"]),
    (345, "Dracula", ["Stoker, Bram"], ["en"], 60000, [], ["Horror tales"]),
    (11, "Alice's Adventures in Wonderland", ["Carroll, Lewis", "Tenniel, John"], ["en"], 75000, ["Category: Children & Young Adult Reading"], ["Fantasy fiction"]),
    (1661, "The Adventures of Sherlock Holmes", ["Doyle, Arthur Conan"], ["en"], 65000, ["Category: Mystery"], ["Detective and mystery stories"]),
    (2000, "Don Quijote", ["Cervantes Saavedra, Miguel de"], ["es", "en"], 20000, [], ["Spain -- Fiction"]),
]
R = ('<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:pgterms="http://www.gutenberg.org/2009/pgterms/" '
     'xmlns:dcam="http://purl.org/dc/dcam/">\n<pgterms:ebook rdf:about="ebooks/%d">\n%s</pgterms:ebook>\n</rdf:RDF>\n')


def record(ident, title, authors, languages, downloads, shelves, subjects):
    parts = ['<dcterms:rights>Public domain in the USA.</dcterms:rights>\n', '<dcterms:title>%s</dcterms:title>\n' % title.replace("&", "&amp;")]
    for a in authors:
        parts.append("<dcterms:creator><pgterms:agent><pgterms:name>%s</pgterms:name></pgterms:agent></dcterms:creator>\n" % a)
    parts.append('<dcterms:type><rdf:Description><rdf:value>Text</rdf:value></rdf:Description></dcterms:type>\n')
    parts.append('<dcterms:hasFormat><pgterms:file rdf:about="https://www.gutenberg.org/ebooks/%d.epub3.images"/></dcterms:hasFormat>\n' % ident)
    for code in languages:
        parts.append("<dcterms:language><rdf:Description><rdf:value>%s</rdf:value></rdf:Description></dcterms:language>\n" % code)
    parts.append('<pgterms:downloads>%d</pgterms:downloads>\n' % downloads)
    for s in shelves:
        parts.append("<pgterms:bookshelf><rdf:Description><rdf:value>%s</rdf:value></rdf:Description></pgterms:bookshelf>\n" % s.replace("&", "&amp;"))
    for s in subjects:
        parts.append('<dcterms:subject><rdf:Description><dcam:memberOf rdf:resource="http://purl.org/dc/terms/LCSH"/><rdf:value>%s</rdf:value></rdf:Description></dcterms:subject>\n' % s)
    return (R % (ident, "".join(parts))).encode("utf-8")


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    with tarfile.open(os.path.join(out, "rdf-sample.tar.bz2"), "w:bz2") as tar:
        for book in BOOKS:
            data = record(*book)
            info = tarfile.TarInfo("cache/epub/%d/pg%d.rdf" % (book[0], book[0]))
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    ratings = ["ol-ratings 1 dump 2026-10-01", "g\t1342\t43\t120", "g\t84\t41\t3", "w\tMoby Dick\tHerman Melville\t39\t57", "w\tDracula\tBram Stoker\t38\t12"]
    with lzma.open(os.path.join(out, "ol-ratings-sample.tsv.xz"), "wt", encoding="utf-8", format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC32) as f:
        f.write("\n".join(ratings) + "\n")


if __name__ == "__main__":
    main()
