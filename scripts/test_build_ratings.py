"""Tests for build-ratings.py and its use in build-catalogue.py. Run: cd scripts && python3 -m unittest test_build_ratings"""
import atexit
import gzip
import importlib.util
import json
import lzma
import os
import shutil
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), os.path.join(HERE, name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ratings = load("build-ratings")
catalogue = load("build-catalogue")


def edition(key, work, *gutenberg):
    record = {"key": key, "works": [{"key": work}], "identifiers": {"project_gutenberg": list(gutenberg)}}
    return "/type/edition\t%s\t%s\n" % (key, json.dumps(record))


def work(key, title, *authors):
    record = {"key": key, "title": title, "authors": [{"author": {"key": a}} for a in authors]}
    return "/type/work\t%s\t%s\n" % (key, json.dumps(record))


def author(key, name):
    return "/type/author\t%s\t%s\n" % (key, json.dumps({"key": key, "name": name}))


def stars(key, *values):
    return [(key, v) for v in values]


class Fixture:
    """Writes the dump extracts the job gives build-ratings.py into a temporary folder."""

    def __init__(self, rating_rows, editions=(), works=(), authors=()):
        self.dir = tempfile.mkdtemp()
        atexit.register(shutil.rmtree, self.dir, ignore_errors=True)
        self.ratings = self.path("r.gz")
        with gzip.open(self.ratings, "wt") as f:
            f.write("".join("%s\t\\N\t%d\t2020-01-01\n" % row for row in rating_rows))
        self.editions, self.works, self.authors = (self.text(n, lines) for n, lines in (("e.txt", editions), ("w.txt", works), ("a.txt", authors)))

    def path(self, name):
        return os.path.join(self.dir, name)

    def text(self, name, lines):
        with open(self.path(name), "w") as f:
            f.write("".join(lines))
        return self.path(name)

    def build(self):
        totals = ratings.read_ratings(self.ratings)
        works = dict(ratings.records(self.works, "/type/work"))
        names = {k: ratings.author_name(r) for k, r in ratings.records(self.authors, "/type/author")}
        id_rows = ratings.by_id(totals, ratings.gutenberg_works(self.editions))
        title_rows = ratings.by_title(totals, works, names)
        out = self.path("o.xz")
        ratings.write(id_rows, title_rows, out, "2026-10-31")
        with lzma.open(out, "rt") as f:
            return id_rows, title_rows, f.read(), out


class BuildRatings(unittest.TestCase):
    def test_averages_a_work_and_gives_it_to_every_gutenberg_edition_of_it(self):
        f = Fixture(
            stars("/works/OL1W", 5, 4, 4) + stars("/works/OL2W", 1),
            editions=[edition("/books/OL1M", "/works/OL1W", "11", "12"), edition("/books/OL2M", "/works/OL2W", "20")],
        )
        id_rows, _, text, _ = f.build()
        self.assertEqual([(11, 43, 3), (12, 43, 3), (20, 10, 1)], id_rows)
        self.assertEqual("openlibrary-ratings 2 dump 2026-10-31\ng\t11\t43\t3\ng\t12\t43\t3\ng\t20\t10\t1\n", text)

    def test_a_book_without_ratings_or_with_a_non_numeric_id_is_left_out(self):
        f = Fixture(stars("/works/OL1W", 3), editions=[edition("/books/OL1M", "/works/OL9W", "5"), edition("/books/OL2M", "/works/OL1W", "abc")])
        self.assertEqual([], f.build()[0])

    def test_the_work_with_most_ratings_wins_when_two_claim_one_id(self):
        f = Fixture(
            stars("/works/OL1W", 5) + stars("/works/OL2W", 1, 1),
            editions=[edition("/books/OL1M", "/works/OL1W", "7"), edition("/books/OL2M", "/works/OL2W", "7")],
        )
        self.assertEqual([(7, 10, 2)], f.build()[0])

    def test_a_work_keeps_its_title_and_authors_only_with_enough_ratings_and_a_named_author(self):
        f = Fixture(
            stars("/works/OL1W", 5, 4, 4, 5, 5) + stars("/works/OL2W", 5, 5) + stars("/works/OL3W", 5, 5, 5, 5, 5),
            works=[work("/works/OL1W", "Emma", "/authors/OL1A"), work("/works/OL2W", "Few", "/authors/OL1A"), work("/works/OL3W", "Nameless", "/authors/OL9A")],
            authors=[author("/authors/OL1A", "Jane Austen")],
        )
        _, title_rows, text, _ = f.build()
        self.assertEqual([("Emma", "Jane Austen", 46, 5)], title_rows)
        self.assertIn("w\tEmma\tJane Austen\t46\t5\n", text)

    def test_a_title_with_a_tab_or_line_break_cannot_split_its_row(self):
        f = Fixture(
            stars("/works/OL1W", 5, 5, 5, 5, 5),
            works=[work("/works/OL1W", "Two\tparts\r\nof one\u2028title", "/authors/OL1A")],
            authors=[author("/authors/OL1A", "A\tB")],
        )
        _, title_rows, text, out = f.build()
        self.assertEqual([("Two parts of one title", "A B", 50, 5)], title_rows)
        self.assertEqual(2 + 0, len(text.splitlines()))

    def test_the_steps_that_cut_the_dumps_down_name_the_works_and_authors_that_matter(self):
        f = Fixture(
            stars("/works/OL1W", 5, 5, 5, 5, 5) + stars("/works/OL2W", 5),
            works=[work("/works/OL1W", "Emma", "/authors/OL1A", "/authors/OL2A")],
        )
        totals = ratings.read_ratings(f.ratings)
        self.assertEqual({"/works/OL1W"}, {k for k, (_, n) in totals.items() if n >= ratings.MIN_RATINGS})
        record = dict(ratings.records(f.works, "/type/work"))["/works/OL1W"]
        self.assertEqual(["/authors/OL1A", "/authors/OL2A"], ratings.author_keys_of(record))


class MatchBooksToRatings(unittest.TestCase):
    def rate(self, lines, books):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "o.xz")
            with lzma.open(path, "wt") as f:
                f.write("openlibrary-ratings 2 dump 2026-10-31\n" + lines)
            return catalogue.read_ratings(path, books)

    def book(self, ident, title, authors):
        return (ident, title, authors, "en", 0, [])

    def test_a_gutenberg_id_wins_and_otherwise_title_and_author_surname_match(self):
        lines = "g\t11\t40\t9\nw\tAlice's Adventures in Wonderland\tLewis Carroll\t42\t100\nw\tMoby Dick\tHerman Melville\t41\t50\n"
        books = [
            self.book(11, "Alice's Adventures in Wonderland", "Carroll, Lewis"),
            self.book(15, "Moby-Dick; or, The Whale", "Melville, Herman"),
            self.book(16, "Moby Dick", "Somebody Else, Jo"),
        ]
        self.assertEqual({11: (40, 9), 15: (41, 50)}, self.rate(lines, books))

    def test_titles_match_without_articles_accents_or_subtitles_and_the_work_with_most_ratings_wins(self):
        lines = "w\tThe Café: A Story\tAnne Écrivain\t30\t6\nw\tCafe\tAnne Ecrivain\t45\t60\n"
        self.assertEqual({1: (45, 60)}, self.rate(lines, [self.book(1, "Cafe (Illustrated)", "Ecrivain, Anne")]))


if __name__ == "__main__":
    unittest.main()
