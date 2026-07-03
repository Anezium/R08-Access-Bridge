# Changelog

## v1.5.0 - 2026-07-03

### Ring input responsiveness

- Reworked multi-tap recognition around an adaptive per-stage window: after each tap the app now waits only if a longer gesture is still possible, instead of always holding the full 700 ms timeout.
- Single tap now activates in ~350 ms instead of ~700 ms.
- Double tap Back fires instantly on the second tap when no triple/quadruple tap or two-tap combo is mapped, instead of up to 1.4 s later.
- Changed the factory defaults for triple and quadruple tap to `No action` so double tap Back is instant out of the box; both remain fully mappable, and devices that received the old auto-assigned quadruple default are migrated.
- Added a global duplicate-key guard so occasional duplicated BLE HID events can no longer cause double moves outside the launcher.
- Double tap Back now respects the same debounce as other Back inputs, so accidental repeated double taps cannot blast through multiple screens.
- When a swipe follows a still-pending tap with no combo mapped, the tap action now resolves before the swipe navigates, so activation can no longer land on the wrong item after the focus moved.

### Launcher swipe latency

- Cut the launcher swipe queue cycle from ~520 ms to ~270 ms per step (shorter injected drag, tighter settle and queue gaps), roughly halving the app-side delay between a ring swipe and the carousel move.
- Rapid ring swipes now accumulate into boosted multi-step drags instead of collapsing while a swipe is in flight, so fast scrolling no longer silently drops inputs.
- Relaxed the launcher direction debounce (260 ms to 150 ms) now that duplicated HID events are filtered at the source.

### Tap + swipe shortcuts

- Added four new mappable ring gestures: 1 tap + swipe up, 1 tap + swipe down, 2 taps + swipe up, and 2 taps + swipe down, recognized when a swipe arrives within the combo window after pending taps.
- Unmapped combos pass through cleanly: the tap and the swipe both keep their normal behavior.

### Launch app action

- Added a `Launch app` action that can be bound to any mappable gesture (triple/quadruple tap and the four tap+swipe combos). Selecting it opens a picker listing the installed apps on the glasses; the chosen app then opens directly from the ring gesture, without navigating the launcher.

### Screen wake

- Ring input now wakes the glasses display when it is asleep, and the gesture that caused the wake (plus a short grace window while the panel lights up) is swallowed instead of navigating or activating blindly. This protects users on short screen-off timeouts, where navigation used to act on a stale, invisible UI.

### Launcher focus under sysconfig MockWindow

- Fixed launcher navigation and the bottom app-name label freezing when the Rokid `sysconfig` firmware window (an invisible 1x1 focused system window created around screen on/off) steals the active-window slot: accessibility root resolution now detects the bogus tiny root and falls back to the real application window.
- Fixed launcher center activation launching the wrong app (typically the media player) after the display had slept, for the same root cause.

### Ring battery chip

- Moved the launcher ring battery chip so it no longer overlaps the launcher status icons (signal, Wi-Fi, Bluetooth) when they appear.

### Phone companion

- The companion main screen now shows a persistent `Watchdog` status line (running / not installed / failed / unknown, with the last-checked time), sourced live from the arm/re-arm output, and the setup copy explains that running the companion once is required to enable the watchdog.
- Fixed self-arm provisioning aborting on `chmod 600`: emulated storage on the glasses rejects POSIX mode changes, which stopped the loopback ADB key/watchdog script install halfway. The mode change is now best-effort (scripts are executed via `sh`, so the executable bit was never required).

## v1.4.8 - 2026-07-02

### Phone companion watchdog

