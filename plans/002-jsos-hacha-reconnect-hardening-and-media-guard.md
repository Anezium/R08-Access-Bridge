# Plan 002: BLE reconnect hardening (JSOS-inspired) and tri-state media key guard (hacha-inspired)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report - do not improvise. When done, update the status row for this plan in
> `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat b790374..HEAD -- app/src/main/java/com/anezium/r08accessbridge/RingBleController.java app/src/main/java/com/anezium/r08accessbridge/MediaKeyGuard.java app/src/main/java/com/anezium/r08accessbridge/RingControlAccessibilityService.java app/src/main/java/com/anezium/r08accessbridge/RingModeSettings.java app/src/main/java/com/anezium/r08accessbridge/MainActivity.java app/src/main/res/values/strings.xml`
>
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: L (Part A: M, Part B: S)
- **Risk**: MED (Part A touches the only path that talks to the ring)
- **Depends on**: none
- **Category**: reliability, feature
- **Planned at**: commit `b790374`, 2026-08-03

## Why this matters

Two external projects shipped work we audited and partially want:

1. **JSOS v2.0.37 "R08 fast reconnect"**
   (https://github.com/IWhatsskill/JSOS/releases/tag/v2.0.37-r08-fast-reconnect)
   hardened its R08 reconnect path: GATT setup timeout with capped backoff
   (3/5/8/12 s), stale-GATT-callback rejection, all callbacks marshalled to
   one handler, reconnect on adapter `STATE_ON`. A static audit of our
   `RingBleController` confirmed we have the same failure modes they fixed:
   - No GATT setup timeout: if `onServicesDiscovered` never arrives after
     `discoverServices()`, we wait forever.
   - If a characteristic write or the CCCD descriptor write never calls back,
     `writing`/`descriptorWriting` stays true and the write queue is dead
     permanently.
   - No timeout on `createBond()`.
   - Fixed 5 s retry, and `scheduleReconnect()` neither deduplicates nor
     cancels, so overlapping failures stack multiple retry runnables.
   - No callback checks that it belongs to the current `gatt`. A delayed
     disconnect callback from an old connection calls `closeGatt()` on the
     global field and closes the brand-new replacement connection. Callbacks
     also mutate state directly on Bluetooth binder threads while the main
     handler runs retries; nothing is synchronized.
   - No `BluetoothAdapter.ACTION_STATE_CHANGED` receiver. If the service
     starts while Bluetooth is off, `startScan()` returns silently and the
     recovery chain is dead until service restart.

2. **hacha's fork** (https://github.com/hacha/R08-Access-Bridge, branches
   `fix-screen-off-media-key-leak` and `media-key-guard`, 2026-07-04/05).
   The core screen-off media guard already landed in our main in refined form
   (commit `3bc8d36`). The one thing we did not adopt is the **always-on
   guard**: hold the media button session permanently so ring media keys
   never reach any media target, even during active playback. Use case: user
   streams phone audio to the glasses; today a ring tap during playback
   pauses the music (our guard deliberately backs off while music is
   active). We adopt it as a third state of the existing setting rather than
   a second toggle.

### Considered and rejected (do not implement)

- **JSOS separate process for the bridge**: their HUD/CXR process resets
  routinely; ours does not. A separate process would not survive the
  firmware force-stops we already handle via self-arm, costs RAM on the
  glasses, and would break same-process SharedPreferences visibility for
  gesture mappings (would then need IPC). Bad trade for us.
- **JSOS cross-process gesture sync**: only needed with a separate process.
  Our service re-reads mappings from SharedPreferences per gesture; already
  immediate.
- **hacha's guard-on-by-default**: our `Keep screen-off taps on glasses` is
  deliberately opt-in; keep that product decision. Migration below preserves
  each user's current choice.

## Source material

- JSOS release notes (see URL above): backoff schedule 3/5/8/12 s; retry
  counter resets only once required GATT services and write characteristics
  are ready; stale service-discovery and characteristic-write callbacks
  discarded; all R08 GATT callbacks on the controller's main handler.
- hacha fork diff vs our main: `MediaKeyGuard.setAlwaysOn(boolean)`, claim
  forced and music-backoff bypassed while always-on, screen-on release
  skipped while always-on, plus a `Ring modes` toggle wired through a
  service command. Port the semantics onto OUR `MediaKeyGuard` (ours
  additionally releases the claim when external music starts and releases
  the silence `AudioTrack` on claim release - keep both refinements, gated
  behind not-always-on where noted).
- Static audit of `RingBleController.java` at commit `b790374` (line
  references below are against that commit).

