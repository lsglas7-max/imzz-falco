# IMZYY XL Lite v9.1 — Kotlin compile fix

This is the complete v9 source bundle with a targeted Kotlin compile fix in
`android-overlay/kotlin/ImzyyRuntimes.kt`.

Changes:
- Add the missing `java.io.FileOutputStream` import.
- Rename the local ZIP extraction variable from `out` to `outputFile` so it cannot
  collide with Kotlin's `out` variance keyword during parsing/type inference.
- Use an explicitly named output stream in `use` and validate directory creation.
- Extend `scripts/test-integration.py` with assertions for this runtime extraction code.

Validation performed for this bundle:
- `python3 scripts/test-integration.py` passes.
- `ImzyyRuntimes.kt` compiles with `kotlinc` against minimal Android/JSON/network stubs.

The full Android APK build still needs to run in GitHub Actions against the selected
upstream FlameLauncher checkout.
