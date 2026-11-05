# Wall Intercom

A photo-frame + video-intercom app for two Android 10 tablets on the same Wi-Fi.
No server: the tablets talk directly to each other.

- Shows a full-screen, cross-fading slideshow from a folder on the tablet.
- Tap the screen → big **Call &lt;other room&gt;** button.
- The other tablet **answers automatically**: wakes the screen, plays a short chime,
  and the call starts. Either side can press **End call**.
- Calls hang up automatically after 15 minutes (configurable), so a forgotten call
  doesn't leave a camera on all night.

## Build

```bash
./build.sh            # -> wall-intercom.apk (ARM, for the tablets)
./build.sh -Pemulator # also includes x86_64 for testing in the emulator
```

Uses JDK 21 in `~/.local/opt` and the Android SDK in `~/Android/Sdk`.

## Set up each tablet

1. **Give each tablet a fixed IP address** in your router (DHCP reservation).
2. Enable *Developer options → USB debugging* on the tablet, plug it in, then:
   ```bash
   adb install -r -g wall-intercom.apk
   ```
   (`-g` grants camera + microphone up front.)
3. Copy your photos into a folder on the tablet, e.g. `Pictures/Wall`.
4. Open **Wall Intercom**. Tap the screen, then **press and hold ⚙** (top right).
   - *Name of this room*: e.g. `Kitchen` (the two tablets must have different names)
   - *Other tablet's IP address*: the IP shown at the top of the other tablet's settings
   - *Choose photo folder…* → pick the folder → *Use this folder* → *Allow*
   - Press *Allow waking up over other apps* and *Don't let Android put this app to sleep*
   - **Save**
5. Press the tablet's Home button and choose **Wall Intercom → Always**. It is now the home
   screen, so it comes back by itself after a reboot or if someone leaves it.
6. Android settings → *Security → Screen lock* → **None**, so incoming calls aren't stuck
   behind the lock screen.

The port (default 47555) must be the same on both tablets.

## Optional: full kiosk lock-down

If you want visitors/kids to be unable to leave the app at all (no status bar, no recent
apps), make it *device owner*. This only works on a tablet **with no Google/other accounts**
added (remove them first, or do it right after a factory reset):

```bash
adb shell dpm set-device-owner app.wallintercom/.AdminReceiver
```

The app then pins itself on screen and disables the lock screen. The *Open Android settings*
button in the app's settings temporarily unpins it. **Note:** undoing device owner (and
uninstalling the app afterwards) requires a factory reset, so try the app without this step first.

## Settings worth knowing

| Setting | Default | Notes |
|---|---|---|
| Brightness while showing pictures | 40% | Full brightness when touched / in a call |
| Night hours / night brightness | 22–07, 2% | 0% turns the backlight off; a touch or call wakes it |
| Seconds per picture | 20 | Photos are shuffled; folder is re-scanned every round |
| HD video | off | 640×480 by default — safest for old tablets |
| Built-in echo cancellation | off | Try it if you get echo with the default |

## How it works

- `IntercomService` – foreground service holding CPU + Wi-Fi locks so the tablet is
  always reachable; wakes the screen on an incoming call.
- `CallManager` – listens on TCP 47555. Calling = connecting to the other tablet and
  exchanging one-line JSON messages (`hello` → `ok`, WebRTC `offer`/`answer`/`ice`, `bye`).
  A `ping`/`pong` every 20 s drives the "Online" indicator. Audio/video is WebRTC,
  peer-to-peer on the LAN (no STUN/TURN).
- `MainActivity` – slideshow, touch overlay, call screen, brightness.
- `SettingsActivity` – the settings form.
