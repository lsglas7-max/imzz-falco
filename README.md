# IMZYY XL Launcher — Premium Android ARM64 Edition (v3)

IMZYY XL Launcher is a single Android application for **Minecraft: Java Edition**. The project embeds the complete upstream JVM/native launch stack and Minecraft game activity in the same APK. Users install and open **IMZYY XL Launcher only**; there is no second launcher APK to install or hand off to. The game activity runs as an isolated process inside the same Android application for stability, not as another launcher app.

## What is inside

The selected open-source engine currently documents:

- Minecraft Java versions from Alpha/Beta through current releases.
- Vanilla, Fabric, Forge, NeoForge and Quilt.
- Mod and modpack discovery/installation through CurseForge and Modrinth, including dependencies.
- Version-matched OpenJDK runtimes selected automatically.
- Zink, Krypton, GL4ES and MobileGlues rendering paths, with hardware-dependent fallback behavior.
- Touch controls, editable on-screen buttons, hardware keyboard, mouse and gamepad support.
- ARM64 Android builds.

OptiFine is not listed as a first-class integrated loader by this upstream. Do not assume it is guaranteed; use the upstream-supported loader and mod paths.

## IMZYY XL Lite

Lite is a build variant of the same project (`IMZYY_VARIANT=lite`, workflow `android-lite.yml`).

- **Size**: only **Java 21** stays inside the APK. Java 8 / 17 / 25 (about 157 MB) are stripped from the APK, published by CI as assets of the `runtimes` GitHub release, and downloaded once, SHA-256 verified, when needed (HOME > Runtime Java, or automatically on game start when not on the UI thread). Estimated APK: roughly 105-120 MB; the build prints the real number and warns above 150 MB.
- **All Minecraft versions**: the launcher picks the Java version per Minecraft version. Old versions (<= 1.16) need Java 8, 1.18-1.20.4 need Java 17, 26.x needs Java 25: download these once in HOME before playing. If a runtime is missing the launcher falls back to the bundled Java 21, which works for most modern versions but not for old/Forge-heavy ones.
- **Mods**: IMPORT accepts `.mrpack`, a single `.jar`, or a `.zip` that contains mods (jars are found at any depth and flattened into `mods/`; nothing else is extracted).
- **Voxy** is an opt-in entry in the BOOST pack (Fabric + Sodium only, needs OpenGL 4.6; often unsupported on phones).
- **Controls** are unchanged: KONTROL tab + the upstream button editor.
- **Look**: animated shimmer title, pulsing PLAY button, drifting particles, monochrome palette.

## v3: monochrome UI + IMZYY Hub

- **Black / white theme** across the upstream screens (palette patch) with an adjusted contrast pass so white-on-accent button text becomes black.
- **IMZYY Hub** is the new launcher entry screen (animated grid backdrop, staggered reveals, spring press feedback). **PLAY** opens the upstream launcher activity, which is untouched and still in the same APK.
  - **BOOST**: one-tap `options.txt` profiles (Hemat FPS / Seimbang / Kualitas, original backed up) and a performance mod pack (Sodium or Embeddium, Lithium, FerriteCore, ModernFix, ImmediatelyFast, Entity Culling, Dynamic FPS) resolved live from Modrinth for the chosen Minecraft version + loader, with required dependencies, SHA-hash verification and a clean uninstall.
  - **MODS**: search Modrinth filtered by version/loader and install into the instance.
  - **IMPORT**: `.mrpack` (Modrinth format v1) importer: hash-verified parallel downloads from allow-listed hosts, zip-slip-safe overrides/client-overrides extraction. Create the matching instance (version + loader) in the launcher first, then pick its folder.
  - **GitHub card**: repo/releases buttons and a "latest release" check (needs `IMZYY_GITHUB_REPO`, set automatically by CI).
- Sodium/Embeddium need an OpenGL 3.2+ path (Zink, Krypton, MobileGlues); do not use them with GL4ES. Sodium is now opt-in rather than preselected so a new FPS pack does not silently install an incompatible renderer mod.
- **Graphics repair (v10)**: backs up `options.txt` and Iris/OptiFine shader settings, resets active resource-pack selections to Vanilla and clears the selected shader. Pack files are preserved; settings can be restored from the Hub.
- **KONTROL tab + in-game hooks** (patched into the real upstream input code, verified against its source):
  - camera sensitivity (replaces the fixed 1.5 constant), drag slop and long-press delay, all live-tunable;
  - **virtual mouse / touchpad mode** for menus and inventory: drag moves a visible cursor, tap = left click, long press or two-finger tap = right click (gameplay keeps normal camera control);
  - on-screen button colors converted to monochrome, and a shortcut to the upstream button-layout editor.
  - Settings travel via `filesDir/imzyy_controls.json` because the game runs in its own `:minecraft` process; they apply when the game (re)starts.
