#!/usr/bin/env python3
"""Writes a large download to stdout in order, fetching it in parallel byte ranges.

Usage: fetch-ranges.py URL | gzip --decompress --stdout | ...

archive.org gives one connection about 0.6 MB/s but several together about 2 MB/s each, so the 12.6 GB Open Library editions dump takes
more than 5 hours on one connection and about 90 minutes on six from a home connection. Nothing is stored: a window of chunks is in flight, and each is written out
once all the chunks before it are. A chunk that fails is fetched again (up to 5 times), so one dropped connection does not restart the dump.
"""
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

CHUNK = 16 * 1024 * 1024
WORKERS = 6
AGENT = "Screenplay catalogue job (+https://github.com/AlexHeavens/Screenplay)"


def request(url, headers=None, method="GET"):
    return urllib.request.Request(url, headers={"User-Agent": AGENT, **(headers or {})}, method=method)


def fetch(url, start, end):
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request(url, {"Range": "bytes=%d-%d" % (start, end)}), timeout=120) as response:
                data = response.read()
            if len(data) == end - start + 1:
                return data
        except OSError as error:
            print("chunk at %d failed (%s), attempt %d" % (start, error, attempt + 1), file=sys.stderr)
        time.sleep(2 ** attempt)
    sys.exit("chunk at %d failed 5 times" % start)


def main():
    # urlopen follows the redirect from openlibrary.org to the archive.org file; ranges go to the final address.
    with urllib.request.urlopen(request(sys.argv[1], method="HEAD"), timeout=120) as head:
        url, size = head.geturl(), int(head.headers["Content-Length"])
    print("fetching %d bytes from %s" % (size, url), file=sys.stderr)
    chunks = [(start, min(start + CHUNK, size) - 1) for start in range(0, size, CHUNK)]
    out = sys.stdout.buffer
    with ThreadPoolExecutor(WORKERS) as pool:
        pending = []
        for chunk in chunks:
            pending.append(pool.submit(fetch, url, *chunk))
            while len(pending) >= WORKERS * 2:  # Bounds memory: at most 12 chunks (192 MB) are held.
                out.write(pending.pop(0).result())
        for future in pending:
            out.write(future.result())
    out.flush()


if __name__ == "__main__":
    main()
