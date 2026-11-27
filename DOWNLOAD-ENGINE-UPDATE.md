# IMZYY XL Lite v9 — merged download, restart and graphics recovery

This archive is based on the Account & Skin edition and already includes the download-resume patch. Do not apply the separate download patch over this archive a second time.

## Merged download changes
- HTTP Range resume when the host supports it.
- Four retry attempts with backoff and a 75-second read timeout.
- 256 KiB streaming buffer and SHA-1/SHA-512/SHA-256 verification before finalizing downloads.
- Asset repair concurrency raised to 8; Modrinth mrpack downloads raised to 6.

## Modpack/restart safety changes
- A staged modpack receives `.imzyy-ready` only after its files have downloaded and overrides have been extracted successfully.
- A new staged install clears an older incomplete staging folder first.
- Restart waits up to 30 seconds for the `:minecraft` process to stop. It does not mutate `mods/` while that process is visible.
- On applying a staged modpack, the old `mods/` directory is moved to `.imzyy-backups/` and the staged `mods/` directory replaces it. Two recent backups are retained.
- Hub PLAY refuses to apply a pending pack while the Minecraft process is still running.
- The Hub `.mrpack` importer stages to the same safe path; it is applied on the next safe PLAY.

## Graphics recovery
- BOOST contains **PERBAIKI RENDER & TEKSTUR**.
- It clears shader-pack selections and removes only the app-generated `IMZYY-Quiet.zip` and `IMZYY-Vanilla-Lite-imzyy-lite.zip` overrides. Other user resource packs are preserved.
- It does not force a renderer setting. Test the renderer in the upstream launcher's Settings > Renderer. For modern Minecraft, try Zink first; if it still glitches, test MobileGlues or Krypton one at a time. GL4ES is intended mainly for older versions and should not be used with Sodium/Embeddium.

## Important validation boundary
`scripts/test-integration.py` validates source integration and patcher behavior. It does not compile an APK or validate rendering on a physical phone. A purple/magenta world can still require changing the renderer or collecting the engine/game log for the exact Minecraft version and GPU driver.
