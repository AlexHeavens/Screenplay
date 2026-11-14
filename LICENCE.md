# Licensing of Screenplay

This page states the licence of this repository and explains why each tool, library, model and data source is allowed under it. It is an engineering record and not legal advice. A claim marked *unverified* has not been checked against the owner's own licence text.

[LICENSE](LICENSE) is the full licence text. [NOTICE](NOTICE) lists the credits that the third-party licences require.

## The licence of this repository

All source code, tests and documents written for this project use the **Mozilla Public License 2.0 (MPL-2.0)**. Each source file carries the standard MPL header.

Why MPL-2.0:

- It is a copyleft licence at file level. If someone changes a file of this project and distributes it, they must publish the changed file under the MPL. Their own separate files can use any licence.
- It allows the app to link with Apache-2.0 libraries and with Google's proprietary ML Kit library. A strong copyleft licence such as GPL-3.0 does not allow the proprietary part.
- Section 3.3 of the MPL lets a combined work be distributed under the GPL. The text of the MPL is not marked "Incompatible With Secondary Licenses" (see [Open question](#open-question-the-built-apk) for why this matters).
- The copyright holder can release later versions under another licence, because all code is written by one owner. Copies already published keep the terms they were published under.

## Rules for choosing what goes in

1. Check the licence of every library, model, dataset, font, icon and tool against MPL-2.0 before it is added.
2. Check what the built APK contains, including native code inside a vendor library, and not only the source tree.
3. Check what the app downloads at run time.
4. Record the result here and put the credit text in [NOTICE](NOTICE).

## Code the app is built with

| Item | Licence | Why it is allowed | Status |
|---|---|---|---|
| sherpa-onnx 1.13.8 (speech engine) | Apache-2.0 | Apache-2.0 is compatible with MPL-2.0. The AAR is fetched by the `fetchSherpa` Gradle task and is not stored in this repository (`app/libs/` is ignored). | Licence text read |
| ONNX Runtime (inside the sherpa-onnx AAR) | MIT | A permissive licence. | *Unverified* |
| Apache Commons Compress | Apache-2.0 | Compatible. Used to unpack the voice archives. | *Unverified* |
| AndroidX Core, Activity, Lifecycle, Compose, WorkManager | Apache-2.0 | Compatible. | *Unverified* |
| Google ML Kit text recognition 16.0.1 | Proprietary, Google ML Kit terms | It is not part of this repository and the MPL allows linking to it. The terms forbid reverse engineering and ask the app owner to tell users about Google's processing of metrics data. They do not forbid use in an open-source app. | Terms page read. See the open question below. |
| JUnit 4 and `org.json` (tests only) | EPL-1.0 and the JSON licence | Used only to run tests. They are not shipped in the app. | *Unverified* |
| Gradle wrapper, Android Gradle Plugin, Kotlin | Apache-2.0 | Build tools only. | *Unverified* |

## Models and data the app downloads on first use

The files are not part of this repository or of the APK.

| Item | Licence | Why it is allowed | Status |
|---|---|---|---|
| Kokoro (int8, English v0.19) voice | Apache-2.0 | The model card states Apache-2.0 and says the training audio is permissive or non-copyrighted. | Model card read |
| Piper `en_US-libritts_r-medium` voice | CC BY 4.0 | It needs a credit, which NOTICE gives. The voice is trained on LibriTTS-R. | Model card read for the dataset licence. The credit text is *unverified*. |
| `espeak-ng-data` (inside both voice archives) | GPL-3.0-or-later | The app only downloads and reads the data files. See the open question below. | *Unverified* |
| Project Gutenberg books | Public domain in the USA, with the Project Gutenberg licence text | The app downloads one book at a time when the user taps it, and keeps the Project Gutenberg licence text with the book. | Terms and robot policy read. See [docs/gutenberg.md](docs/gutenberg.md). |
| Gutendex search (gutendex.com) | Code is MIT. The public instance states no terms. | It is a free search API for Project Gutenberg metadata. The app sends a User-Agent that names the project. | Repository page read |
| Open Library lookup by ISBN | Terms page asks regular callers to send a User-Agent with a contact. It says the APIs are not a backend for third-party services. | The app sends one request when a user enters an ISBN, which is a human lookup. The call sends a User-Agent that names the project and its URL. | Terms page read. Data licence page not read. |

## Assets stored in this repository

| Item | Licence | Why it is allowed | Status |
|---|---|---|---|
| App icon "Open book" (Pinhead icons v12.0.0) | CC0 1.0 | Public domain dedication. No credit is needed, and one is given as a courtesy. | Listed in NOTICE |
| Playback icons `replay_10` and `forward_10` (Material Icons) | Apache-2.0 | Compatible. | Listed in NOTICE |
| `lighthouse.epub` test book | CC0 1.0 | The text was written for this project. | Own work |
| OCR test scans of Darwin (1859), Gibbon (1897) and James (1902) | Public domain | The Wikimedia Commons metadata of each scan says "Public domain" and "not copyrighted" (checked 2026-11-14). | Metadata read |
| Expected text for those scans | CC BY-SA 4.0 (Wikisource) | The text is a Wikisource transcription. It needs a credit and share-alike for changes, and NOTICE gives the credit. Its licence is *unverified* page by page. | *Unverified* |
| Synthetic OCR benchmark pages in `tools/ocr-bench/corpus/` | Own work and public-domain text | They are generated by `synth.py` from public-domain text. | *Unverified* |

## Material removed before publication

The first history of this project held scans and quoted text of a book that is still under copyright. Before the repository became public, that material was deleted and the history was replaced with a single new commit. Tests that need similar text use invented sentences. The old private repository stays private.

## Open question: the built APK

This repository holds no binaries, so the source is not affected. A built APK would contain:

- MPL-2.0 code from this project,
- GPL-3.0-or-later espeak-ng code. The native library in the sherpa-onnx AAR contains strings such as `phondata` and `mbrola`, which shows that the espeak-ng engine is compiled in,
- the proprietary ML Kit libraries.

GPL-3.0 does not allow a proprietary library in the same combined work. **Do not publish an APK until this is resolved.** The options are:

1. Replace ML Kit with an open-source OCR engine such as Tesseract (Apache-2.0).
2. Keep ML Kit and use only the phone's own voice, with no sherpa-onnx.
3. Ask a lawyer whether the dynamic loading of ML Kit changes the result.

## Still unchecked

- The licence text of ONNX Runtime, Commons Compress, AndroidX, JUnit and `org.json` was not read. The licences in the tables above are the usual ones for those projects.
- The Open Library data licence page was not read.
- The CC BY-SA status of each Wikisource transcription was not checked.
- The credit text for the Piper voice and for LibriTTS-R was not checked against the voice's own page.