- Instances in the Hub come straight from the launcher's own instance list (Minecraft version and loader are read automatically), so mod install/FPS pack/.mrpack always match the selected instance.
- Local `.mrpack` import checks the pack's Minecraft version and loader against the selected instance and refuses mismatches.

## GitHub

```bash
gh auth login
bash scripts/github-setup.sh imzyy-xl-launcher public --release v3.0.0
```

Pushing to `main` builds the APK as an artifact; pushing a `v*` tag also publishes a GitHub Release with the APK, build info and corresponding source.

## Built-in performance profiles

The JVM/game settings page includes three working one-tap profiles. They change existing engine settings; they do not inject undocumented JVM arguments:

| Profile | Heap limit policy | Render scale | GC |
|---|---:|---:|---|
| FPS | 45% of detected heap ceiling, capped at 2048 MB | 75% | G1GC |
| Balanced | 58% of detected heap ceiling, capped at 3072 MB | 85% | G1GC |
| Visual | 68% of detected heap ceiling, capped at 4096 MB | 100% | G1GC |

The heap ceiling is derived from device RAM and the preset keeps memory headroom for Android, textures and native renderer allocations. These are starting points, not universal FPS guarantees. The best configuration depends on the phone's GPU/driver, RAM pressure, thermal state, Minecraft version, mods and renderer. Settings take effect on the next game launch. Renderer choices remain available in the existing renderer settings; the launcher does not force unsupported GPU flags.

### Renderer starting points

- **Zink**: try first on a modern Vulkan-capable GPU for Minecraft 1.17+ and shader-capable setups. The engine documents a device-dependent fallback path.
- **Krypton**: a broad GLES 3.x path when Zink is unstable or unavailable.
- **GL4ES**: compatibility path for older Minecraft versions and devices that cannot run Zink.
- **MobileGlues**: another OpenGL/GLES path exposed by the upstream engine; verify compatibility with the exact game version and device.

Do not stack multiple renderer drivers or copy random JVM flags from PC guides. Change one setting at a time and keep a working profile so regressions can be isolated.

## IMZYY account, Minecraft authentication and local skins

- **IMZYY LOGIN is device-local in this source version.** Registration creates a profile in the app-private storage on this device. Passwords are salted PBKDF2 hashes, not plaintext. There is no hosted account API, cloud sync, email recovery, or cross-device login until an IMZYY backend is configured.
- The IMZYY account gates the launcher dashboard and owns the local skin file. It is **not** a Minecraft account and does not issue Minecraft session tokens.
- **Important upstream limitation:** the selected FlameLauncher engine documents Microsoft sign-in as its only Minecraft authentication method. Its account/authentication flow is not replaced by the device-local IMZYY account in this patch. Official online play and the upstream game launch flow may still request a Microsoft-authenticated Minecraft profile. Do not describe this build as a fully offline/no-Microsoft Minecraft engine.
- **Local skin scope:** the PNG is installed as an enabled resource pack in the chosen instance, overriding the default local Steve/Alex fallback texture. It is not uploaded to Mojang/Microsoft or a skin server, and it does not replace authenticated skins already loaded for online players. Use a 64×64 PNG and select the matching Classic or Slim model.
- The upstream Android namespace/application ID are intentionally preserved for now because OAuth callback registration may depend on that identity. The visible app branding and artifact name are IMZYY XL Launcher.

## Download reliability

- HTTPS downloads use a larger 256 KiB streaming buffer, 75-second read timeout, up to four attempts with backoff, and HTTP Range resume when the CDN supports it.
- Every resumed file is re-hashed from the beginning and verified against the supplied SHA-1/SHA-512/SHA-256 before it is promoted from `.part` to its final name. Hash failure discards the partial file.
- Asset repair uses up to eight concurrent downloads; Modrinth `.mrpack` uses up to six.
- Resume improves recovery from interrupted transfers; it cannot make a slow server or network inherently faster, and servers that ignore Range restart that one file from the beginning.

