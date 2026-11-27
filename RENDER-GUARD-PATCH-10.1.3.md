# IMZYY XL v10.1.3 — Renderer patch anchor fix

- Fixes the `Anchor renderer compatibility tidak ditemukan/tidak unik` preparation failure.
- Renderer guard inserts before `return base` in the renderer-resolution method, independent of nearby upstream comments.
- If the upstream renderer method has changed beyond safe matching, the optional guard is skipped with a warning instead of aborting the full build. For Sodium/Embeddium, select MobileGlues manually in that case.
- Offline integration test only; no full Gradle build or physical Galaxy A07 render test is claimed.
