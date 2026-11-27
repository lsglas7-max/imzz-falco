# IMZYY XL Lite v10.1.1 — Luna Sixty Kotlin compile fix

## Fixes
- Made `LunaMessage` public so the public `HubState.lunaMessages` property does not expose an internal type.
- Changed the recursive JSON response parser `find` from an expression body to a block body, allowing early returns from recursive array traversal.
- Added integration regression assertions for both Kotlin compiler errors.

## Validation
- `python3 scripts/test-integration.py` passes in the extracted source bundle.
- Full Android Gradle build and device/API runtime tests still need to run in GitHub Actions and on-device.
