# Plan 001: Add QRing-derived ring battery status and sleep-safe touch handling

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report - do not improvise. When done, update the status row for this plan in
> `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat 21ee603..HEAD -- app/src/main/java/com/anezium/r08accessbridge/RingBleController.java README.md app/build.gradle.kts`
>
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: direction, bug, docs
- **Planned at**: commit `21ee603`, 2026-06-15

## Why this matters

QRing 1.0.1.131 exposes the R08 ring battery level over the same custom BLE
service already used by R08 Access Bridge. R08 Access Bridge can surface this
to the glasses HUD and logs without adopting the full QRing health stack.

The same reverse-engineering pass also explains a likely battery drain cause:
QRing warns that touch/gesture modes increase power consumption and expects
touch to auto-disable after inactivity, while R08 Access Bridge currently sends
a TP sleep/wake packet every 18 seconds forever. That recurring wake likely
prevents the ring from entering its intended low-power idle state.

## Source material

- Local QRing input: `QRing_1.0.1.131_APKPure.xapk`, untracked in the repo.
- XAPK manifest observed from temp extract: package `com.app.cq.ring`,
  versionName `1.0.1.131`, versionCode `131`, min SDK `26`, target SDK `35`.
- Decompiled Java was produced under `%TEMP%/qring_jadx`. Treat those temp
  paths as evidence for this plan only; do not commit generated sources.
- QRing base APK was extracted under `%TEMP%/qring_apk_raw`. UI strings cited
  below were found in `resources.arsc`, not in a committed strings file.

## Confirmed QRing protocol facts

### BLE service and characteristics

QRing uses the same custom service and characteristics as R08 Access Bridge:

- Service: `6e40fff0-b5a3-f393-e0a9-e50e24dcca9e`
- Notify/read characteristic: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
- Write characteristic: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- Notify descriptor: standard CCCD `00002902-0000-1000-8000-00805f9b34fb`

Evidence:

- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/communication/Constants.java`
  defines `UUID_SERVICE`, `UUID_READ`, and `UUID_WRITE` with the values above.
- `app/src/main/java/com/anezium/r08accessbridge/RingBleController.java:40`
  through `:42` already define the same write, notify, and CCCD UUIDs.

QRing enables notifications on the read characteristic after service discovery:

- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/bluetooth/BleOperateManager.java:95`
  creates `EnableNotifyRequest(Constants.UUID_SERVICE, Constants.UUID_READ, ...)`.
- The same file at `:102` and `:332` sets enable true before executing it.

Current R08 Access Bridge state:

- `RingBleController.java:434` defines `enableNotifications(...)`.
- `rg -n "enableNotifications\\(" RingBleController.java` only finds the
  method declaration, so the helper is currently unused.
- `RingBleController.java:160` says writes are enough and avoids CCCD writes.
  That comment is now stale if battery responses are needed.

### Packet framing and CRC

QRing request packets are 16 bytes:

- byte 0: command key
- bytes 1 through 14: optional command subdata, zero-filled when absent
- byte 15: checksum, sum of bytes 0 through 14 modulo 256

Evidence:

- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/communication/req/BaseReqCmd.java`
  allocates `Constants.CMD_DATA_LENGTH`, writes the key to byte 0, copies
  `getSubData()` at byte 1, then stores `(sum & 255)` in the final byte.
- QRing `Constants.CMD_DATA_LENGTH` is `16`.

QRing response parsing masks the error bit before dispatch:

- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/bluetooth/QCDataParser.java`
  computes `bArr[0] & ~Constants.FLAG_MASK_ERROR`.
- The parser passes `Arrays.copyOfRange(bArr, 1, bArr.length - 1)` to response
  classes, so response payload byte 0 means original packet byte 1.

### Battery command

Battery is command `0x03` (`CMD_GET_DEVICE_ELECTRICITY_VALUE`).

The QRing request is `SimpleKeyReq((byte) 3)`, which has no subdata. The full
16-byte packet is:

```text
03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03
```

QRing parses the response payload like this:

- response payload byte 0: battery percent
- response payload byte 1: charging flag, `1` means charging

Evidence:

- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/communication/Constants.java:38`
  defines command `3`.
- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/communication/req/SimpleKeyReq.java`
  returns null subdata.
- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/communication/rsp/BatteryRsp.java`
  assigns `batteryValue = bArr[0]` and `charging = bArr[1] == 1`.
- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/bluetooth/BeanFactory.java`
  maps command `3` to `BatteryRsp`.

