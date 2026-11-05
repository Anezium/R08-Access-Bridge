# Native Rokid assistant controls from the Ring

## Cause and behavior

On firmware `1.28.016`, `AiAssistantDialog.onKeyUp(KEYCODE_ENTER)` pins the
assistant with `holdAIAssistant()`. The visible `id_tv_hold_tip` label is not
clickable, so R08's generic accessibility/touch activation only tapped the chat
content and never triggered that key handler.

`NativeAssistantControls` recognizes the active native assistant by its package,
AI container and visible pin hint resource IDs. A single tap requests Enter
through the existing acknowledged shell mailbox, now shared as
`NativeUiKeyRequest`. No gesture mapping changes are needed. Other screens and
already pinned chats retain normal activation.

Ring slides request native DPAD Down / Up on the visible `id_rv_ai_content`
chat. `ErAIRecyclerView.dispatchKeyEvent` moves approximately 85% of its measured
height per key and calls the dialog scroll listener to disable automatic text
scrolling. This route precedes generic focus traversal. Up to three additional
slides are queued in order while a key awaits acknowledgement; each queued step
checks the active chat again. Back clears the queue. The directional request
explicitly names the `assistant` target, so it cannot use the launcher's resumed
activity underneath the system dialog as its foreground check.

The request has a helper session, unique token and 900 ms expiry. The helper
checks the focused system window, expiry and current mailbox again before
injecting. It consumes a token before injection, so it cannot replay. Back
cancels a pending request best-effort. No polling is added while idle.

The helper advertises `assistant_pin` and `assistant_scroll`; an old or unavailable helper produces an
actionable self-arm / companion re-arm message. The helper freshness check now
requires these features so normal maintenance replaces old helpers. Both glasses
and companion builds package the updated script.

Disable Nexus assistant takeover to use the native Rokid assistant. Its phone
voice connection still needs Hi Rokid; pinning does not implement or replace
that connection.

## Validation, 2026-11-05

- App debug build and all 147 JVM tests pass. Companion debug build passes.
- On-device shell test with stubbed input: Enter delivered once; duplicate,
  expired, background and unsupported-key/target requests rejected; both assistant
  scroll directions delivered; launcher directional
  entry preserved. Run `tools/test-r08-assistant-pin.sh` on Android with the
  helper script path as its argument. These tests inject no real UI input.
- Before the fix, an event injected through the actual R08 HID input device
  produced a generic touch tap and left the pin hint visible.
- After installing the debug APK and updated helper, the same HID input reached
  the native Enter key-up handler. Firmware logs confirmed `holdAIAssistant` and
  `setAutoExit isNotAutoExit: true`; the accessibility tree and screenshot showed
  `id_iv_hold_tip` instead of the pin label.
- Ring double tap still reaches native Back with `isHold: true`. The assistant
  was closed after testing.
- Both slide directions injected through the actual R08 HID device reached
  `ErAIRecyclerView` DPAD Down / Up and the native `setScrollListener` callback.
  This smoke test used the initial listening item (no long text to scroll).
  The user subsequently confirmed scrolling a long answer with the Ring works
  in real use, as well as the pin behavior.
- Nexus phone processes were inactive, Hi Rokid was running, and native Rokid AI
  opened in listening mode. A complete spoken question/answer was not tested.

The test device's automatic Wireless ADB maintenance reported an SSL error, so
the updated helper was deployed over its existing USB ADB connection. This is
separate from the pin fix; successful automatic helper migration on that device
was not demonstrated.

Debug builds also accept `RecoveryActivity --es debug_recording single_tap` for
the existing tap recognizer smoke path. The activity is protected by `DUMP` and
release builds ignore the extra. Device validation above used the R08 HID path.
