# v1.5.0

Big responsiveness and input release: the ring finally reacts like a native controller, launcher swiping works the same for everyone, and ring gestures can now launch any installed app.

## Ring input responsiveness

- Single tap activates in ~350 ms instead of ~700 ms.
- Double tap Back is instant on the second tap when nothing longer is mapped (was up to 1.4 s).
- Multi-tap recognition is now adaptive: the app only waits after a tap when a longer gesture (more taps or a tap+swipe combo) is actually mapped.
- Triple and quadruple tap now default to `No action` so taps are fast out of the box; both stay fully mappable. Devices that received the old auto-assigned quadruple default are migrated.
- Duplicated BLE HID key events are filtered globally, and double-tap Back respects the Back debounce, so no more phantom double moves or Back bursts.

## Launcher swipe latency

- The launcher swipe cycle dropped from ~520 ms to ~270 ms per step — about half the app-side delay between a ring swipe and the carousel move.
- Rapid swipes accumulate into boosted multi-step drags instead of being dropped while a swipe is in flight. Fast scrolling no longer loses inputs.

## Launcher navigation fixed for everyone

- Fixed the "works for me, not for them" launcher bug: the Rokid firmware parks accessibility focus on an invisible 1x1 system window around screen off, freezing launcher navigation and the selected-app label for users with short screen timeouts. The app now detects that state and resolves the real launcher window.
- Ring input wakes the glasses display when it is asleep, and the waking gesture is swallowed instead of navigating or activating blindly on a dark screen.

## New gestures and Launch app action

- Four new mappable gestures: 1 tap + swipe up/down and 2 taps + swipe up/down.
- New `Launch app` action for any mappable gesture: pick any installed app from an on-glasses picker and open it directly from the ring, without scrolling the launcher.

## Phone companion (v0.2.8)

- The main screen now shows a persistent `Watchdog` status line so you can confirm the glasses are protected against firmware force-stops, with copy explaining that running the companion once is required to enable the watchdog.
- Fixed self-arm provisioning aborting on a `chmod` that emulated storage rejects; the loopback ADB key and self-arm watchdog script now install completely.

## Reminder: run the phone companion at least once

Rokid RG firmware 1.21.009 force-stops third-party apps and strips their Accessibility service when a temple leg is folded. Arming the bridge from `R08 Companion` once installs the accessibility watchdog that recovers ring control automatically. See the README section "Run the phone companion at least once".

APKs are debug-signed, as with previous releases.

**Full changelog**: [CHANGELOG.md](https://github.com/Anezium/R08-Access-Bridge/blob/main/CHANGELOG.md)
