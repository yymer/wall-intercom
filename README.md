# Wall Intercom

Turn two old Android tablets into wall-mounted **photo frames that double as a video intercom**
between two rooms. No server, no cloud, no accounts: the tablets talk directly to each other
over your home Wi-Fi.

- Full-screen, cross-fading **slideshow** from a folder on the tablet.
- Touch the screen and two small buttons appear: **Call** and **Bell**. They stay for 25 s.
- **Call** starts a video call. The other tablet **answers automatically**: it wakes up,
  plays a short chime (so the room knows the camera is on) and the call starts.
  Either side can press **End call**.
- **Bell** rings a ding-dong on the other tablet and shows "🔔 Bell from …" without turning on
  any camera, so the other room can call back when it suits them.
- If the Wi-Fi hiccups during a call it shows **Reconnecting…** and recovers by itself
  (for up to 30 s) instead of hanging up.
- Calls hang up automatically after 15 minutes (configurable), so a forgotten call doesn't
  leave a camera on all night.
- Dims at night, keeps itself running, survives reboots (it acts as the home screen).
- English and Swedish, following each tablet's language setting.

Tested on two Lenovo Tab M10 (TB-X505F) running Android 10.

| Touch the photo: Call and Bell | Bell ringing on the other tablet | The other tablet got the bell |
|---|---|---|
| ![Call and Bell buttons over the photo](docs/buttons.jpg) | ![Bell button turns gold while ringing](docs/bell-ringing.jpg) | ![Bell from Bedroom banner](docs/bell-received.jpg) |

*(Screenshots from the emulator with a generated test picture.)*

## Requirements

- Two Android tablets with **Android 10 or newer** and a front camera.
- Both on the **same network**. Give each a fixed IP address (a DHCP reservation in your router).
- A computer with `adb` to install the app (and JDK 17/21 + Android SDK if you build it yourself).

## Build

```bash
./build.sh             # -> wall-intercom.apk (ARM, for real tablets)
./build.sh -Pemulator  # also includes x86_64, for testing in the Android emulator
```

`build.sh` uses `JAVA_HOME` and `ANDROID_HOME` if set (JDK 17 or 21; Android SDK with
platform 35). The APK is signed with your local debug key, which is fine for sideloading.
Keep using the same machine (same `~/.android/debug.keystore`) to build updates, or Android
will refuse to install them over the old version.

## Set up each tablet

1. Enable *Developer options → USB debugging* on the tablet, plug it in, then:
   ```bash
   adb install -r -g wall-intercom.apk
   ```
   (`-g` grants camera and microphone up front.)
2. Copy your photos into a folder on the tablet, e.g. `Pictures/Wall`.
3. Open **Wall Intercom**. Tap the screen, then **press and hold ⚙** (top right) for settings:
   - *Name of this room*, e.g. `Kitchen`. The two tablets must have different names.
   - *Other tablet's IP address*: shown at the top of the other tablet's settings.
   - *Choose photo folder…* → pick the folder → *Use this folder* → *Allow*.
   - Press *Allow waking up over other apps* and *Don't let Android put this app to sleep*.
   - **Save**.
4. Press the tablet's Home button and choose **Wall Intercom → Always**. It is now the home
   screen, so it comes back by itself after a reboot or if someone leaves it.
5. Android settings → *Security → Screen lock* → **None**, so calls aren't stuck behind the
   lock screen.
6. If the tablet has **Dolby Atmos** or similar sound "enhancement", turn it off
   (Settings → Sound). It confuses echo cancellation and causes echo/noise during calls.

The port (default 47555) must be the same on both tablets.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Seconds per picture | 20 | Photos are shuffled; the folder is re-scanned every round |
| Fill the whole screen | on | Crops pictures slightly instead of showing black bars |
| Brightness while showing pictures | 40% | Full brightness when touched or in a call |
| Night hours / night brightness | 22–07, 2% | 0% turns the backlight off; a touch, call or bell wakes it |
| Call volume | 80% | |
| Bell volume | 80% | Plays on Android's *alarm* volume channel |
| Hang up automatically after | 15 min | 0 = never |
| HD video | off | 640×480 by default, which is safest for old tablets |
| Built-in echo cancellation | off | Use the tablet's own instead of WebRTC's; try it if you get echo |

## Security and privacy

- Calls are **answered automatically**. Only use this where everyone in both rooms is
  fine with that.
- Each tablet only accepts connections from the **IP address configured as the other
  tablet**; everything else is ignored. There is no other authentication, so use it on a
  network you trust (your home Wi-Fi, not a public one).
- Audio and video are encrypted by WebRTC (DTLS-SRTP) and go directly between the tablets.
  Nothing leaves your network.

## Optional: full kiosk lock-down

To make it impossible to leave the app (no status bar, no recent apps), make it
*device owner*. This only works on a tablet with **no Google or other accounts** added
(remove them first, or do it right after a factory reset):

```bash
adb shell dpm set-device-owner app.wallintercom/.AdminReceiver
```

The app then pins itself on screen and disables the lock screen. The *Open Android settings*
button in the app's settings temporarily unpins it. **Note:** undoing device owner (and then
uninstalling the app) requires a factory reset, so try the app without this step first.

## Troubleshooting

See what happened on a tablet, including why a call ended:

```bash
adb logcat -d | grep CallManager
```

- **"Not reachable right now"**: check the IP address and port on both tablets, and that both
  are on the same network. Some routers have "client/AP isolation" turned on for Wi-Fi, which
  blocks devices from talking to each other.
- **Echo or noise**: turn off Dolby/sound enhancements, lower *Call volume*, or try the
  *built-in echo cancellation* setting.
- **App stops answering after a while**: press *Don't let Android put this app to sleep* in
  settings. Some manufacturers have extra battery savers in their own settings apps.

## How it works

- `IntercomService`: a foreground service holding CPU and Wi-Fi locks so the tablet is always
  reachable. It wakes the screen on an incoming call or bell.
- `CallManager`: listens on TCP 47555. A call connects to the other tablet and exchanges
  one-line JSON messages (`hello` → `ok`, WebRTC `offer`/`answer`/`ice`, `bye`).
  `ping`/`pong` every 20 s drives the "Online" indicator, and `bell` rings the other tablet.
  Audio/video is WebRTC, peer-to-peer on the LAN (no STUN/TURN). On a network hiccup the
  caller re-opens the socket (`resume`) and does an ICE restart; the call only ends if it
  can't recover within 30 s.
- `MainActivity`: slideshow, touch buttons, call screen, brightness.
- `SettingsActivity`: the settings form.
- Translations are in `app/src/main/res/values*/strings.xml`. To add a language, copy
  `values/strings.xml` to `values-xx/` and translate it.

## License

[MIT](LICENSE)
