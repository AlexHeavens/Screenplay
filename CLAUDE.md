# Screenplay

- Run: `scripts/ui-tests.sh` (e2e on an emulator); the app builds with `./gradlew assembleDebug`.
- Test: `./gradlew testDebugUnitTest`. Opt-in tests: `RealSpeechTest` (needs a real speech engine); `./gradlew pixel6Api34DebugAndroidTest` for the Gradle Managed Device.
- Lint: the three commands under Tests in `README.md` (ShellCheck, actionlint, ruff).
- Docs: `README.md` for setup, `docs/` for the data specs, use cases and Gutenberg rules.
- Before each commit, check the staged diff for secrets. The pre-commit hook needs `git config core.hooksPath .githooks`.
