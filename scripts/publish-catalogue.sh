#!/usr/bin/env bash
# Builds the book catalogue from Project Gutenberg's bulk feed and publishes it to the release tagged `catalogue`, unless nothing changed.
# The workflow .github/workflows/catalogue.yml runs this; run it by hand the same way: scripts/publish-catalogue.sh (needs gh, curl, python3).
# One request for one bulk file, as Gutenberg's robot page asks; no per-book requests. The app downloads the result from
# https://github.com/AlexHeavens/Screenplay/releases/download/catalogue/catalogue.tsv.xz (never /releases/latest/, which will be the APK).
set -o errexit -o nounset -o pipefail
cd "$(dirname "$0")/.."

repo=${GITHUB_REPOSITORY:-AlexHeavens/Screenplay}
feed=https://www.gutenberg.org/cache/epub/feeds/rdf-files.tar.bz2
agent="Screenplay catalogue job (+https://github.com/AlexHeavens/Screenplay)"
work=$(mktemp --directory)
trap 'rm -rf "$work"' EXIT

curl --silent --show-error --fail --location --user-agent "$agent" --dump-header "$work/headers" --output "$work/rdf-files.tar.bz2" "$feed"
modified=$(awk 'tolower($1) == "last-modified:" { sub(/^[^:]*: */, ""); sub(/\r$/, ""); print }' "$work/headers" | tail --lines 1)
build_date=$(date --utc --date "$modified" +%F)

python3 -I scripts/build-catalogue.py "$work/rdf-files.tar.bz2" "$work/catalogue.tsv.xz" --build-date "$build_date"
size=$(stat --format %s "$work/catalogue.tsv.xz")
echo "catalogue built $build_date: $size bytes"

# The version line changes every day the feed is rebuilt; only the books below it say whether anything changed.
books_hash() { xz --decompress --stdout "$1" | tail --lines +2 | sha256sum | cut --delimiter ' ' --fields 1; }

if ! gh release view catalogue --repo "$repo" >/dev/null 2>&1; then
    gh release create catalogue --repo "$repo" --latest=false --title "Book catalogue" \
        --notes "The book list \"Find books\" searches, rebuilt daily from Project Gutenberg's bulk catalogue by scripts/publish-catalogue.sh. Not an app release."
fi

if gh release download catalogue --repo "$repo" --pattern catalogue.tsv.xz --dir "$work/published" 2>/dev/null \
    && [ "$(books_hash "$work/published/catalogue.tsv.xz")" = "$(books_hash "$work/catalogue.tsv.xz")" ]; then
    echo "unchanged: the published catalogue has the same books, so nothing is uploaded"
    exit 0
fi

gh release upload catalogue "$work/catalogue.tsv.xz" --repo "$repo" --clobber
echo "uploaded: https://github.com/$repo/releases/download/catalogue/catalogue.tsv.xz"
