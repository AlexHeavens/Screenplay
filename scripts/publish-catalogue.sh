#!/usr/bin/env bash
# Builds the book catalogue from Project Gutenberg's bulk feed and Open Library's ratings and publishes it to the release tagged `catalogue`,
# unless nothing changed. Open Library publishes a dump each month; the dump is read only when it is newer than the one the ratings in the
# release (the asset ol-ratings.tsv.xz, whose first line names its dump) came from. Otherwise those ratings are reused.
# The built file is checked against schemas/catalogue/vN.json (scripts/validate-schema.py) before anything is uploaded.
# DRY_RUN=1 does everything except write to the release (reads still need `gh auth login` or GH_TOKEN).
# The workflow .github/workflows/catalogue.yml runs this; run it by hand the same way: scripts/publish-catalogue.sh (needs gh, curl, python3).
# One request for one bulk file, as Gutenberg's robot page asks; no per-book requests. The app downloads the result from
# https://github.com/AlexHeavens/Screenplay/releases/download/catalogue/catalogue.tsv.xz (never /releases/latest/, which will be the APK).
set -o errexit -o nounset -o pipefail
cd "$(dirname "$0")/.."

repo=${GITHUB_REPOSITORY:-AlexHeavens/Screenplay}
feed=https://www.gutenberg.org/cache/epub/feeds/rdf-files.tar.bz2
dumps=https://archive.org/download
agent="Screenplay catalogue job (+https://github.com/AlexHeavens/Screenplay)"
work=$(mktemp --directory)
trap 'rm -rf "$work"' EXIT

write() { if [ -n "${DRY_RUN:-}" ]; then echo "dry run, not run: $*"; else "$@"; fi; }
if ! gh release view catalogue --repo "$repo" >/dev/null 2>&1; then
    write gh release create catalogue --repo "$repo" --latest=false --title "Book catalogue" \
        --notes "The book list \"Catalogue\" searches, rebuilt daily from Project Gutenberg's bulk catalogue and Open Library's ratings by scripts/publish-catalogue.sh. Not an app release."
fi

# Open Library: the date of its newest dump, from where the "latest" link redirects (a HEAD request, nothing downloaded).
latest=$(curl --silent --show-error --fail --head --user-agent "$agent" https://openlibrary.org/data/ol_dump_ratings_latest.txt.gz \
    | awk 'tolower($1) == "location:" { print $2 }' | grep --only-matching --extended-regexp '[0-9]{4}-[0-9]{2}-[0-9]{2}' | tail --lines 1) || latest=
cached_date=
if gh release download catalogue --repo "$repo" --pattern ol-ratings.tsv.xz --dir "$work/cached" 2>/dev/null; then
    # awk reads the whole stream: `head` would close the pipe early, and under pipefail xz's SIGPIPE (exit 141) fails the script.
    cached_date=$(xz --decompress --stdout "$work/cached/ol-ratings.tsv.xz" | awk 'NR == 1 { print $4 }')
fi
echo "open library: newest dump ${latest:-unknown}, ratings in the release from dump ${cached_date:-none}"

