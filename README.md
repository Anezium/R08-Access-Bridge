<h1 align="center">R08 Access Bridge</h1>

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/r08_access_bridge_logo_header.png" alt="R08 Access Bridge logo" width="320">
</p>

<p align="center">
  <a href="https://github.com/Anezium/R08-Access-Bridge/releases/latest">
    <img alt="Latest release" src="https://img.shields.io/github/v/release/Anezium/R08-Access-Bridge?style=for-the-badge&label=release">
  </a>
  <img alt="Android" src="https://img.shields.io/badge/Android-SDK%2028+-3DDC84?style=for-the-badge&logo=android&logoColor=white">
  <img alt="Java" src="https://img.shields.io/badge/Java-17-007396?style=for-the-badge&logo=openjdk&logoColor=white">
  <img alt="Rokid" src="https://img.shields.io/badge/Rokid-Glasses-111111?style=for-the-badge">
</p>

<p align="center">
  <a href="https://ko-fi.com/M8R61ZTXMI" target="_blank">
    <img height="36" style="border:0px;height:36px;" src="https://storage.ko-fi.com/cdn/kofi4.png?v=6" border="0" alt="Buy Me a Coffee at ko-fi.com" />
  </a>
</p>

<p align="center">
  Turn an R08 smart ring into a stable, one-axis controller for Rokid glasses.
</p>

R08 Access Bridge lets an R08 smart ring act as a navigation controller for Rokid glasses. It pairs with the ring over Bluetooth LE, configures the ring into a usable input mode, then translates ring input into launcher navigation, app focus movement, activation, and Android Back actions through an Accessibility Service.

Everything now runs on the glasses. A one-time `Self-arm (no phone)` sets up the exact Hi Rokid shortcut and keeps ring control alive across the firmware's force-stops — no phone, no Hi Rokid authorization, no shared Wi-Fi.

## Project Details

| Item | Value |
| --- | --- |
| App name | R08 Access Bridge |
| Package name | `com.anezium.r08accessbridge` |
| Minimum Android SDK | 28 (self-arm needs 30+) |
| Target Android SDK | 34 |
| Primary target | Rokid glasses / YodaOS-Sprite, 480x640 portrait HUD |
| Input device | R08 BLE ring |

## What It Does

- Pairs or reconnects to an R08 ring over Bluetooth LE.
- Converts ring inputs into one-axis navigation suitable for Rokid glasses, using Android Accessibility to move focus, scroll, click, inject launcher swipes, and perform Back.
- Arms the exact Hi Rokid shortcut entirely on the glasses with `Self-arm (no phone)`, and keeps the Accessibility service alive after the firmware force-stops it — no phone required.
- Enables Stable mode by default using R08 `appType 1`, which emits media key events, and lets you switch between Stable, Fast, and Touch fallback behavior from one APK.
- Lets you remap triple tap, quadruple tap, and four tap+swipe combo gestures directly from the glasses UI, including launching any installed app.
- Shows the latest R08 ring battery reading next to the glasses battery on the Rokid launcher.
- Lets ring swipes adjust the Rokid volume screen, and a single ring tap trigger a photo from the active camera page, without the privileged bridge.
- Wakes the glasses display on ring input and ignores the waking gesture, so ring actions never run blindly on a sleeping screen.
- Optionally keeps screen-off ring taps on the glasses: an opt-in `Keep screen-off taps on glasses` mode stops ring media keys from reaching a paired phone while the display is off, so a tap wakes the glasses instead of launching the phone's media app.

## Screenshots

<p align="center">
  <img src="docs/screenshots/home.png" alt="R08 Access Bridge home screen" width="190">
  <img src="docs/screenshots/ring-modes.png" alt="Ring Modes screen with Stable mode active" width="190">
  <img src="docs/screenshots/action-mapping.png" alt="Action Mapping screen with tap and tap+swipe gestures" width="190">
  <img src="docs/screenshots/tap-swipe-options.png" alt="Action options for a tap+swipe combo, including Launch app" width="190">
  <img src="docs/screenshots/launch-app-picker.png" alt="Launch App picker listing installed apps" width="190">
