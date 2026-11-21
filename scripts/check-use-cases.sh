#!/usr/bin/env bash
# Keeps docs/use-cases.md honest: every test it names must still exist, and every use case needs a `Tests:` line.
# A test is `Class.method`, found as `fun method` in a file that declares `class Class` under app/src/test or app/src/androidTest.
# An id is `UC-12` (a `###` heading) or `UC-12a` (a `####` heading under it). A parent with children is a group and needs no `Tests:` line.
# It also warns, without failing, about every `@Test` that no use case names, so new tests do not slip in unlinked.
# Needs no emulator and no Gradle. Run it by hand, or through `./gradlew testDebugUnitTest`.
set -o errexit -o nounset -o pipefail
# One byte order for sort and comm, whatever the caller's locale.
export LC_ALL=C
cd "$(dirname "$0")/.."

document=docs/use-cases.md
[ -f "$document" ] || { echo "check-use-cases: $document not found" >&2; exit 2; }

# Every Class.method the test sources declare. A file with several classes offers each of its methods under each class.
# shellcheck disable=SC2046  # the file list is split on purpose; the names hold no spaces
declared=$(awk '
    function flush(    declaredClass, declaredFunction) {
        for (declaredClass in classes) for (declaredFunction in functions) print declaredClass "." declaredFunction
        delete classes; delete functions
    }
    FNR == 1 { flush() }
    /^[[:space:]]*((private|internal|open|abstract|data) +)*class +[A-Za-z0-9_]+/ {
        name = $0; sub(/.*class +/, "", name); sub(/[^A-Za-z0-9_].*/, "", name); classes[name] = 1
    }
    /fun +[A-Za-z0-9_]+ *\(/ {
        name = $0; sub(/.*fun +/, "", name); sub(/[^A-Za-z0-9_].*/, "", name); functions[name] = 1
    }
    END { flush() }
' $(find app/src/test app/src/androidTest -name '*.kt') | sort --unique)

# Every Class.method that is a @Test, with its own class (the list above also pairs a method with the other classes of its file).
# shellcheck disable=SC2046  # the file list is split on purpose; the names hold no spaces
test_methods=$(awk '
    FNR == 1 { current = ""; pending = 0 }
    /^((private|internal|open|abstract|data) +)*class +[A-Za-z0-9_]+/ {
        name = $0; sub(/.*class +/, "", name); sub(/[^A-Za-z0-9_].*/, "", name); current = name
    }
    /@Test/ { pending = 1 }
    /fun +[A-Za-z0-9_]+ *\(/ {
        if (pending && current != "") { name = $0; sub(/.*fun +/, "", name); sub(/[^A-Za-z0-9_].*/, "", name); print current "." name }
        pending = 0
    }
' $(find app/src/test app/src/androidTest -name '*.kt') | sort --unique)

# One tab-separated record per fact: ID, TEST (id, name), NONE (id), NOTESTS (id), GROUP (parent id of a sub-entry).
# Only text after a "### UC-nn" or "#### UC-nna" heading counts, and a "Tests:" block ends at the first line that is not a bullet.
facts=$(awk '
    function finish() {
        if (current != "" && !hasTests) print "NOTESTS\t" current
        current = ""; hasTests = 0; inTests = 0
    }
    /^##($|[^#])/ { finish(); next }
    /^####? UC-[0-9]+[a-z]?( |$)/ {
        finish(); current = $2; hasTests = 0; print "ID\t" current
        parent = current; if (parent ~ /[a-z]$/) { sub(/[a-z]$/, "", parent); print "GROUP\t" parent }
        next
    }
    current == "" { next }
    /^Tests:/ {
        hasTests = 1; inTests = 1
        if ($0 ~ /none yet/) { print "NONE\t" current; inTests = 0 }
        line = $0
    }
    inTests && !/^Tests:/ && !/^[[:space:]]*- / { inTests = 0 }
    inTests {
        line = $0
        while (match(line, /`[A-Za-z0-9_]+\.[A-Za-z0-9_]+`/)) {
            print "TEST\t" current "\t" substr(line, RSTART + 1, RLENGTH - 2)
            line = substr(line, RSTART + RLENGTH)
        }
    }
    END { finish() }
' "$document")

failures=0
report() { echo "  $1"; failures=$((failures + 1)); }

use_cases=$(printf '%s\n' "$facts" | awk -F'\t' '$1 == "ID" { print $2 }')
duplicates=$(printf '%s\n' "$use_cases" | sort | uniq --repeated)
groups=$(printf '%s\n' "$facts" | awk -F'\t' '$1 == "GROUP" { print $2 }' | sort --unique)
untested=$(printf '%s\n' "$facts" | awk -F'\t' 'NR == FNR { group[$0] = 1; next } $1 == "NOTESTS" && !($2 in group) { print $2 }' <(printf '%s\n' "$groups") -)
named=$(printf '%s\n' "$facts" | awk -F'\t' '$1 == "TEST" { print $3 }' | sort --unique)
absent=$(comm -23 <(printf '%s\n' "$named") <(printf '%s\n' "$declared"))
missing=$(printf '%s\n' "$facts" | awk -F'\t' 'NR == FNR { absent[$0] = 1; next } $1 == "TEST" && ($3 in absent) { print $2 " names " $3 }' <(printf '%s\n' "$absent") - | sort --unique)

if [ -n "$duplicates" ] || [ -n "$untested" ] || [ -n "$missing" ]; then
    echo "check-use-cases: $document is out of step with the tests" >&2
    while IFS= read -r id; do [ -n "$id" ] && report "$id appears more than once"; done <<<"$duplicates"
    while IFS= read -r id; do [ -n "$id" ] && report "$id has no Tests: line (write 'Tests: none yet' if it has no test)"; done <<<"$untested"
    while IFS= read -r line; do [ -n "$line" ] && report "$line, which is not a test in app/src/test or app/src/androidTest"; done <<<"$missing"
    echo "check-use-cases: $failures problem(s). Update $document in the same change that renames or removes a test." >&2
    exit 1
fi

count() { [ -z "$1" ] && echo 0 || printf '%s\n' "$1" | wc --lines; }
none_yet=$(printf '%s\n' "$facts" | awk -F'\t' '$1 == "NONE"')
unlinked=$(comm -23 <(printf '%s\n' "$test_methods") <(printf '%s\n' "$named"))
if [ -n "$unlinked" ]; then
    echo "check-use-cases: warning: $(count "$unlinked") of $(count "$test_methods") tests are named by no use case:"
    printf '%s\n' "$unlinked" | sed 's/^/  /'
fi
echo "check-use-cases: ok. $(count "$use_cases") use cases ($(count "$groups") groups), $(count "$named") tests checked, $(count "$none_yet") marked 'none yet', $(count "$unlinked") tests unlinked."
