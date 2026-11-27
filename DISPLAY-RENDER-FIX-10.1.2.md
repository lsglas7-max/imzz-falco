# IMZYY XL Lite v10.1.2 — Display & renderer compatibility fix

## Fixes
- Removed IMZYY's second fullscreen/insets/display-cutout override from the Minecraft activity. The upstream MinecraftActivity/SDL now retains ownership of the game window and native SurfaceView viewport.
- Kept IMZYY lifecycle behavior: Safe Boot session markers, touch-pad attachment after resume, and release of held controls on pause.
- Added a session-only renderer safety guard: if Sodium/Embeddium is detected with GL4ES on a modern Minecraft instance, use MobileGlues for that launch. The saved global and per-instance renderer choices are left unchanged.
- Added integration regression checks for both changes.

## Validation scope
- Offline patch integration tests validate the source injection and window-hook invariants.
- A successful test run does not replace a full Android Gradle build or a real render test on the Galaxy A07. Verify vanilla first, then the same Fabric instance with mods.
