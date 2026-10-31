# Issue #20: reboot recovery and native recording

## Cause and implementation

Firmware `1.28.016` ignores the old scene broadcast on production builds. The
2.0.6 workaround injected the camera button, whose firmware route deliberately
starts video/audio with `video_no_ui` / `audio_no_ui`. Accessibility Back does not
reliably stop these background recordings. Shell helper recovery also depends on
ADB provisioning and, with Nexus, its recovery policy.

The firmware's exported `MasterAssistService` still offers its regular media
Binder interface (`com.rokid.os.sprite.assist.server.IAssistServer`). R08 binds as
itself, registers its own callback, and uses the native commands:

- Photo: `cmd_take_picture`, `cameraOpenType=picture_with_ui`.
- Video: `cmd_start_video_record` / `cmd_stop_video_record`, `cameraOpenType=video_with_ui`.
- Audio: `cmd_start_audio_record` / `cmd_stop_audio_record`, `audioOpenType=audio_with_ui`.
- AI: `cmd_open_scene_with_ignore_tips`, `sceneKey=ai_assist`.

The firmware launcher receives its normal media callbacks and displays its own
recording indicator and timer. R08 observes native scene snapshots and terminal
recording callbacks. Double tap sends explicit stop commands only while a
recording is actually running, otherwise preserving normal Back. It does not
toggle a camera button or persist an assumed recording state. Native actions
reconnect with accessibility after reboot independently of shell helper recovery.

Photo/AI retain the shell fallback. Hi Rokid Shortcut still uses the shell helper;
AR capture remains outside this change. Native AI voice services retain the
firmware's requirement for a Hi Rokid phone connection.

The maintenance self-arm now preserves a private paired key over a stale legacy
external copy and distinguishes missing keys / failed Wireless ADB from waiting
for a live Nexus watchdog. No ADB keys or native SDK credentials are included.

## Validation on firmware 1.28.016

- Full app JVM suite: 141 tests, no failures; debug APK builds.
- Real video and audio: firmware timer visible, native start state confirmed,
  explicit stop confirmed by native terminal state.
- Double tap through the tap recognizer and through injected events on the
  actual R08 HID input device stops both video and audio.
- Reboot: native service reconnects automatically before any manual R08 launch.
  Photo and audio requests work without phone provisioning after reboot. Video
  and audio indicators use the firmware launcher, not a custom R08 overlay.
- Photo works with the R08 shell helpers stopped. AI opens the native assistant;
  with Hi Rokid disconnected it correctly displays the firmware's phone connection
  requirement, rather than requesting an R08 Companion re-arm.

The physical gesture/action mappings are preserved. Audio toggle is available as
a new action; no existing user gesture is reassigned by this change.

## Local smoke hooks

On debuggable builds only, the existing shell-protected `RecoveryActivity` accepts
`--es debug_recording video|audio|photo|ai|double_tap|stop|back`. It finishes
immediately without replacing the foreground screen. `double_tap` runs two taps
through the normal recognizer; `back` uses the navigator. Release builds ignore
the extra. Always stop test recordings, and check native state returns to idle.

## Hi Rokid can be disconnected while Bluetooth is on

The glasses' crossed Bluetooth indicator and "Mobile app disconnected" message
can refer to the Hi Rokid control connection, even while phone audio and the R08
ring are connected. A device check found `SOCKET_CONNECT_FAILED` in Hi Rokid:
its cached RFCOMM socket identifier differed from the glasses' current listening
service. Restarting Hi Rokid or cycling Bluetooth alone did not update that cache.

Rediscovering the same glasses through Hi Rokid's device management / Add device
flow refreshed the connection. The glasses accepted the control socket and the
phone reported `BLUETOOTH_AVAILABLE`. No R08 settings or ring pairing needed to be
removed. Do not confuse this repair with rerunning R08 Companion provisioning,
and do not publish either device's socket identifiers or Bluetooth addresses.

After a subsequent full glasses reboot, Hi Rokid reconnected automatically and
the disconnected-app message stayed gone. The ring HID input and its GATT
bootstrap also recovered; the configured photo/video/audio/AI mappings persisted.
