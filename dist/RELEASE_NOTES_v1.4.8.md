## v1.4.8

Phone companion + glasses self-arm recovery release for Rokid RG firmware 1.21.009.

### Accessibility watchdog

- Adds a shell watchdog packaged in the phone APK and launched on the glasses over ADB during arm/re-arm.
- The watchdog restores `enabled_accessibility_services`, clears the force-stopped package state by starting R08 Access Bridge, and returns the glasses to Home.
- This targets the Rokid AssistServer regression where folding a temple leg force-stops the foreground third-party app and removes its AccessibilityService.

### Glasses self-arm

- Adds hacha-style local ADB loopback recovery: the phone companion provisions the trusted ADB key, the watchdog script, and `persist.adb.tcp.port=5555`.
- After provisioning, opening R08 Access Bridge directly on the glasses connects to `127.0.0.1:5555`, repairs Accessibility, and restarts the watchdog even if the phone is not involved.
- Boot receiver also attempts the same self-arm path when Android delivers `BOOT_COMPLETED`.

### Phone companion

- On launch, the companion now auto-attempts re-arm when a previous arm endpoint or Hi Rokid authorization is available.
- Re-arm starts both the shortcut bridge and the Accessibility watchdog.
- Re-arm provisions the glasses app-open self-arm path automatically.
- Direct ADB fallback now waits for mDNS `_adb-tls-connect` results to settle so stale Wireless Debugging ports are not selected first.
- `Disable bridge` also stops the watchdog.

### Thanks

- Thanks to hacha for sharing the `rokid-r08-wake` loopback self-arm approach that made the app-open recovery path possible: https://github.com/hacha/rokid-r08-wake / https://x.com/hacha

### APKs

- `R08-Access-Bridge-v1.4.8-unsigned.apk` goes on the Rokid glasses.
- `R08-Companion-v0.2.7-unsigned.apk` goes on the Android phone for the Hi Rokid shortcut bridge, Accessibility watchdog, and self-arm provisioning.