## Current state (excerpts at `b790374`)

`app/src/main/java/com/anezium/r08accessbridge/RingBleController.java`:

- Constants: `SCAN_TIMEOUT_MS = 25_000L`, `RECONNECT_MS = 5_000L` (lines
  51-52). Single `Handler` on the main looper (line 56), `ArrayDeque` write
  queue (line 57), mutable globals `gatt`, `writeCharacteristic`,
  `targetDevice`, flags `writing`, `descriptorWriting`,
  `notificationsEnabled` (lines 59-70).
- `gattCallback` (lines 120-190): `onConnectionStateChange` assigns
  `gatt = bluetoothGatt` on connect and calls `closeGatt()` +
  `scheduleReconnect()` on disconnect, unconditionally.
  `onServicesDiscovered` schedules reconnect on explicit failure only.
  `onCharacteristicWrite` clears `writing` and re-drains.
  `onDescriptorWrite` clears `descriptorWriting`, calls `onGattReady(...)`,
  drains. No callback validates `bluetoothGatt` against the current field;
  no callback posts to `handler` before mutating state.
- `start()` (lines 200-224): registers only
  `BluetoothDevice.ACTION_BOND_STATE_CHANGED`; bonded ring -> direct
  `connect()`, else `startScan()`.
- `startScan()` (lines 380-401): `scanner == null` -> log + return, no
  retry scheduled.
- `bondOrConnect()` (lines 413-426): `createBond()` with no timeout; bond
  receiver (lines 81-97) connects on `BOND_BONDED` only.
- `connect()` (lines 428-436): `closeGatt()` then
  `device.connectGatt(context, false, gattCallback, TRANSPORT_LE)`; no
  watchdog armed.
- `closeGatt()` (lines 438-447): operates on the global `gatt` field.
- `scheduleReconnect()` (lines 449-460): `handler.postDelayed(...,
  RECONNECT_MS)` guarded only by `started`; no dedupe, no counter.
- `enableNotifications()` (lines 462-491): sets `descriptorWriting = true`
  before submitting the CCCD write; no timeout.
- `drainWrites()` (lines 501-523): returns while `descriptorWriting` or
  `writing` is true; retries only on synchronous submit failure.
- `onGattReady(boolean)` (lines 346-352): configures the ring mode and
  optionally requests battery. This is the natural "fully ready" milestone.
- `stop()` (lines 226-236) calls `handler.removeCallbacksAndMessages(null)`,
  which will also clear every watchdog this plan adds - keep that property.

`app/src/main/java/com/anezium/r08accessbridge/MediaKeyGuard.java`:

- No `alwaysOn` concept. `start()` claims only when `!isInteractive()`
  (lines 125-127). `claim()` bails when `isInteractive()` (non-forced,
  lines 171-173) and skips while `audioManager.isMusicActive()` (lines
  175-178). The playback callback releases the claim when
  `isExternalMusicActive(configs)` (lines 74-80, 239-248). `onScreenOn()`
  always releases (lines 155-158). `releaseClaim()` releases the silence
  track and cancels `reclaimCheck` only for the `screen_on` reason (lines
  190-202).

Wiring of the existing boolean setting:

- `RingModeSettings.PREF_SCREEN_OFF_MEDIA_GUARD = "screen_off_media_guard"`,
  default `false` (RingModeSettings.java lines 13, 50-56).
- `RingControlAccessibilityService.COMMAND_SET_SCREEN_OFF_MEDIA_GUARD =
  "set_screen_off_media_guard"` with boolean `EXTRA_ENABLED` (lines 42,
  162-163), handled by `setScreenOffMediaGuardEnabled(boolean)` (lines
  265-277) which creates/destroys the guard; `onServiceConnected()` creates
  it when the pref is on (lines 320-323).
- `MainActivity`: Ring Modes row `action_screen_off_media_guard` +
  `toggleScreenOffMediaGuard()` (lines 294-295, 516-523), detail helper at
  lines 473-477.
- A repo-wide grep confirmed no other consumer of
  `set_screen_off_media_guard` (not in `bridge-protocol/`, `phone/`, or
  docs), so the command and its extra can be reshaped freely.

---

## Part A - RingBleController reconnect hardening

All changes in `RingBleController.java` only. Three mechanisms, implemented
in this order because A1 makes A2 safe to reason about.

### A1. Marshal GATT callbacks to the main handler and reject stale ones

1. In every `BluetoothGattCallback` override, wrap the entire body in
   `handler.post(() -> { ... })`. Do not read or write controller state on
   the binder thread. (`onCharacteristicChanged` may extract the byte value
   before posting - the payload is per-call data, not shared state.)
