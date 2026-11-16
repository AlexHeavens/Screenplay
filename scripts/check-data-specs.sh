#!/usr/bin/env bash
# The data-spec checks CI runs (docs/data-specs.md), runnable by hand: the tools' own tests, schema-diff against the last release, and every
# golden data file against the schema of its version. The golden files are then read by the JVM unit tests (./gradlew testDebugUnitTest).
# Usage: scripts/check-data-specs.sh [--base REF]   (REF is passed to schema-diff.py; the default is its default)
set -o errexit -o nounset -o pipefail
cd "$(dirname "$0")/.."

(cd scripts && python3 -m unittest test_schema_diff)
python3 -I scripts/schema-diff.py "$@"

# golden/<name>/vN/<file> follows schemas/<name>/vN.json; <file>.expected.json is the recorded result of reading it, not data.
count=0
while IFS= read -r -d '' file; do
    version=$(basename "$(dirname "$file")")
    name=$(basename "$(dirname "$(dirname "$file")")")
    python3 -I scripts/validate-schema.py "schemas/$name/$version.json" "$file"
    count=$((count + 1))
done < <(find golden -type f ! -name '*.expected.json' ! -name 'README*' -print0 | sort --zero-terminated)
echo "$count golden files follow their schemas"