- Added a phone-packaged Accessibility watchdog for Rokid RG firmware 1.21.009, launched over ADB shell during arm/re-arm so the glasses app can recover after AssistServer force-stops it on leg fold.
- Added glasses-side app-open self-arm: after the phone provisions a trusted ADB key and `persist.adb.tcp.port=5555`, opening R08 Access Bridge on the glasses connects to `127.0.0.1:5555`, repairs Accessibility, and restarts the watchdog. The loopback self-arm technique comes from [hacha](https://x.com/hacha)'s [`rokid-r08-wake`](https://github.com/hacha/rokid-r08-wake).
- The phone companion now auto-attempts re-arm on launch when a previous arm endpoint or Hi Rokid authorization exists, restoring the shortcut bridge and watchdog without an extra tap.
- Added a direct ADB fallback when Hi Rokid/CXR Bluetooth is unavailable, with mDNS settling to avoid stale Wireless Debugging ports.
- `Disable bridge` now stops the Accessibility watchdog too, so the persistent recovery loop is user-controlled.

## v1.4.7 - 2026-06-28

### App compatibility

- Added NewPipe Rokid build compatibility by forwarding ring navigation commands to NewPipe's own single-axis navigator when it is the active app.

### Launcher focus

- Promoted the v1.4.7 preview launcher focus tuning to stable, with tighter queued swipe limits and delayed center actions that keep tap and long-press activation aligned after carousel movement.

## v1.4.6 - 2026-06-16

### Launcher focus

- Made launcher carousel navigation less timing- and layout-sensitive by deriving swipe distance from visible app spacing, using carousel-relative center targeting, and delaying center activation until queued launcher swipes settle.

## v1.4.5 - 2026-06-16

### Ring battery and launcher polish

- Stopped polling the R08 ring battery every 5 minutes; the app now reads battery on BLE connection and after real ring input, throttled to once every 4 minutes.
- Reduced the launcher ring battery overlay icon from 14dp to 12dp.

## v1.4.4 - 2026-06-16

### No-bridge system controls

- Added direct volume adjustment on the Rokid volume screen using Android audio APIs, so ring swipes work without the privileged bridge.
- Added single-tap capture from the active Rokid camera page using the normal Rokid photo scene command.

### Ring battery and power

- Added QRing-compatible BLE notifications and battery parsing for the R08 ring.
- Added a compact ring battery overlay next to the glasses battery on the Rokid launcher.
- Removed the infinite 18-second TP sleep/wake loop while keeping one-shot wake calls around mode changes and reconnects.

## v1.4.3 - 2026-06-14

### Reader app compatibility

- Added automatic Readera compatibility for opening visible books, using the `Read` action from the document sheet, and page turning from the reader canvas.
- Added automatic `檐字阅读` / `com.arbook.reader` compatibility for opening visible books and turning reader pages with left/right tap zones.
- Improved focused slider handling so brightness/volume-style controls can react to ring swipes through Accessibility range actions.

## v1.4.2 - 2026-06-13

### Wireless setup post-pair recovery

- Fixed the phone companion getting stuck after `Paired` when the glasses kept reporting `pairing_ready` while the Wireless Debugging pairing dialog was still visible.
- After a successful ADB pairing, the companion now arms the live Wireless Debugging port even if the next CXR state still says `Pairing code ready`.
- Added short post-pair ADB connection retries and clearer `Paired, waiting for port` / `Arm failed` status transitions instead of collapsing every post-pair issue into `Pairing failed`.

## v1.4.1 - 2026-06-12

### Wireless setup resilience

- Added mDNS fallback for `_adb-tls-pairing._tcp` so the phone can recover the temporary Wireless Debugging pairing port when Settings text is incomplete or ambiguous.
- Kept the pairing-code dialog open long enough for the phone to pair, and avoided leaving Settings too early.
- Fixed stale `accessibility_service_needed`, `bridge_armed`, and expired pairing states so retrying setup does not get stuck after enabling R08 Accessibility, clearing the phone app, or stopping the bridge.
- Added phone-side Wi-Fi reachability checks and clearer logs for CXR, ADB pairing, and pairing-port resolution.
- Hardened KADB pairing with a watchdog so the phone UI cannot hang indefinitely while waiting for pairing.
- Returned the glasses to Home after successful setup/already-armed flows and made Back from the app root escape cleanly.

## v1.4.0 - 2026-06-10

### Reboot persistence and one-tap re-arm

- **Reboot self-heal (glasses):** Added `BootReceiver` — on `BOOT_COMPLETED`, if the bridge was previously armed, the glasses app re-enables Wi-Fi and writes `adb_wifi_enabled=1` via `WRITE_SECURE_SETTINGS` so the phone can reconnect without any user action on the glasses.
- **WRITE_SECURE_SETTINGS retention:** Phone companion now grants (instead of revoking) `WRITE_SECURE_SETTINGS` to the glasses app during arm, persisting the permission across reboots.
- **One-tap re-arm (phone):** Added `reArm` fast path in `AdbBridgeClient`. On launch, if a prior arm endpoint is stored, the companion auto-attempts re-arm using the saved host/port and the persisted KADB/dadb key — no re-pairing, no Settings navigation.
- **Adaptive primary button:** Phone UI primary button is now `Set up bridge` on first use and `Re-arm bridge` after any successful arm, adapting to the current state.
- **Wi-Fi + always-on scanning off after arm:** Shell bridge (`tools/r08-shortcut-bridge.sh` and raw copy) now also sets `wifi_scan_always_enabled=0` whenever Wi-Fi is turned off, keeping the glasses in fully quiet low-power mode.
- **Phone UI refonte:** Restructured `PhoneCompanionActivity` with a guided hero section (status + adaptive primary button), a collapsible "How this works" explanation, and all advanced controls (LAN scan, manual IP, recovery path, tools) moved to a collapsible "Advanced" section. Phosphor-green aesthetic and all existing functionality preserved.
- **Docs:** Updated README "Hi Rokid Shortcut Bridge" section with first-time vs. reboot flows; noted that the multi-language Settings automator is first-run only.

## v1.3.2 - 2026-06-09

### Shortcut bridge reliability

- Delayed glasses Wi-Fi shutdown after bridge arming to avoid a framework `system_server` watchdog reset.
- Updated bridge command feedback so the phone shows a Wi-Fi-off-scheduled state before the actual shutdown.
- Kept the bridge armed and usable in low-power operation after setup completes.

## v1.2.0 - 2026-06-07

### Hi Rokid shortcut bridge

- Added a bridge-backed `Hi Rokid Shortcut` tap action for the exact two-finger AI shortcut path.
- Changed Quadruple Tap's default action to `Hi Rokid Shortcut`.
- Added a shell bridge that converts R08 app requests into the raw Rokid `KEYCODE_SETTINGS` input event.
- Added a PC helper script at `tools/arm-r08-shortcut-bridge.ps1`.
- Added an Android `phone` companion app that arms the bridge over ADB Wi-Fi.
- Added Hi Rokid/CXR-L bootstrap so the phone can start the glasses app, foreground the glasses Wi-Fi panel, receive the glasses Wi-Fi IP, and then arm over ADB TCP when reachable.
- Added delayed Wi-Fi IP recovery: the glasses now push IP-watch states after opening Wi-Fi, and the phone keeps polling during bootstrap until the IP appears or the attempt times out.
- Added battery-friendly Wi-Fi control: the companion can ask the shell bridge to turn glasses Wi-Fi off after arming, and the glasses app can ask the bridge to turn Wi-Fi back on during the next CXR bootstrap if the bridge survived.
- Added phone-side LAN discovery, glasses Wi-Fi IP refresh, and manual ADB recovery actions.
- Reworked the phone companion UI around a single `Start Bridge` flow with a prominent bridge state, a close-the-app-when-armed message, a manual `Disable bridge` tool, and a monochrome phosphor-green Rokid-style interface.
- Added the R08 logo as the phone companion launcher icon.
- Added a shared `bridge-protocol` module so the phone app, glasses app, CXR bootstrap, and shell bridge use one canonical command contract.
- Added a protected glasses-side `BridgeCommandActivity` for automated setup commands instead of routing phone/ADB command extras through the main UI.
- Split phone-side ADB arming, LAN discovery, CXR Global service binding, and operation results into focused helper classes.
- Deduplicated the packaged shell bridge script so the phone APK is generated from the canonical script in `tools/`.

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