2. Inside the posted runnable, first validate the callback's gatt against
   the current field: `if (bluetoothGatt != gatt) { ... stale ... }`.
   Android passes the same `BluetoothGatt` instance that `connectGatt`
   returned, and `connect()` assigns that return value to the field, so
   identity comparison is correct. The check must run inside the posted
   runnable (state may legitimately change between binder delivery and
   main-thread execution - that is the race being closed).
   - Stale `onConnectionStateChange`: call `bluetoothGatt.close()` (close
     the stale object itself, never the global) and return. Never touch
     `closeGatt()`/`scheduleReconnect()` from a stale callback.
   - All other stale callbacks: log at debug and return.
3. `onConnectionStateChange(STATE_CONNECTED)` no longer needs
   `gatt = bluetoothGatt` (identity already holds); keep the state resets
   (`writeCharacteristic = null`, clear queue/flags) as they are.
4. `closeGatt()` already nulls the field, so every late callback from a
   closed connection fails the identity check. No generation counter is
   needed - do not add one.

### A2. Setup watchdog, per-operation watchdog, capped backoff, dedupe

Constants (replace `RECONNECT_MS`):

```java
private static final long[] RECONNECT_BACKOFF_MS = {3_000L, 5_000L, 8_000L, 12_000L};
private static final long SETUP_TIMEOUT_MS = 20_000L;   // connectGatt -> onGattReady
private static final long GATT_OP_TIMEOUT_MS = 8_000L;  // one write/descriptor op
private static final long BOND_TIMEOUT_MS = 30_000L;    // createBond -> BOND_BONDED
```

1. **Reconnect dedupe + backoff.** Replace the anonymous lambda in
   `scheduleReconnect()` with a named `reconnectRunnable` field. In
   `scheduleReconnect()`: `handler.removeCallbacks(reconnectRunnable)` then
   post with delay
   `RECONNECT_BACKOFF_MS[Math.min(reconnectAttempts, RECONNECT_BACKOFF_MS.length - 1)]`
   and increment `reconnectAttempts`. Reset `reconnectAttempts = 0` in
   exactly one place: `onGattReady(...)` - per JSOS, "ready" (services +
   write characteristic + notification setup finished) is the only reset
   milestone, so persistent discovery failures keep climbing the schedule.
   Log the chosen delay and attempt number.
2. **Setup watchdog.** Named runnable `setupTimeout`: on fire, log,
   `closeGatt()`, `scheduleReconnect()`. Arm (remove + postDelayed
   `SETUP_TIMEOUT_MS`) in `connect()` right after `connectGatt(...)`.
   Cancel in `onGattReady(...)` and in `closeGatt()`. This single watchdog
   covers connection, service discovery, and notification setup, because
   `onGattReady` is only reached after all three.
3. **Per-operation watchdog.** Named runnable `gattOpTimeout`: on fire,
   log, `closeGatt()`, `scheduleReconnect()` (a wedged write means the link
   is unusable; a clean reconnect re-runs mode config anyway via
   `onGattReady`). Arm when `drainWrites()` submits a write
   (`writing = true`) and when `enableNotifications()` sets
   `descriptorWriting = true`; cancel in `onCharacteristicWrite`,
   `onDescriptorWrite`, and `closeGatt()`.
4. **Bond watchdog.** Named runnable `bondTimeout`: on fire, log,
   `scheduleReconnect()`. Arm in `bondOrConnect()` when `createBond()`
   returns true; cancel in the bond receiver on `BOND_BONDED` (before
   `connect(device)`) and also handle `BOND_NONE` there as an immediate
   `scheduleReconnect()` (bonding rejected/failed).
5. `stop()` keeps `handler.removeCallbacksAndMessages(null)`; verify it
   runs after this change too (it clears all four named runnables - that is
   intended).

### A3. Bluetooth adapter state receiver

1. Register a second receiver (same `RECEIVER_EXPORTED` pattern as
   `bondReceiver`, lines 211-216) for
   `BluetoothAdapter.ACTION_STATE_CHANGED` in `start()`; unregister in
   `stop()` with the same try/catch style.
2. On `STATE_ON`: `ensureAdapter()`, cancel any pending reconnect and
   watchdogs, reset `reconnectAttempts = 0`, then attempt connection using
   the same logic as `restart()` lines 243-253 (prefer `targetDevice`, else
   bonded lookup, else scan). Extract that block into a private
   `connectToKnownOrScan()` used by `start()`, `restart()`, the receiver,
   and `reconnectRunnable`, instead of duplicating it a fourth time.
