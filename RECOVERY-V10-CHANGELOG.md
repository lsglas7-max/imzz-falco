# IMZYY XL Lite v10.0 — Recovery Center

## Boot and modpack recovery

- Adds a reversible Safe Boot action in BOOST. It moves the selected instance's `mods/` folder and any pending `.mrpack` staging folder to `.imzyy-recovery/safe-boot-*`; it does not delete either folder.
- Adds restore support. Files created during Safe Boot are retained in separate backup subfolders rather than overwritten.
- Adds a pre-launch JAR metadata audit for common duplicate mod IDs and known renderer conflicts. Critical conflicts stop the launch and point the user toward Safe Boot or removal of the duplicate JARs.
- Modpack installs are audited before the pending-ready marker is written.
- Adds a process marker to distinguish clean Minecraft activity destruction from an uncleanly ended session and improves process-alive checks during restart.
- Refuses to alter live `mods/` or graphics settings while the Minecraft process is still running.

## Graphics recovery

- Backs up `options.txt`, Iris properties, and shader options into `.imzyy-recovery/graphics-before/`.
- Resets selected resource packs to `vanilla`, empties the incompatible resource-pack list, and clears selected shader values.
- Preserves downloaded resource-pack and shader files. A restore action returns the original configuration.
- Does not blindly force a renderer: renderer choice remains in the upstream Settings screen because GPU/driver/mod compatibility varies.

## Diagnostics

- Writes `logs/imzyy-diagnostics.txt` with device/instance details, mod IDs, common conflicts, relevant `latest.log` lines, and recent crash-report excerpts.
- Redacts common access/refresh token and client-secret patterns before writing the diagnostics report.

## Validation

- `python3 scripts/test-integration.py` passes.
- Recovery Kotlin sources compile with local Android/JSON stubs.
- Synthetic filesystem test passes for Safe Boot backup/restore, graphics reset/restore, and duplicate Sodium/Embeddium detection.
- Full Android Gradle build and physical Galaxy A07 GPU/renderer test remain to be verified by GitHub Actions and on device.


## v10.1 — Luna Sixty & diagnostic context

- Added Luna Sixty native Compose assistant screen and a navigation shortcut from HOME and BOOST.
- Added natural-language chat through the configured Kyio GPT-6 Luna endpoint. The endpoint is stored in one API client source file and is not displayed in the UI or logs.
- Added explicit user-triggered analysis of the selected instance's redacted diagnostic report; no log is sent automatically.
- Expanded local diagnostic collection with graphics options, shader/resource-pack inventory, relevant latest.log lines, and recent crash-report excerpts.
- Added request timeouts, bounded prompt/response sizes, JSON/plain-text response extraction, and redaction of common secret patterns before diagnostic submission.
