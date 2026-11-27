# IMZYY XL v10.1.4 — Renderer Regression Rollback

## What changed
- Removed the IMZYY rule that automatically changed a Sodium/Embeddium instance from GL4ES to MobileGlues.
- The upstream per-instance renderer preference, or the upstream global preference when no per-instance setting exists, remains the selected renderer.
- If a previously prepared working tree contains the old marker block, the preparation script removes that block before build. It refuses to edit if the removal boundary cannot be identified safely.
- Removed accidentally included Python bytecode and scratch test artifacts from the source archive.

## Why
The v10.1.2/v10.1.3 auto-switch was based only on mod filenames and did not measure the device's actual graphics capabilities. MobileGlues has documented black-screen issues on some Mali configurations; forcing it can make a partially-rendered screen worse. The screenshot regression is consistent with a renderer/backend issue, but a screenshot alone cannot prove the exact driver fault.

## Important Sodium note
Sodium's current post-launch compatibility check explicitly throws a RuntimeException when it detects a Pojav-style Android environment. Changing the renderer alone does not remove that check. This patch does not bypass Sodium's compatibility restriction. Test a clean/vanilla instance without Sodium first; then add mods back in small groups.

## Validation scope
- Offline integration suite verifies removal of the legacy guard, preservation of the upstream renderer selection, and normal IMZYY source injection.
- No full Gradle build or physical Android GPU test is claimed.