3. On `STATE_TURNING_OFF`/`STATE_OFF`: `stopScan()`, `closeGatt()`, cancel
   pending reconnect and all watchdogs. Do not schedule retries while the
   adapter is off - recovery is the `STATE_ON` branch's job. Log
   "waiting for Bluetooth".
4. In `startScan()` when `getBluetoothLeScanner()` returns null, and in
   `connect()`/`reconnectRunnable` when the adapter is null or disabled
   (`!adapter.isEnabled()`), log "waiting for Bluetooth" and return without
   scheduling - same rationale.

### Part A verification

| Check | Command | Expected |
|-------|---------|----------|
| Build | `.\gradlew.bat assembleDebug` | exit 0 |
| Lint | `.\gradlew.bat lintDebug` | exit 0 or only pre-existing warnings |
| Logs | `adb logcat -v time -s R08Ble:D *:S` | see matrix below |

Manual matrix (glasses + ring, watch `R08Ble` logs):

1. Normal boot with bonded ring -> connects, one "GATT ready", attempts
   counter resets (later failures start again at 3 s).
2. Take the ring out of range mid-session -> disconnect, retries at 3, 5,
   8, then repeated 12 s; bring it back -> reconnects, mode config re-runs,
   ring input works. Exactly one scheduled retry at any time (no
   interleaved duplicate "Connecting GATT" bursts).
3. Toggle Bluetooth off -> "waiting for Bluetooth", no retry spam; toggle
   on -> reconnect within ~3 s + connection time, ring works.
4. Reboot glasses with Bluetooth off, let the service start, then enable
   Bluetooth -> ring connects without touching the app (this is the
   scenario that dead-ended before).
5. Ring input latency unchanged in normal operation (callbacks now hop
   through the main handler - taps and swipes must feel identical).

## Part B - Tri-state media key guard

Files: `MediaKeyGuard.java`, `RingModeSettings.java`,
`RingControlAccessibilityService.java`, `MainActivity.java`, `strings.xml`.

### B1. Setting: mode instead of boolean

In `RingModeSettings`:

```java
static final int MEDIA_GUARD_OFF = 0;
static final int MEDIA_GUARD_SCREEN_OFF = 1;
static final int MEDIA_GUARD_ALWAYS = 2;
private static final String PREF_MEDIA_GUARD_MODE = "media_guard_mode";
```

`getMediaGuardMode(Context)`: if `PREF_MEDIA_GUARD_MODE` is absent, migrate
from the legacy boolean - `PREF_SCREEN_OFF_MEDIA_GUARD` true ->
`MEDIA_GUARD_SCREEN_OFF`, else `MEDIA_GUARD_OFF` - write the migrated value
and return it. `setMediaGuardMode(Context, int)` clamps to the three
values. Delete `isScreenOffMediaGuardEnabled`/`setScreenOffMediaGuardEnabled`
and update all callers; keep the legacy pref key constant only for the
migration read.

### B2. MediaKeyGuard: `setAlwaysOn`

Port hacha's semantics onto our refined guard (do not copy his file - ours
releases the claim when external music starts and frees the silence track on
release; both must survive):

1. Add `private boolean alwaysOn;` and `void setAlwaysOn(boolean enabled)`:
   set the field; if not `started`, return; if enabled ->
   `claim("guard_enabled", true)`; else if `isInteractive()` ->
   `releaseClaim("guard_disabled")`.
2. `start()`: claim when `alwaysOn || !isInteractive()`.
3. `claim()` non-forced bail-outs: skip the `isInteractive()` bail when
   `alwaysOn`. The `audioManager.isMusicActive()` skip applies only when
   `!alwaysOn` (always-on claims over active playback - that is its point).
4. Playback callback: call `releaseClaim("music_active")` only when
   `!alwaysOn`. Keep scheduling `reclaimCheck` in both modes (in always-on
   it re-claims if anything stole the session).
5. `onScreenOn()`: release only when `!alwaysOn`.
6. Update the class javadoc: describe both scopes in one paragraph each,
   same tone as the current comment.

### B3. Service wiring

Replace `COMMAND_SET_SCREEN_OFF_MEDIA_GUARD`/`EXTRA_ENABLED` handling with
`COMMAND_SET_MEDIA_GUARD_MODE = "set_media_guard_mode"` carrying an int
`EXTRA_MODE = "mode"` (grep confirmed no external consumer). Replace
`setScreenOffMediaGuardEnabled(boolean)` with `setMediaGuardMode(int mode)`:

