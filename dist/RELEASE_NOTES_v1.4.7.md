## v1.4.7

Stable release promoted from the v1.4.7 preview, plus NewPipe compatibility.

### App compatibility

- Adds NewPipe Rokid build compatibility.
- When `com.anezium.rokid.newpipe` is active, ring next / previous / select commands are forwarded to NewPipe's own single-axis navigator instead of using the generic accessibility walker.
- This keeps NewPipe list scrolling, category headers, search keyboard behavior, and player action rail navigation under NewPipe's native focus model.

### Launcher focus

- Promotes the v1.4.7 preview launcher focus tuning to stable.
- Limits queued launcher carousel steps more aggressively while a swipe is already in flight.
- Delays center tap and long-press actions until queued launcher swipes settle, reducing cases where the ring opens a different app than the visually centered one.

### Install note

For first-time R08 ring setup:

1. Update the ring firmware once with the official QRing / R08 Ring app.
2. Unbind or disconnect the ring from the phone app.
3. If pairing still behaves oddly, forget the ring from phone Bluetooth or temporarily turn phone Bluetooth off.
4. Pair/reconnect the ring from R08 Access Bridge on the glasses.

### APKs

- `R08-Access-Bridge-v1.4.7.apk` goes on the Rokid glasses.
- `R08-Companion-v0.2.5.apk` goes on the Android phone for the Hi Rokid shortcut bridge.
