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
  Turn an R08 smart ring into a fast, one-axis controller for Rokid glasses.
</p>

R08 Access Bridge lets an R08 smart ring act as a navigation controller for Rokid glasses. It pairs with the ring over Bluetooth LE, configures the ring into a usable input mode, then translates ring input into launcher navigation, app focus movement, activation, and Android Back actions through an Accessibility Service.

This is an independent utility for Rokid glasses workflows. It is not an official Rokid application.

## Tech Stack

<p>
  <img alt="Android" src="https://img.shields.io/badge/Android-SDK%2028+-34A853?style=for-the-badge&logo=android&logoColor=white">
  <img alt="Java 17" src="https://img.shields.io/badge/Java-17-007396?style=for-the-badge&logo=openjdk&logoColor=white">
  <img alt="Gradle Kotlin DSL" src="https://img.shields.io/badge/Gradle-Kotlin%20DSL-02303A?style=for-the-badge&logo=gradle&logoColor=white">
  <img alt="Bluetooth LE" src="https://img.shields.io/badge/Bluetooth%20LE-GATT-0082FC?style=for-the-badge&logo=bluetooth&logoColor=white">
  <img alt="Accessibility Service" src="https://img.shields.io/badge/Accessibility-Service-5B5FC7?style=for-the-badge">
  <img alt="ADB" src="https://img.shields.io/badge/ADB-Debugging-3DDC84?style=for-the-badge&logo=android&logoColor=white">
</p>

## Built For

<p>
  <img alt="Rokid Glasses" src="https://img.shields.io/badge/Rokid-Glasses-111111?style=for-the-badge">
  <img alt="YodaOS Sprite" src="https://img.shields.io/badge/YodaOS-Sprite-6C5CE7?style=for-the-badge">
  <img alt="R08 Ring" src="https://img.shields.io/badge/R08-Smart%20Ring-00B894?style=for-the-badge">
  <img alt="HUD" src="https://img.shields.io/badge/HUD-480x640-0984E3?style=for-the-badge">
  <img alt="One-axis control" src="https://img.shields.io/badge/Input-One--Axis%20Control-FDCB6E?style=for-the-badge">
</p>

## Highlights

<p>
  <img alt="Fast mode" src="https://img.shields.io/badge/Fast%20Mode-Default-2ECC71?style=for-the-badge">
  <img alt="Focus sync APK" src="https://img.shields.io/badge/Focus--Sync-Optional%20Fix-9B59B6?style=for-the-badge">
  <img alt="Triple tap" src="https://img.shields.io/badge/Triple%20Tap-AI%20Shortcut-3498DB?style=for-the-badge">
  <img alt="No network" src="https://img.shields.io/badge/Network-Not%20Requested-2D3436?style=for-the-badge">
  <img alt="Debug signed" src="https://img.shields.io/badge/APK-Debug%20Signed-E67E22?style=for-the-badge">
</p>

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
- Enables Fast mode by default using R08 `appType 1`, which emits media key events.
- Converts ring inputs into one-axis navigation suitable for Rokid glasses.
- Uses Android Accessibility to move focus, scroll, click, inject launcher swipes, and perform Back.
- Keeps the in-app HUD compact and readable on a 480x640 glasses display.
- Provides an AppType probe screen for testing R08 output modes.
- Provides a safe Forget R08 flow to remove the saved Bluetooth bond and pair again.

The app does not request internet access and does not send ring or glasses data to a server.

## Controls

In Fast mode, the R08 ring is configured to emit media keys:

| Ring input | Meaning |
| --- | --- |
| Forward / next | Move forward through the launcher or current app focus |
| Backward / previous | Move backward through the launcher or current app focus |
| Single tap | Activate the current app, button, or focused item |
| Double tap | Android Back |
| Triple tap | Rokid AI assistant / glasses long-press shortcut |

Inside R08 Access Bridge itself, double tap goes back to the previous screen. On the root screen, Back exits the app and returns to the launcher.

Triple tap opens the same Rokid AI assistant scene used by the glasses long-press path. The protected Hi Rokid two-finger shortcut broadcast cannot be sent by a normal APK, so this is intentionally an AI/long-press shortcut rather than an exact two-finger shortcut clone.

## APK Variants

Each release includes two debug-signed APKs:

| APK | Use when | Tradeoff |
| --- | --- | --- |
| `R08-Access-Bridge-v1.0.2-debug.apk` | Your launcher selection already matches what the ring launches. This is the recommended daily APK. | Keeps repeated-swipe acceleration for faster launcher movement. |
| `R08-Access-Bridge-v1.0.2-focus-sync-debug.apk` | The launcher visually highlights one app but launches another, snaps back to Voice Translation/default translation, or the ring and glasses focus disagree. | Moves the launcher one app per swipe for stability. |

Install the normal APK first. If the launcher selection is wrong or unstable on your glasses, install the focus-sync APK instead.

## Launcher Behavior

The Rokid launcher does not reliably respond to normal Accessibility scroll calls, so R08 Access Bridge injects small horizontal launcher swipes instead.

The launcher movement is tuned to keep the visible Rokid launcher selection and the ring action aligned:

- Each ring swipe moves one normal launcher step.
- Activation taps the visible center app in the launcher carousel.
- Triple tap opens the Rokid AI assistant, matching the glasses system long-press shortcut.

This avoids relying on stale launcher accessibility focus, at the cost of removing repeated-swipe acceleration in the focus-sync build.

The normal APK keeps the original faster repeated-swipe acceleration behavior.

## App Screens

### Home

- `Pair / Reconnect` scans for an R08 ring, connects to a bonded ring, or restarts the connection.
- `Ring modes` opens input mode settings.
- `System` opens permissions and reset actions.

### Ring Modes

- `Fast mode` restores the recommended `appType 1` media-key mode.
- `Touch fallback` configures `appType 4` touch-style fallback mode.
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
adb install -r R08-Access-Bridge-v1.0.2-debug.apk
```

After installation:

1. Open `R08 Access Bridge`.
2. Grant Bluetooth permissions if Android asks.
3. Open `System` -> `Accessibility`.
4. Enable the `R08 Access Bridge` accessibility service.
5. Return to the app and select `Pair / Reconnect`.
6. Keep the R08 ring nearby and allow pairing if Android asks.
7. Use `Ring modes` -> `Fast mode` if navigation does not start immediately.

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

Force Fast mode / `appType 1` from ADB:

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

It does not request network, camera, microphone, contacts, storage, or account permissions.

## Notes

- Fast mode is the recommended mode.
- Touch fallback exists for experimentation when media-key mode is not usable.
- Native DPAD output was not observed in the tested `appType 0..7` range, so the app bridges media/touch outputs into navigation behavior.
- The app is designed for the Rokid glasses HUD, not a phone-first UI.