if [ -n "$latest" ] && [[ "$latest" > "$cached_date" ]]; then
    echo "reading the $latest dump: ratings (9 MB), then the editions (12.6 GB), works (4 GB) and authors (0.8 GB), streamed in parallel ranges and cut down as they arrive"
    base="$dumps/ol_dump_$latest/ol_dump_%s_$latest.txt.gz"
    stream() { python3 -I scripts/fetch-ranges.py "$(printf "$base" "$1")" | gzip --decompress --stdout; }
    curl --silent --show-error --fail --location --user-agent "$agent" --output "$work/ratings.txt.gz" "$(printf "$base" ratings)"
    stream editions | grep --fixed-strings '"project_gutenberg"' | cut --fields 1,2,5 > "$work/editions.txt"
    python3 -I scripts/build-ratings.py work-keys "$work/ratings.txt.gz" "$work/work-keys.txt"
    # awk compares the whole key field, so /works/OL1W never matches /works/OL1W2.
    stream works | cut --fields 1,2,5 | awk -F '\t' 'NR == FNR { keep[$1]; next } $2 in keep' "$work/work-keys.txt" - > "$work/works.txt"
    python3 -I scripts/build-ratings.py author-keys "$work/works.txt" "$work/author-keys.txt"
    stream authors | cut --fields 1,2,5 | awk -F '\t' 'NR == FNR { keep[$1]; next } $2 in keep' "$work/author-keys.txt" - > "$work/authors.txt"
    python3 -I scripts/build-ratings.py build "$work/ratings.txt.gz" "$work/editions.txt" "$work/works.txt" "$work/authors.txt" "$work/ol-ratings.tsv.xz" --dump-date "$latest"
    rm "$work"/ratings.txt.gz "$work"/editions.txt "$work"/works.txt "$work"/authors.txt "$work"/work-keys.txt "$work"/author-keys.txt
    write gh release upload catalogue "$work/ol-ratings.tsv.xz" --repo "$repo" --clobber
    ratings="$work/ol-ratings.tsv.xz"
elif [ -n "$cached_date" ]; then
    echo "no newer dump: the catalogue is rebuilt with the ratings from dump $cached_date"
    ratings="$work/cached/ol-ratings.tsv.xz"
else
    echo "no ratings: Open Library's newest dump could not be found and the release holds none" >&2
    exit 1
fi

# Gutenberg goes down for maintenance for days at a time (the whole site answers 503). Retry a few times for a short blip, then fail
# with the status, so the log says the upstream is down and not that this script broke. The old catalogue stays published.
download_feed() {
    local attempt=1 delay=30 status
    while :; do
        status=$(curl --silent --show-error --location --user-agent "$agent" --dump-header "$work/headers" --output "$work/rdf-files.tar.bz2" \
            --write-out '%{http_code}' "$feed") || status=000
        [ "$status" = 200 ] && return 0
        if [ "$attempt" -ge 4 ]; then
            echo "Project Gutenberg's feed is unavailable (HTTP $status from $feed after $attempt attempts); the published catalogue is left as it is." >&2
            return 1
        fi
        echo "feed download failed (HTTP $status), attempt $attempt of 4; retrying in ${delay}s" >&2
        sleep "$delay"
        attempt=$((attempt + 1)) delay=$((delay * 2))
    done
}
download_feed
modified=$(awk 'tolower($1) == "last-modified:" { sub(/^[^:]*: */, ""); sub(/\r$/, ""); print }' "$work/headers" | tail --lines 1)
build_date=$(date --utc --date "$modified" +%F)

python3 -I scripts/build-catalogue.py "$work/rdf-files.tar.bz2" "$work/catalogue.tsv.xz" --build-date "$build_date" --ratings "$ratings"
# A file that breaks its schema (docs/data-specs.md) is not published: the old catalogue stays and the job fails.
format=$(xz --decompress --stdout "$work/catalogue.tsv.xz" | awk 'NR == 1 { print $2 }')
python3 -I scripts/validate-schema.py "schemas/catalogue/v$format.json" "$work/catalogue.tsv.xz"
size=$(stat --format %s "$work/catalogue.tsv.xz")
echo "catalogue built $build_date: $size bytes"

# The version line changes every day the feed is rebuilt; only the books below it say whether anything changed.
books_hash() { xz --decompress --stdout "$1" | tail --lines +2 | sha256sum | cut --delimiter ' ' --fields 1; }

if gh release download catalogue --repo "$repo" --pattern catalogue.tsv.xz --dir "$work/published" 2>/dev/null \
    && [ "$(books_hash "$work/published/catalogue.tsv.xz")" = "$(books_hash "$work/catalogue.tsv.xz")" ]; then
    echo "unchanged: the published catalogue has the same books, so nothing is uploaded"
    exit 0
fi

write gh release upload catalogue "$work/catalogue.tsv.xz" --repo "$repo" --clobber
echo "uploaded: https://github.com/$repo/releases/download/catalogue/catalogue.tsv.xz"
