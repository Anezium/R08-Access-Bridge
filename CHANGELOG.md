# Changelog

## v1.0.2 - 2026-06-04

- Added triple tap on the R08 ring as a Rokid AI assistant / glasses long-press shortcut.
- Added the same triple-tap behavior to both release APK variants.
- Kept `R08-Access-Bridge-v1.0.2-debug.apk` as the normal fast APK with repeated-swipe acceleration.
- Kept `R08-Access-Bridge-v1.0.2-focus-sync-debug.apk` as the compatibility APK for launcher focus mismatch issues.
- Documented all ring actions and explained when to use the normal APK versus the focus-sync APK.
- Clarified that the protected Hi Rokid two-finger shortcut broadcast cannot be emitted by a normal APK; triple tap opens the Rokid AI assistant scene instead.

## v1.0.1 - 2026-06-02

- Released the first public debug APK.
- Added a focus-sync compatibility APK for Rokid launchers where the visible carousel and internal launcher focus can disagree.
- The focus-sync APK activates the visually centered launcher item and removes repeated-swipe acceleration for stability.