QRing reads battery in several normal app flows:

- `%TEMP%/qring_jadx/sources/com/qcwireless/smart/ui/base/receiver/MyBluetoothReceiver.java:37`
  sends `SimpleKeyReq((byte) 3)` during connection init.
- `%TEMP%/qring_jadx/sources/com/qcwireless/smart/base/lifecycle/QcLifeCycle.java:110`
  sends the same request when an activity resumes.
- `%TEMP%/qring_jadx/sources/com/qcwireless/smart/ui/device/DeviceFragment.java:1011`
  reads battery for the device page.
- `%TEMP%/qring_jadx/sources/com/qcwireless/smart/ui/mine/MineFragment.java:2108`
  reads battery for the mine/profile page.

### Touch, gesture, and TP sleep commands

Touch control is command `0x3B` (`59`, `CMD_DEVICE_TOUCH`).

QRing command subdata shapes:

| Purpose | Command | Subdata |
|---------|---------|---------|
| Read touch settings | `0x3B` | `01 00` |
| Read gesture settings | `0x3B` | `01 01` |
| Read special touch-only settings | `0x3B` | `01 02` |
| Write touch mode | `0x3B` | `02 00 <appType> <sleepMinutes>` |
| Write gesture mode | `0x3B` | `02 01 <appType> <strength>` |
| TP sleep/wake | `0x3B` | `02 02 <appType> <state>` |

Evidence:

- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/communication/req/TouchControlReq.java`
  contains the request constructors above.
- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/communication/rsp/TouchControlResp.java`
  parses read responses:
  - payload byte 0 is the read/write discriminator
  - payload byte 1 is touch vs gesture, where `0` means touch
  - payload byte 2 is app type
  - touch responses expose sleep time and touch sleep flag
  - gesture responses expose strength

Current R08 Access Bridge state matches QRing's packet shapes:

- `RingBleController.java:478` writes touch mode as
  `0x3B, 0x02, 0x00, appType, sleepMinutes`.
- `RingBleController.java:489` writes gesture mode as
  `0x3B, 0x02, 0x01, appType, strength`.
- `RingBleController.java:500` writes TP sleep/wake as
  `0x3B, 0x02, 0x02, category, state`.

Current R08 Access Bridge power-risk evidence:

- `RingBleController.java:49` sets `KEEPALIVE_MS = 18_000L`.
- `RingBleController.java:75` through `:83` runs a repeating keepalive.
- `RingBleController.java:81` sends `sendTpSleepWake()`.
- `RingBleController.java:163` and `:164` schedule that keepalive after GATT
  setup.
- `RingBleController.java:282`, `:295`, and `:309` configure touch with
  `sleepMinutes = 5`.
- `RingBleController.java:323` through `:325` sends TP sleep/wake for the
  current app type.

QRing UI/resource evidence:

- `%TEMP%/qring_apk_raw/resources.arsc` contains the English touch warning:
  touch control increases power consumption and, if unused for 10 minutes, is
  automatically turned off.
- The same resource blob contains a gesture warning that gesture control
  increases power consumption.

Interpretation:

- R08 Access Bridge already asks for a sleep-capable touch mode.
- The 18-second TP sleep/wake loop likely resets or prevents the ring's own
  idle timer, which explains battery drain while the ring is not being worn.

### Device support flags

QRing has a command `0x3C` (`60`, `CMD_DEVICE_FUNCTION_SUPPORT`) whose response
parses support/capability flags.

Useful flags found:

- `supportTouch`
- `supportGesture`
- per-app touch support flags such as music, video, ebook, camera, phone call,
  game, heart, and Muslim touch
- `tpSleep`
- `bodyTag`

Evidence:

- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/communication/Constants.java`
  defines `CMD_DEVICE_FUNCTION_SUPPORT = 60`.
- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/bluetooth/BeanFactory.java`
  maps command `60` to `DeviceSupportFunctionRsp`.
- `%TEMP%/qring_jadx/sources/com/oudmon/ble/base/communication/rsp/DeviceSupportFunctionRsp.java`
  parses `tpSleep` from byte 4 bit 4 and `bodyTag` from byte 8 bit 5.

Interpretation:

- These are capability flags, not current live state.
- `bodyTag` should not be treated as "ring is currently worn" without a real
  live-state response proving it.

### Wearing / not-worn findings

This pass did not find a simple QRing BLE command that continuously reports
"ring currently worn" or "ring currently not worn".

What was found:

- `%TEMP%/qring_apk_raw/resources.arsc` contains the UI string "Not worn.
  Please wear the device properly and measure again."
- QRing contains health and calibration flows that can reject a measurement as
  not worn.
- `DeviceSetting` fields such as `wristSense`, `wristSenseHand`,
  `leftOrRight`, `warmingHeart`, and `open` are configuration/state fields,
  not a cheap current wear-state signal.
- `UserProfileViewModel.execUserInfoToDevice(... heartWearing, wearingOpen)`
  writes user/profile settings to the device, not a live sensor result.
- `PalmScreenRsp` and `DisplayOrientationRsp` expose left/right orientation,
  not worn/not-worn.

Conclusion:

- Do not expose a ring worn/not-worn UI yet.
- A future deeper pass could instrument health commands, but that is higher
  power, harder to test, and outside the navigation controller use case.

### Other non-actionable observations

- QRing also reads the standard Device Information service `0000180A` and
  characteristics `2A26`, `2A27`, and `2A28` for firmware, hardware, and
  software revision.
- The XAPK includes an `arm64_v8a` split with native libraries, but the core
  ring BLE protocol inspected here is Java-side under `com.oudmon.ble`.
- QRing requests broad permissions, including BLE scan/connect/advertise,
  location, foreground service, wake lock, boot completed, notifications,
  network, camera, audio, settings, and ignore battery optimizations. R08
  Access Bridge should not copy these broadly; keep only permissions required
  for the bridge.
- QRing contains many health commands and monitoring features. They are not
  needed for navigation, and some explicitly trade accuracy for power usage.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Build | `.\gradlew.bat assembleDebug` | exit 0, debug APKs generated |
| Lint | `.\gradlew.bat lintDebug` | exit 0 or only pre-existing warnings |
| Device logs | `adb logcat -v time -s R08Bridge:D R08Ble:D R08Navigator:D R08Activity:D *:S` | logs show connection, battery request/response, no raw identifiers |

## Scope

**In scope**:

- `app/src/main/java/com/anezium/r08accessbridge/RingBleController.java`
- Any small UI/status file needed to display battery in the existing glasses
  HUD, if the executor confirms the project has a canonical status surface.
- README or changelog notes for user-visible battery reporting and power
  behavior.

**Out of scope**:

- QRing APK, XAPK splits, JADX output, or APK extraction folders.
- Health, sleep, heart-rate, blood-oxygen, ECG, AI analysis, or QRing account
  features.
- A ring worn/not-worn indicator.
- Broad QRing permissions that R08 Access Bridge does not need.
- Changes to launcher navigation semantics.

## Git workflow

- Branch suggestion: `advisor/001-qring-battery-sleep`.
- Existing repo history uses release-style changelog entries rather than a
  strict conventional-commit format. Keep commits small and descriptive.
- Do not push or publish release APKs unless instructed.

## Implementation steps

### Step 1: Enable QRing-style notifications

In `RingBleController.onServicesDiscovered`, get the notify characteristic from
the custom service and call the existing `enableNotifications(...)` helper.
Keep write queue ordering deterministic: descriptor writes must not overlap
normal characteristic writes.

Target behavior:

- The notify characteristic is resolved from `NOTIFY_CHAR_UUID`.
- CCCD enable is attempted once after GATT readiness.
- Logs include only notification enable success/failure and payload length, not
  MAC addresses or raw packet dumps.

**Verify**:
`rg -n "enableNotifications\\(|NOTIFY_CHAR_UUID|writeDescriptor" app/src/main/java/com/anezium/r08accessbridge/RingBleController.java`
shows a real call site plus the helper.

### Step 2: Add battery request and parser

Add a 16-byte packet builder for command `0x03` using the same CRC helper used
by existing touch packets.

Parse notifications with QRing's response rules:

- ignore null/short packets
- validate CRC before using values
- command key is `value[0] & ~0x80`
- for command `0x03`, battery percent is `value[1] & 0xFF`
- charging flag is `value[2] == 1`

Do not log raw notification bytes. Log a bounded status such as
`Ring battery=NN charging=true/false`.

**Verify**:
`rg -n "0x03|battery|charging|FLAG_MASK|checkCrc|requestBattery" app/src/main/java/com/anezium/r08accessbridge/RingBleController.java`
shows a request path and parser.

### Step 3: Request battery at low-risk times

Request battery:

- after notifications are enabled and GATT is ready
- after reconnect
- when returning the app to foreground if there is already a foreground hook
- optionally after manual pair/reconnect