</p>

<p align="center">
  <img src="docs/screenshots/quadruple-tap-bridge.png" alt="Quadruple Tap mapped to the Hi Rokid Shortcut bridge" width="190">
  <img src="docs/screenshots/quadruple-tap-options.png" alt="Quadruple Tap action options" width="190">
</p>

## Controls

By default, the R08 ring is configured to emit media keys in Stable mode:

| Ring input | Meaning |
| --- | --- |
| Forward / next | Move forward through the launcher or current app focus |
| Backward / previous | Move backward through the launcher or current app focus |
| Single tap | Activate the current app, button, or focused item |
| Double tap | Android Back |
| Triple tap | Configurable action, no action by default |
| Quadruple tap | Configurable action, no action by default |
| 1 tap + swipe up / down | Configurable shortcut, no action by default |
| 2 taps + swipe up / down | Configurable shortcut, no action by default |

Inside R08 Access Bridge itself, double tap goes back to the previous screen. On the root screen, Back exits the app and returns to the launcher.

Triple tap, quadruple tap, and the combos ship unmapped so that single tap and double tap Back respond as fast as possible: the recognizer only waits for a longer gesture when one is actually mapped. Mapping a higher tap count or a combo adds a short (~350-500 ms) confirmation delay to the tap counts below it.

Each mappable gesture can be set in the app to:

- No action
- Rokid AI
- Hi Rokid Shortcut
- Take photo
- Video toggle
- AR screenshot
- AR video toggle
- Launch app (opens a picker listing the apps installed on the glasses)

## Self-arm: the Hi Rokid shortcut and surviving force-stops

Two things on the glasses need more than a normal APK can do by itself:

1. **The exact Hi Rokid shortcut** (Rokid AI, the two-finger long-press). It is the glasses touchpad `KEYCODE_SETTINGS` path — on tested hardware a raw `/dev/input/event1` event with scan code `149`, which Rokid turns into `ACTION_SETTINGS_KEY` / `openAIFunction type: 2` / CXR `Ai / Both_KeyDown`. A normal app cannot write `/dev/input`.
2. **Surviving the firmware.** Rokid RG firmware (1.21.009) force-stops the foreground third-party app and strips its Accessibility service when a temple leg is folded or the glasses sleep. Without recovery, ring control silently dies the first time you fold the glasses, and the service has to be re-enabled by hand.

`Self-arm (no phone)` solves both, entirely on the glasses. It installs two small helpers that run as the ADB `shell` user:

- A **shortcut bridge** that turns each quadruple-tap request into the real `sendevent` sequence, so the exact Hi Rokid shortcut works.
- An **accessibility watchdog** that re-enables the R08 Accessibility service automatically whenever the firmware kills it — so ring navigation survives folding and sleep without touching Settings.