- Persist via `RingModeSettings.setMediaGuardMode`.
- `mode == OFF`: stop and null the guard (current disable branch).
- Otherwise: create/start the guard if absent, then
  `mediaKeyGuard.setAlwaysOn(mode == MEDIA_GUARD_ALWAYS)`.

`onServiceConnected()`: create the guard when
`getMediaGuardMode(this) != MEDIA_GUARD_OFF` and apply `setAlwaysOn(...)`
before `start()` so the initial claim logic sees the right mode.

### B4. UI

The Ring Modes row keeps the single-row tap pattern (rows are `action()`
entries, not switches): tapping cycles Off -> Screen-off only -> Always ->
Off, persisting + sending the service command each time. Strings
(`strings.xml` - replace the five `*_screen_off_media_guard*` entries):

```xml
<string name="action_media_guard">Ring media key guard</string>
<string name="detail_media_guard_off">Off - screen-off taps can reach the paired phone\'s media app</string>
<string name="detail_media_guard_screen_off">Screen-off only - dark-screen taps wake the glasses instead of playing music</string>
<string name="detail_media_guard_always">Always - ring input never controls media, even during playback</string>
<string name="toast_media_guard_off">Media key guard off</string>
<string name="toast_media_guard_screen_off">Screen-off taps stay on glasses</string>
<string name="toast_media_guard_always">Ring never controls media</string>
```

In `MainActivity`, replace `toggleScreenOffMediaGuard()` and
`screenOffMediaGuardDetail()` with mode-cycling equivalents; the detail line
is exactly the matching `detail_media_guard_*` string (no extra "Active -"
prefix; the mode name carries the state).

### B5. Docs

- `CHANGELOG.md`: add an `## Unreleased` section describing the tri-state
  guard and the reconnect hardening, and credit hacha's fork
  (`https://github.com/hacha/R08-Access-Bridge`) for the always-on guard
  concept.
- `README.md`: update the screen-off bullet in "What It Does" and the
  Ring Modes screen list to describe the three states; extend the existing
  screen-off media-key paragraph with one sentence on `Always`.

### Part B verification

| Check | Command | Expected |
|-------|---------|----------|
| Build | `.\gradlew.bat assembleDebug` | exit 0 |
| Lint | `.\gradlew.bat lintDebug` | exit 0 or only pre-existing warnings |
| Logs | `adb logcat -v time -s R08MediaGuard:D R08Bridge:D *:S` | see matrix |

Manual matrix (glasses paired to a phone as Bluetooth audio sink):

1. Upgrade with the old boolean ON -> mode reads Screen-off only; with it
   OFF -> mode reads Off (no user-visible change either way).
2. Screen-off only: dark screen + tap -> glasses wake, phone media app does
   not launch (the original leak fix still holds).
3. Screen-off only + music streaming from phone + glasses screen off + tap
   -> music pauses (documented, unchanged back-off behavior).
4. Always: same scenario -> glasses wake, music keeps playing, log shows
   "Consumed media key".
5. Always + screen on + music playing + tap -> navigation acts, music
   unaffected.
6. Cycle the row Off -> Screen-off -> Always -> Off without restarting the
   service; each state takes effect immediately (verify via logs).

## STOP conditions

- Drift check fails and the live code no longer matches the "Current state"
  excerpts for the section being edited.
- Any consumer of `set_screen_off_media_guard` or
  `isScreenOffMediaGuardEnabled` turns up outside the three known files.
- After A1/A2, ring taps/swipes show human-noticeable added latency, or the
  ring fails to reach "GATT ready" on a normal boot twice in a row.
- The always-on guard interferes with glasses system sounds or the camera
  shutter (the silence claim should be inaudible and 60 ms; anything
  audible is a STOP).
- Any need to touch `bridge-protocol/`, `phone/`, or the self-arm path -
  out of scope here.

## Acceptance checklist

- [ ] `.\gradlew.bat assembleDebug` exits 0.
- [ ] `.\gradlew.bat lintDebug` exits 0 or only pre-existing warnings.
- [ ] Part A manual matrix passes (5 scenarios).
- [ ] Part B manual matrix passes (6 scenarios).
- [ ] No remaining reference to `RECONNECT_MS`,
      `COMMAND_SET_SCREEN_OFF_MEDIA_GUARD`, or
      `isScreenOffMediaGuardEnabled`.
- [ ] CHANGELOG credits hacha's fork; README describes the three guard
      states.
- [ ] `plans/README.md` status row updated.
