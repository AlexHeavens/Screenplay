# Data specs: formats that must stay readable

Some data outlives the app version that wrote it: the catalogue file the app downloads, the per-book files in the library and the saved options.
Each has a machine-checked spec, so a change that would strand old data fails before it ships. Behaviour the reader sees stays in
[use-cases.md](use-cases.md) and the UI tests; this page covers data formats only.

## Layout

| Path | What it is |
|---|---|
| `schemas/<name>/vN.json` | JSON Schema (2020-12 subset, see `scripts/schemalib.py`) for format version N of `<name>` (`catalogue`, `book`, `options`). One schema describes one document, or for a row file one row as an object. **Frozen** once released. |
| `golden/<name>/vN/` | Real files written by that version's writer. **Never edited.** Each data file may have `<file>.expected.json`, the recorded result of reading it. |
| `scripts/validate-schema.py SCHEMA DATAFILE` | Checks a data file against a schema (`.json`, `.jsonl`, `.tsv[.xz]`). |
| `scripts/schema-diff.py` | Fails when a schema changed incompatibly since the last release. |
| `scripts/check-data-specs.sh` | Runs both tools' tests, the schema diff and every golden file against its schema. This is what CI runs. |
| `.github/workflows/golden.yml` | Generates the golden files for a new version with the real writers (never overwrites a folder). |

A row file (TSV) lists its column names in `x-column-order` and its leading non-row lines in `x-header-lines` (regular expressions). Cells are
read as the type their property asks for (integer, array of integers from `1,2,3`, empty is `null` where `null` is allowed).

## The rule

**Additive only within a version.** A released `vN.json` may gain an optional property (a new column goes last in `x-column-order`), a wider
range or a new enum value, and nothing else. `schema-diff.py` fails on a removed property, a changed type, a narrower range or enum, a new
required property, and on any keyword it cannot compare. The test is: the new schema accepts everything the old one accepted.

**A breaking change is a new version:** a new `vN+1.json`, a migration in the app (or a reader for both), and a new `golden/<name>/vN+1/`.
The old schema and golden folder stay, and the current parser keeps reading them.

For the catalogue, the app reads formats 1 and 2 and rejects a higher number with "Updating the app may fix this". Since the parser accepts
extra trailing columns, an optional column added to format 2 does not break an app that already shipped this parser; apps older than that
read exactly 8 columns and do.

## Add a new spec

1. Write `schemas/<name>/v1.json` with `$schema`, a `description` naming the writer and the reader, `required`, and `x-column-order` for a row file.
2. Run the real writer on a small input and keep the file as `golden/<name>/v1/<file>`. Check it: `python3 -I scripts/validate-schema.py schemas/<name>/v1.json golden/<name>/v1/<file>`.
3. Write a JVM unit test that loads every file in `golden/<name>/v1/` with the current parser and compares with `<file>.expected.json` (see `GoldenCatalogueTest`).
4. Add the writer to `.github/workflows/golden.yml` so the next version's golden files are generated, not hand-made.

## Bump a version

1. Copy `vN.json` to `vN+1.json` and make the breaking change there. Never touch `vN.json`.
2. Change the writer and the parser; keep the parser reading N (migration) for as long as N is supported.
3. Run `golden.yml` (workflow dispatch) and download the artifact. It writes `golden/<name>/vN+1/` and refuses when that folder exists.
   Commit the folder as it is; add `<file>.expected.json` files from the unit-test recorder (`GOLDEN_RECORD_DIR`, see the workflow), reviewing them by hand.
4. `scripts/check-data-specs.sh` and `./gradlew testDebugUnitTest` pass; the catalogue publisher validates its output against the new schema.

## Checks

* **CI** (`data-specs` job in `ci.yml`): `scripts/check-data-specs.sh`, then the newest published catalogue against its schema.
* **Publishing**: `scripts/publish-catalogue.sh` validates the file it built and uploads nothing when it fails.
* **Unit tests**: every golden file is read by the current code and must equal its `.expected.json`.
* **Last release** for `schema-diff.py` is the newest `v<digit>` tag, or `origin/main` while the app has no release tag. CI needs full history (`fetch-depth: 0`).

## Saved options

`schemas/options/v1.json` describes the `settings` SharedPreferences file as one object: a property per stored key, with type, allowed values and the
`default` an absent key takes. Unknown keys are allowed (`additionalProperties`), because the app ignores them. `golden/options/v1/<name>.json` is a
stored map from one era of the app and `<name>.expected.json` the settings the current code must read from it (`OptionsSpecTest`).

* **A new option is a new key**, so add it to a new schema version; `OptionsSpecTest.everyKeyInTheSourcesIsInTheSchema` fails on a key in `app/src/main` with no schema entry.
* **A removed or renamed key** needs a migration in `Options.load` and a golden map that still holds the old key. `natural` (the first build's Natural voice switch, read as voice `KOKORO`) is the example.
* Values the schema forbids (an unknown voice name, a malformed `catalogue-languages`) are tolerated by the reader; they are tested inline, not as golden files.
* The old Catalogue "English only" switch was never stored, so no migration exists; an absent `language` reads as All.
