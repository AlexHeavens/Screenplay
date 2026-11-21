# Screenplay

Screenplay is an Android app that turns a PDF into an audiobook with a synchronised transcript, notes pinned to points in the audio, and a one-tap backup through the share sheet.

- **Import**: pick a PDF, or share or open one from another app.
- **Generate**: each page is rendered and read by on-device OCR (ML Kit), so scans and text PDFs take one path. The sentences are spoken by the device's text-to-speech engine and encoded to AAC. A foreground WorkManager job keeps a long book going when the app is closed.
- **Listen**: the current sentence is highlighted and scrolled into view. Tap a sentence to jump to it.
- **Notes**: "Add note" pins text to the current position; it shows under its sentence.
- **Catalogue**: search a stored copy of the Project Gutenberg catalogue, refreshed daily, with no network needed after the first download (by title, author, genre and popularity) and add a book to the import queue as an EPUB. Gutenberg texts are public domain in the US only, and the app is not endorsed by Project Gutenberg. See [docs/gutenberg.md](docs/gutenberg.md) for the rules the feature follows and what to check before publishing.
- **Back up**: one zip of the PDF, audio, transcript and notes, sent with `ACTION_SEND`.

Not built: background playback with lock-screen controls, restoring from a backup.

## Contents

- `app/`: the Android app (Kotlin).
- `scripts/`: the catalogue builder and publisher, the data-spec checks, the emulator and UI-test runners, and `install-gitleaks.sh`.
- `tools/ocr-bench/`: a benchmark for the OCR step.
- `schemas/` and `golden/`: the data formats' schemas and their golden files ([docs/data-specs.md](docs/data-specs.md)).
- `docs/`: use cases, data specs, Gutenberg rules and measurements.
- `pins/`: version pins for gitleaks and the linters; Dependabot proposes bumps.
- `.github/workflows/`: CI, secret scan, lint, catalogue publishing and golden-file generation.

## Setup

Prerequisites: JDK 21, the Android SDK (`local.properties` holds `sdk.dir`) and Python 3.

```bash
scripts/install-gitleaks.sh      # the pre-commit hook needs gitleaks
git config core.hooksPath .githooks
```

## Tests

```bash
./gradlew testDebugUnitTest      # JVM tests; the command CI runs
scripts/ui-tests.sh              # e2e on a warm emulator that scripts/emulator.sh starts (quick boot) and stops after 30 idle minutes; set ANDROID_SERIAL to use another device
./gradlew pixel6Api34DebugAndroidTest   # same tests on a Gradle Managed Device
```

Lint, as CI runs it (`pins/lint/requirements.txt` pins the versions):

```bash
python3 -m venv /tmp/lint && /tmp/lint/bin/pip install --requirement pins/lint/requirements.txt
git ls-files '*.sh' .githooks | xargs /tmp/lint/bin/shellcheck --severity=warning
/tmp/lint/bin/actionlint
/tmp/lint/bin/ruff check --isolated --select E9,F .
```

The e2e tests use the real app, OCR, AAC encoder, player and share intent. Only speech is faked (`FakeSynthesizer`, a tone per word), because the `aosp_atd` emulator image has no speech engine. `RealSpeechTest` runs against the real engine and skips itself where none exists.

## Use cases

[docs/use-cases.md](docs/use-cases.md) lists what the app lets a listener do, each use case linked to the tests that prove it, with the gaps listed at the end. `scripts/check-use-cases.sh` fails when a named test no longer exists or a use case id repeats, and `./gradlew testDebugUnitTest` runs it.

## Licence

Mozilla Public License 2.0 ([LICENSE](LICENSE)). [LICENCE.md](LICENCE.md) explains why each tool and data source is allowed. Credits are in [NOTICE](NOTICE).