## Magenta / broken rendering and display viewport

The game `SurfaceView` and SDL window remain owned by the upstream Minecraft activity. IMZYY only attaches its touch-pad overlay and boot markers; it does not change `decorFitsSystemWindows` or the display-cutout policy from an activity lifecycle callback. This avoids applying a second window geometry policy after the native renderer has measured its viewport.

IMZYY does not automatically change the renderer based on mod filenames. The upstream per-instance renderer selection is used when present; otherwise the upstream global renderer setting is used. This is intentional: renderer support depends on the exact GPU, driver, Minecraft version and mod set, and automatically forcing MobileGlues produced a black/magenta rendering regression on some Mali configurations.

The upstream engine exposes Zink, Krypton, GL4ES and MobileGlues with different graphics capabilities. Do not assume one renderer is best for every device. First test a clean vanilla instance with no mods or shader packs; then test the same instance with mods added in small groups. Sodium's own current compatibility check can reject Pojav-style Android environments regardless of renderer, so IMZYY does not bypass that check. BOOST > Perbaikan render / tekstur backs up and resets active resource/shader-pack selections; it cannot repair a broken GPU driver or make an unsupported mod compatible.

## v10.1.4 renderer regression rollback

The automatic Sodium/Embeddium-to-MobileGlues renderer switch from v10.1.2/v10.1.3 has been removed. IMZYY now preserves the renderer selected by the user in the upstream instance/global settings. This is a rollback of an unsafe assumption, not a promise that the current modpack's GPU output is fixed.

## Build with GitHub Actions

1. Upload the contents of this ZIP to the root of a GitHub repository.
2. Push to `main` or `master`, or run **Actions → Build IMZYY XL Launcher (ARM64)** manually.
3. Download the artifact `IMZYY-XL-Launcher-ARM64-debug`.
4. It contains the debug APK, build provenance and corresponding patched upstream source archive.

## Build locally

Requirements: Git, Python 3, JDK 17, Android SDK platform 36, Android build-tools 36.0.0, NDK 27.0.12077973, CMake 3.22.1 and network access.

```bash
python3 scripts/test-integration.py
bash scripts/build-imzyy.sh all
```

Output:

```text
output/IMZYY-XL-Launcher-arm64-debug.apk
output/BUILD-INFO.txt
output/IMZYY-XL-Launcher-corresponding-source.tar.gz
```

The build downloads FlameLauncher-Android, applies the IMZYY identity/UI/performance patch and compiles one ARM64 Android APK. The exact upstream commit is recorded in `BUILD-INFO.txt`. For reproducible releases, pin `IMZYY_ENGINE_REF` to a reviewed commit or tag before publishing.

## Validation and limitations

- Offline integration tests validate the patcher, single-app activity presence, branding, local account/skin overlays, UI profiles, typography, icon wiring, ARM64 target and idempotency.
- The Android APK must still be built by GitHub Actions or on a machine with the Android SDK/NDK/Gradle toolchain. This environment cannot access GitHub or run the Android build, so no fresh APK or physical-device FPS result is claimed here.
- Install and test on an ARM64 phone after CI passes. Check first launch, Microsoft sign-in, one vanilla instance, one mod-loader instance, renderer switching, touch input and resume after returning from the game.

## Licensing

The selected FlameLauncher source tree declares **AGPL-3.0** for the combined project because it includes GPL/AGPL components. Keep its `LICENSE` and `NOTICE`, publish the corresponding source and modifications when distributing the app, and review all upstream notices. Do not redistribute custom Vulkan drivers unless their separate terms allow it. Minecraft is a Mojang/Microsoft trademark; IMZYY XL is not affiliated with Mojang or Microsoft.

Upstream engine: https://github.com/FlameLaunchers/FlameLauncher-Android

## In-game extras (v8)

