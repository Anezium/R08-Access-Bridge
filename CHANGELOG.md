# Changelog

## v1.1.0 - 2026-06-06

### Release packaging

- Ships one APK: `R08-Access-Bridge-v1.1.0.apk`.
- Removed the old split between a fast APK and a focus-sync APK.
- Promoted the previous dev/focus-sync behavior to the default Stable mode.

### Ring modes

- Added an in-app `Ring modes` screen with Stable, Fast, Touch fallback, and AppType probe.
- Made Stable mode the default for safer launcher navigation.
- Added a top-bar badge showing the current Stable/Fast/Touch state.
- Kept Fast mode available as an optional launcher acceleration mode.
- Changed Fast mode to dispatch boosted launcher swipes instead of very short queued swipes that the Rokid launcher could swallow.

### Tap actions

- Added an `Action Mapping` screen for triple and quadruple tap.
- Kept Triple Tap defaulting to Rokid AI.
- Added Quadruple Tap with a no-action default.
- Added mappable actions for Rokid AI, normal photo, normal video toggle, AR screenshot, and AR video toggle.
- Reworked tap recognition into one tolerant tap-sequence window for single, double, triple, and quadruple taps.
- Added expiry for optimistic video and AR recording toggle state because Rokid does not expose a simple public broadcast ack.

### Navigation and compatibility

- Normalized native R08 DPAD keys through the focus-sync navigator instead of passing them to the launcher.
- Inverted R08 music-key navigation so physical slide down moves down and slide up moves up.
- Added vertical touch-fallback swipe handling for the same down/up mental model.
- Removed the launcher carousel auto-restore because it was too slow in real use.
- Guarded launcher scroll fallbacks against missing nodes on internal launcher activities.
- Removed the Tasker Bridge package-specific navigation path from R08 Access Bridge.

### Maintenance

- Extracted launcher navigation into `LauncherNavigator`.
- Extracted injected tap/swipe/long-press gestures into `GestureDispatcher`.
- Extracted tap-sequence timing into `TapSequenceRecognizer`.
- Added README screenshots captured from the real glasses UI.

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