Both come back on their own after a reboot: the app's boot receiver reconnects over local ADB loopback (`127.0.0.1:5555`, pinned via `persist.adb.tcp.port`) and restarts the helpers. The loopback self-arm technique is based on [hacha](https://x.com/hacha)'s [`rokid-r08-wake`](https://github.com/hacha/rokid-r08-wake).

### How to self-arm (one time)

1. Open `R08 Access Bridge` → `System` → `Accessibility` and enable the **R08 Access Bridge** service.
2. Back in the app, tap **`Self-arm (no phone)`**.
3. The Accessibility service opens the glasses' own Wireless Debugging, reads the pairing code, pairs over ADB loopback, installs the bridge and watchdog, grants `WRITE_SECURE_SETTINGS`, and turns Wi-Fi back off. When the app shows **`Self-arm complete`**, you are done.

The Settings navigation is locale-independent and works in any glasses language (English, French, Spanish, Portuguese, German, Italian, Russian, and Korean). Wireless Debugging requires Android 11+, which the R08 satisfies.

**Battery note:** Wi-Fi is only enabled for the duration of the self-arm, then turned off along with always-on scanning, keeping the glasses in low-power operation. The shortcut bridge waits on an event-driven FIFO rather than polling, so it costs almost no CPU while idle.

### The phone companion is no longer needed

Earlier versions required a phone companion app (`R08 Companion`) plus Hi Rokid/CXR-L and ADB over the same Wi-Fi to arm the bridge. Self-arm replaces all of that — the companion is obsolete for normal use and kept in the repo only as a legacy path. For development, `tools/arm-r08-shortcut-bridge.ps1` still arms over ADB from a PC:

```powershell
.\tools\arm-r08-shortcut-bridge.ps1 -Serial 1901092534053723 -Action restart
```

## Input Modes

The release APK contains both launcher behaviors — there is no separate fast APK anymore:

| Mode | Behavior | Use when |
| --- | --- | --- |
| Stable | One launcher step per slide. This is the default. | Most users, especially when launcher focus can drift. |
| Fast | Uses boosted launcher swipes after repeated slides. | Your glasses keep visual focus and launched app aligned, and you want faster launcher movement. |
| Touch | Configures the R08 touch fallback profile. | Debug only, when key input is not usable. |

The current mode is shown in the top bar of the app so the active behavior is visible at a glance.

## Launcher Behavior

The Rokid launcher does not reliably respond to normal Accessibility scroll calls, so R08 Access Bridge injects small horizontal launcher swipes instead. Each ring swipe moves one normal launcher step; Fast mode accelerates repeated slides with boosted swipes; activation taps the visible center app in the carousel. This avoids relying on stale launcher accessibility focus.

Launcher swiping also no longer depends on the display staying awake. The Rokid firmware parks focus on an invisible 1x1 system window around screen off, which used to freeze launcher navigation and the selected-app label for users with short screen timeouts. The app now detects that state, resolves the real launcher window, wakes the display on ring input, and swallows the gesture that caused the wake so nothing runs blindly on a dark screen.

One deliberate exception remains by default: a tap on a dark screen still acts as play/pause, so music can be started without waking the display. While the display is off, Android routes media keys to the current media button session before any accessibility filtering — and when the glasses are connected to a phone as a Bluetooth audio sink, that session is the phone-side AVRCP controller, so a screen-off tap can land on the phone and launch its media app while the glasses stay dark. If you never start music from the ring, enable `Keep screen-off taps on glasses` in `Ring modes`: the app then claims the media button session while the screen is off, consumes ring media keys, and turns taps into a display wake instead. It releases the claim on screen-on and backs off during real playback. This guard is based on [hacha](https://x.com/hacha)'s diagnosis and [pull request](https://github.com/hacha/R08-Access-Bridge/pull/1) on his fork.

## App Screens

### Home

- `Pair / Reconnect` scans for an R08 ring, connects to a bonded ring, or restarts the connection.
- `Self-arm (no phone)` arms the Hi Rokid shortcut and installs the accessibility watchdog, all on the glasses.
- `Ring modes` opens input mode settings.
- `Action mapping` opens tap and tap+swipe mapping.
- `System` opens permissions and reset actions.

### Action Mapping

- `Triple Tap` and `Quadruple Tap` choose what three or four taps trigger.
- `1 Tap + Swipe Up/Down` and `2 Taps + Swipe Up/Down` choose the tap+swipe combo shortcuts.
- Picking `Launch app` for any gesture opens a picker listing the installed apps; the chosen app is then launched directly by that gesture.

### Ring Modes

- `Stable mode` restores the recommended `appType 1` media-key mode with one launcher step per slide.
- `Fast mode` keeps `appType 1` and enables launcher acceleration after repeated slides.
- `Touch fallback` configures `appType 4` touch-style fallback mode for debugging.
- `Keep screen-off taps on glasses` blocks ring media keys from reaching a paired phone while the display is off; taps wake the screen instead of playing music. Off by default.
- `AppType probe` lets you test `appType 0` through `appType 7` and inspect key output in logs.

### System

- `Accessibility` opens Android Accessibility settings so the service can be enabled.
- `App settings` opens Android app details for Bluetooth permissions and system settings.
- `Forget R08` opens a confirmation screen and removes the bonded R08 ring when confirmed.

## Installation

Download the APK from the [GitHub Releases page](https://github.com/Anezium/R08-Access-Bridge/releases).

### Important: update and disconnect the ring first

Before pairing the ring with R08 Access Bridge, connect it to the official R08 Ring (QRing) app and let it install any available ring firmware update.

This matters for Stable mode / R08 `appType 1`: before the firmware update, `appType 1` may only emit swipe / previous / next input, and tap and double-tap Back may not work at all. After updating the ring in the official app, **disconnect it there**, then reconnect it in R08 Access Bridge. If the ring stays connected to the phone, the glasses may only see normal media-key behavior and R08 Access Bridge may not take over. Thanks to Reddit user `u/Rare_Wheel1907` for finding and confirming this fix.

### Set up on the glasses

```powershell
adb install -r R08-Access-Bridge-v1.6.0.apk
```

1. Open `R08 Access Bridge` and grant Bluetooth permissions if Android asks.
2. Open `System` → `Accessibility` and enable the `R08 Access Bridge` accessibility service.
3. On the phone, unbind/disconnect the ring from the official R08 Ring app (or forget it from phone Bluetooth if needed).
4. Back in the glasses app, select `Pair / Reconnect` and keep the R08 ring nearby.
5. Tap **`Self-arm (no phone)`** and wait for `Self-arm complete`. This one step arms the Hi Rokid shortcut and installs the accessibility watchdog, so ring control survives the firmware force-stops. Do it once — it persists across reboots.

Stay in `Stable mode` for the safest launcher behavior, or switch to `Fast mode` from `Ring modes` if you want launcher acceleration.

## Build From Source

Requirements: Android SDK with API 36, Java 17, and ADB access to the glasses.

```powershell
.\gradlew.bat assembleDebug          # build the glasses APK
.\gradlew.bat lintDebug              # lint
.\gradlew.bat testDebugUnitTest      # unit tests
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The project has no private release signing configuration; GitHub release APKs are debug-signed unless a release signing setup is added.

## Debugging

```powershell
# Useful log tags
adb logcat -v time -s R08Bridge:D R08Ble:D R08Navigator:D R08LocalSelfArm:D *:S

# Probe the R08 media-key profile / appType 1 from ADB
adb shell am start -n com.anezium.r08accessbridge/.MainActivity --ei probe_app_type 1 --ez exit_after_probe true
```

## Privacy And Permissions

R08 Access Bridge requests:

- Bluetooth permissions for scanning, pairing, reconnecting, and configuring the R08 ring.
- Location permission on Android versions where Bluetooth scanning requires it.
- Accessibility permission so ring input can control launcher/app navigation.
- Wake lock to keep ring connection maintenance reliable.

## Credits

- [hacha](https://x.com/hacha) shared the [`rokid-r08-wake`](https://github.com/hacha/rokid-r08-wake) loopback self-arm technique that the accessibility watchdog recovery path is built on. Ring control surviving the Rokid firmware force-stops exists because of his work. He also diagnosed the screen-off media-key leak to paired phones and contributed the media button session guard ([PR #1](https://github.com/hacha/R08-Access-Bridge/pull/1) on his fork) that `Keep screen-off taps on glasses` is built on.
- Reddit user `u/Rare_Wheel1907` found and confirmed the "update the ring firmware, then disconnect it from the official app before pairing" fix.

## Notes

- Stable mode is the recommended default. Fast mode is optional launcher acceleration; Touch fallback exists for experimentation when media-key mode is not usable.
- The app lets the ring's touch sleep timer work instead of sending an infinite wake command, which keeps idle ring power use lower.
- Native DPAD output was not observed in the tested `appType 0..7` range, so the app bridges media/touch outputs into navigation behavior.
- The app is designed for the Rokid glasses HUD, not a phone-first UI.