Avoid polling aggressively. A 5-10 minute refresh while connected is enough if
the value is displayed persistently.

**Verify**:
`rg -n "requestBattery|postDelayed|postAtTime|onResume|onStart" app/src/main/java/com/anezium/r08accessbridge app/src/main/java`
shows no tight polling loop below 5 minutes.

### Step 4: Stop waking TP every 18 seconds forever

Replace the forever keepalive with battery-safe behavior:

- Keep the immediate wake after mode changes (`~540 ms`) so the selected mode
  activates promptly.
- Let QRing-style touch sleep do its work after the configured timeout.
- Stop scheduling `tpSleepWake(activeAppType, 1)` every `18_000 ms`.
- If a wake is still required for some firmware, gate it behind real need:
  app foreground, mode change, reconnect, or explicit user input recovery.

The safest first version is to remove the repeating `keepAlive` runnable and
keep only one-shot wake calls around mode changes/reconnect. If real-device
testing shows input dies too early, add a finite keepalive window with an idle
timeout instead of restoring an infinite loop.

**Verify**:
`rg -n "KEEPALIVE_MS|keepAlive|sendTpSleepWake\\(|postDelayed\\(keepAlive" app/src/main/java/com/anezium/r08accessbridge/RingBleController.java`
shows no infinite repeating 18-second wake path.

### Step 5: Surface battery without cluttering the HUD

Use the existing compact glasses UI pattern. Prefer a short status label such
as `Ring 82%` or `Ring 82% charging` in an existing top/status area. Avoid a
phone-style settings card or a new screen unless the existing UI already has a
status row meant for this.

If there is no clean status surface, keep the first implementation as logs and
internal state, then document that UI is deferred.

**Verify**:
`.\gradlew.bat assembleDebug` exits 0.

### Step 6: Document the user-visible behavior

Update public docs only after the feature exists:

- README: mention ring battery display if surfaced.
- README/Notes: mention that touch is allowed to sleep for ring battery life.
- CHANGELOG: add a new unreleased or next-version entry following existing
  style.

**Verify**:
`rg -n "battery|sleep|touch" README.md CHANGELOG.md`
shows the new notes, and `.\gradlew.bat lintDebug` exits 0 or only reports
pre-existing warnings.

## Test plan

- Build: `.\gradlew.bat assembleDebug`.
- Lint: `.\gradlew.bat lintDebug`.
- On connected glasses with a paired ring:
  - install the debug APK
  - open R08 Access Bridge
  - pair/reconnect the ring
  - confirm logs show GATT ready, notifications enabled, one battery request,
    and a parsed battery percent
  - confirm logs do not expose MAC address, serial number, or raw payload dumps
  - leave the ring idle longer than the configured touch sleep window and
    confirm there is no recurring 18-second TP wake
  - wake/reconnect/change mode and confirm navigation still works

## Done criteria

- [ ] Battery command `0x03` is sent only after notifications are enabled.
- [ ] Battery responses parse percent and charging state using QRing's payload
      shape.
- [ ] No raw BLE payloads, MAC addresses, serial numbers, or device-specific
      identifiers are logged.
- [ ] The infinite 18-second TP sleep/wake loop is gone or replaced by a finite,
      documented, idle-aware recovery path.
- [ ] `.\gradlew.bat assembleDebug` exits 0.
- [ ] `.\gradlew.bat lintDebug` exits 0 or only shows pre-existing warnings.
- [ ] Real-device smoke test confirms ring navigation still works after
      reconnect and mode changes.
- [ ] `plans/README.md` status row is updated.

## STOP conditions

Stop and report back if:

- `RingBleController.java` no longer matches the current-state excerpts in this
  plan.
- Battery responses arrive with a different length or payload shape than
  QRing's `BatteryRsp` implies.
- Enabling CCCD notifications destabilizes writes or disconnects the ring.
- Removing the infinite keepalive makes normal Stable mode unusable after less
  than the configured touch sleep window.
- A ring worn/not-worn feature appears to require health/PPG measurement flows.
  That is a separate power and privacy decision.

## Maintenance notes

- Keep QRing-derived protocol constants local to the BLE controller unless more
  commands are added. Avoid a large generic QRing SDK abstraction for one or
  two commands.
- If more QRing commands are adopted later, centralize packet framing and CRC
  first.
- Treat `bodyTag` as a capability flag until a live-state packet proves
  otherwise.
- Battery refresh should stay sparse. This app is a navigation bridge, not a
  health sync service.
