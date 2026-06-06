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

## Project Details

| Item | Value |
| --- | --- |
| App name | R08 Access Bridge |
| Package name | `com.anezium.r08accessbridge` |
| Minimum Android SDK | 28 |
| Target Android SDK | 34 |
| Primary target | Rokid glasses / YodaOS-Sprite, 480x640 portrait HUD |
| Input device | R08 BLE ring |

## What It Does

- Pairs or reconnects to an R08 ring over Bluetooth LE.
- Enables Stable mode by default using R08 `appType 1`, which emits media key events.
- Converts ring inputs into one-axis navigation suitable for Rokid glasses.
- Uses Android Accessibility to move focus, scroll, click, inject launcher swipes, and perform Back.
- Keeps the in-app HUD compact and readable on a 480x640 glasses display.
- Lets you switch between Stable, Fast, and Touch fallback behavior from one APK.
- Lets you remap triple and quadruple tap actions directly from the glasses UI.
- Provides an AppType probe screen for testing R08 output modes.
- Provides a safe Forget R08 flow to remove the saved Bluetooth bond and pair again.

## Screenshots

<p align="center">
  <img src="docs/screenshots/home.png" alt="R08 Access Bridge home screen" width="220">
  <img src="docs/screenshots/ring-modes.png" alt="Ring Modes screen with Stable mode active" width="220">
  <img src="docs/screenshots/action-mapping.png" alt="Action Mapping screen for triple and quadruple tap" width="220">
</p>

## Controls

By default, the R08 ring is configured to emit media keys in Stable mode:

| Ring input | Meaning |
| --- | --- |
| Forward / next | Move forward through the launcher or current app focus |
| Backward / previous | Move backward through the launcher or current app focus |
| Single tap | Activate the current app, button, or focused item |
| Double tap | Android Back |
| Triple tap | Configurable action, defaults to Rokid AI assistant |
| Quadruple tap | Configurable action, defaults to no action |

Inside R08 Access Bridge itself, double tap goes back to the previous screen. On the root screen, Back exits the app and returns to the launcher.

Triple tap defaults to the same Rokid AI assistant scene used by the glasses long-press path. The protected Hi Rokid two-finger shortcut broadcast cannot be sent by a normal APK, so this is intentionally an AI/long-press shortcut rather than an exact two-finger shortcut clone.

Triple and quadruple tap can be remapped in the app to:

- No action
- Rokid AI
- Take photo
- Video toggle
- AR screenshot
- AR video toggle

## Input Modes

The release APK now contains both launcher behaviors. There is no separate fast APK and focus-sync APK anymore:

| Mode | Behavior | Use when |
| --- | --- | --- |
| Stable | One launcher step per slide. This is the default. | Most users, especially when launcher focus can drift. |
| Fast | Uses boosted launcher swipes after repeated slides. | Your glasses keep visual focus and launched app aligned, and you want faster launcher movement. |
| Touch | Configures the R08 touch fallback profile. | Debug only, when key input is not usable. |

The current mode is shown in the top bar of the app so the active behavior is visible at a glance.

## Launcher Behavior

The Rokid launcher does not reliably respond to normal Accessibility scroll calls, so R08 Access Bridge injects small horizontal launcher swipes instead.

The launcher movement is tuned to keep the visible Rokid launcher selection and the ring action aligned:

- Each ring swipe moves one normal launcher step.
- Fast mode accelerates repeated launcher slides with boosted swipes instead of issuing a second tiny swipe that the Rokid launcher may ignore.
- Activation taps the visible center app in the launcher carousel.
- Triple tap opens the Rokid AI assistant, matching the glasses system long-press shortcut.

This avoids relying on stale launcher accessibility focus. Fast mode can re-enable repeated-swipe acceleration from the `Ring modes` screen when a device handles it correctly.

## App Screens

### Home

- `Pair / Reconnect` scans for an R08 ring, connects to a bonded ring, or restarts the connection.
- `Ring modes` opens input mode settings.
- `Action mapping` opens triple and quadruple tap mapping.
- `System` opens permissions and reset actions.

### Action Mapping

- `Triple Tap` chooses what three taps trigger.
- `Quadruple Tap` chooses what four taps trigger.

### Ring Modes

- `Stable mode` restores the recommended `appType 1` media-key mode with one launcher step per slide.
- `Fast mode` keeps `appType 1` and enables launcher acceleration after repeated slides.
- `Touch fallback` configures `appType 4` touch-style fallback mode for debugging.
- `AppType probe` lets you test `appType 0` through `appType 7` and inspect key output in logs.

### System

- `Accessibility` opens Android Accessibility settings so the service can be enabled.
- `App settings` opens Android app details for Bluetooth permissions and system settings.
- `Forget R08` opens a confirmation screen and removes the bonded R08 ring when confirmed.

## Installation

Download the APK from the GitHub Releases page:

[R08 Access Bridge releases](https://github.com/Anezium/R08-Access-Bridge/releases)

Then install it on the glasses:

```powershell
adb install -r R08-Access-Bridge-v1.0.10.apk
```

After installation:

1. Open `R08 Access Bridge`.
2. Grant Bluetooth permissions if Android asks.
3. Open `System` -> `Accessibility`.
4. Enable the `R08 Access Bridge` accessibility service.
5. Return to the app and select `Pair / Reconnect`.
6. Keep the R08 ring nearby and allow pairing if Android asks.
7. Stay in `Stable mode` for the safest launcher behavior, or use `Ring modes` -> `Fast mode` if you want launcher acceleration.

## Build From Source

Requirements:

- Android SDK with API 34 installed.
- Java 17.
- ADB access to the Rokid glasses for install/testing.

Build a debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Run lint:

```powershell
.\gradlew.bat lintDebug
```

Install on a connected device:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The current project does not include a private release signing configuration. GitHub release APKs are debug-signed unless a release signing setup is added.

## Debugging

Useful log tags:

```powershell
adb logcat -v time -s R08Bridge:D R08Ble:D R08Navigator:D R08Activity:D R08RokidSystem:D *:S
```

Probe the R08 media-key profile / `appType 1` from ADB:

```powershell
adb shell am start -n com.anezium.r08accessbridge/.MainActivity --ei probe_app_type 1 --ez exit_after_probe true
```

Probe another app type:

```powershell
adb shell am start -n com.anezium.r08accessbridge/.MainActivity --ei probe_app_type 4 --ez exit_after_probe true
```

## Privacy And Permissions

R08 Access Bridge requests:

- Bluetooth permissions for scanning, pairing, reconnecting, and configuring the R08 ring.
- Location permission on Android versions where Bluetooth scanning requires it.
- Accessibility permission so ring input can control launcher/app navigation.
- Wake lock to keep ring connection maintenance reliable.

## Notes

- Stable mode is the recommended default.
- Fast mode is optional launcher acceleration for devices where visual focus and launched app stay aligned.
- Touch fallback exists for experimentation when media-key mode is not usable.
- Native DPAD output was not observed in the tested `appType 0..7` range, so the app bridges media/touch outputs into navigation behavior.
- The app is designed for the Rokid glasses HUD, not a phone-first UI.