- **Asset repair**: every PLAY verifies the instance's Minecraft assets and re-downloads missing/truncated files; Home has a manual "PERIKSA & PERBAIKI ASSET" button.
- **Pad**: tap PAD in-game, then tap a button to change its mode, hide/show it, or merge more keys into it (e.g. CTRL+C, pressed together). "+ TOMBOL" adds new buttons or combos, "DAFTAR / SEMBUNYI" hides buttons one by one or all at once. The same controls exist in Hub > KONTROL.
- **MODS (in-game)**: search Modrinth modpacks that match the running instance (Minecraft version + loader), install them with hash verification, then use RELOAD TEKSTUR (F3+T), RELOAD CHUNK (F3+A) or RESTART GAME. The modpack is staged in `.imzyy-pending` (the running JVM's mod jars are never touched) and applied by RESTART GAME, which relaunches the same game automatically; it is also applied on the next Hub PLAY. Reload only refreshes resources and rendering.
- The upstream "Release Notes" menu and its GitHub update popup are removed at patch time.
- **Anti lag masuk world** (BOOST tab): lowers render/simulation distance per device tier (never raises a value you already set lower) and cheapens chunk updates, particles, shadows and biome blend; re-applied on every PLAY.
- **Modpack safety**: a modpack never overwrites your `options.txt`, `optionsof.txt`, `optionsshaders.txt` or `servers.dat` (language, keybinds and graphics stay yours). In-game and Hub `.mrpack` installs are staged and applied only after the Minecraft process exits. The previous `mods/` directory is backed up under `.imzyy-backups/` before it is replaced, preventing old/new jar versions from being loaded together. Fabric modpacks without Mod Menu get it added automatically (title-screen "Mods" button); Hub > MODS has a manual "PASANG MOD MENU".
- **Restart safety**: RESTART GAME waits up to 30 seconds for the isolated game process to end. It never edits `mods/` while the JVM is still alive; on timeout the staged pack is left untouched and the player can close Minecraft and press PLAY from the Hub.
- **Sodium on Android**: Sodium 0.5.x prints a red "PojavLauncher is not supported" console message because of the POJAV_* environment this engine needs. On PLAY, a tiny lang-only pack (`resourcepacks/IMZYY-Quiet.zip`) blanks that one message. It only hides the notice; it does not change how Sodium behaves. Newer Sodium (0.6+) throws instead of warning and cannot be hidden this way.
- **Bahasa game** (BOOST tab): INDONESIA / ENGLISH chips write `lang` into the instance options.txt without opening the in-game menu.


## IMZYY XL v10 Recovery Center

- Graphics recovery backs up `options.txt` and Iris/OptiFine shader settings, resets active resource packs to Vanilla, and disables the selected shader without deleting user pack files. Settings can be restored from the Hub.
- Safe Boot reversibly moves the live `mods/` folder and pending `.mrpack` staging folder into `.imzyy-recovery/`. Restore brings them back; files created during Safe Boot are preserved separately.
- A pre-launch mod audit reads common Fabric/Quilt/Forge metadata and blocks common conflict combinations (duplicate mod IDs, Sodium + Embeddium, Iris + Oculus, and OptiFine with Sodium/Embeddium).
- Game-process markers detect a session that ended without a clean activity-destroy callback. The Hub can create a redacted diagnostics report at `logs/imzyy-diagnostics.txt`.
- Mod install/uninstall, graphics repair and Safe Boot are refused while the Minecraft process is still alive, avoiding writes into files currently used by the JVM.

Renderer selection is intentionally not forced by the recovery routine. The upstream engine exposes several renderer backends, and compatibility depends on Minecraft version, GPU driver and mods. After a clean Safe Boot, use the upstream Settings > Renderer control to compare an unmodded baseline with the modded instance.


## v10.0 Recovery patch validation

The repository's offline integration suite checks that the recovery UI/hooks are injected and that the new Kotlin sources are present. A JVM test compiles the recovery classes against Android/JSON stubs and exercises the Safe Boot backup/restore flow plus graphics reset/restore on a synthetic instance. A full Android Gradle build and on-device GPU/renderer test must still be run by GitHub Actions and on the target device.


## Luna Sixty (v10.1)

The launcher includes a native Luna Sixty assistant screen. Users can ask general questions and request a filtered diagnosis of the selected Minecraft instance. Diagnostic reports are generated locally and sent only after the user taps **Analisis Log Instance**. The UI and app logs do not display the service URL. Because the endpoint is called from an Android client, a determined user can still discover it by inspecting the APK or network traffic; use a server-side proxy if the endpoint must be concealed from clients.

The diagnostic report now also includes selected graphics options and the names of local resource/shader packs, alongside relevant Minecraft log lines and mod metadata.
