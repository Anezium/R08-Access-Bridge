## v1.4.6

Small launcher reliability hotfix after v1.4.5.

### Launcher focus

- Launcher carousel navigation is now less timing- and layout-sensitive.
- Swipe distance is derived from the visible app spacing instead of relying on a fixed estimate.
- Center activation / long-press waits for queued launcher swipes to settle before firing.

This should reduce cases where the visible centered launcher app and the app opened by the ring get out of sync.

### Install note

For first-time R08 ring setup:

1. Update the ring firmware once with the official QRing / R08 Ring app.
2. Unbind or disconnect the ring from the phone app.
3. If pairing still behaves oddly, forget the ring from phone Bluetooth or temporarily turn phone Bluetooth off.
4. Pair/reconnect the ring from R08 Access Bridge on the glasses.

### APKs

- `R08-Access-Bridge-v1.4.6.apk` goes on the Rokid glasses.
- `R08-Companion-v0.2.5.apk` goes on the Android phone for the Hi Rokid shortcut bridge.
