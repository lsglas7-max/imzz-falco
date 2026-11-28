# IMZYY XL Launcher — v10.1.5 adaptive performance upgrade

## Changes

- Locks only `ImzyyHubActivity` to portrait. Minecraft remains on the upstream display/orientation path.
- Adds `ImzyyFramePacing`: refresh-rate detection, a compositor refresh-rate hint for the Hub window, and tween-duration quantization to whole display frames.
- Improves the Anti drop FPS toggle with an adaptive profile based on RAM tier, active display refresh rate, and the count of mod JARs. 100+ JARs receive a more conservative profile, including immediately after a pending `.mrpack` is applied during PLAY. Lower user-selected FPS and view-distance values are not raised.
- Changes texture downsampling to decode at half resolution directly and further limit temporary bitmap memory for exceptionally large textures. `.png.mcmeta` animated textures are still skipped.
- Adds CI syntax/commit checks and makes the Gradle cache key sensitive to the overlay and wrapper configuration.

## Validation

`python3 scripts/test-integration.py` passes against the bundled upstream-shaped fixture. The patcher injects the portrait manifest value idempotently, and the integration suite checks the new frame pacing and adaptive FPS sources.

A complete Android APK build and a real Minecraft play session were not run in this environment: this archive contains the overlay/build scripts, while the FlameLauncher engine, Android SDK/NDK and target device are fetched/provided during CI. GitHub Actions must still build the APK, and the resulting APK must be smoke-tested on the target phone with both vanilla and the user's real modpack.

## Performance notes

The 100+ mod count is a conservative heuristic, not a measure of actual mod workload. Some small mods are nearly idle; a few world-generation or rendering mods can be expensive. The dynamic profile reduces common graphics loads, but cannot eliminate mod incompatibilities, GPU driver faults, memory exhaustion or device thermal throttling.
