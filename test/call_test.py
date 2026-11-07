#!/usr/bin/env python3
"""
Tests calls between two tablets: that the answering tablet shows video, also after its main screen
has been opened twice (as the Home screen and as the normal app, which once left every later call
without video), and that other sound pauses or is muted during a call.

    test/call_test.py --avd <name of an Android emulator>

It builds the debug APK and starts two copies of the emulator, in read-only mode so that nothing is
kept: "Hallway" calls "Kitchen". The emulator needs an emulated front camera (the default) and an
English image. Exits with 0 only if every check passed.

Two emulators can set up a call but not send each other video (both have the same address inside),
so the checks are on the wall tablet's view of its own camera. It goes through the same path to the
screen as the other tablet's video, and the bug this tests for stopped both.
"""
import argparse, json, os, re, socket, subprocess, sys, time, xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Android/Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb")
EMULATOR = os.path.join(SDK, "emulator", "emulator")
APK = os.path.join(REPO, "wall-intercom.apk")
PKG = "se.graw.wallintercom"
# Emulators can't reach each other directly, only the computer they run on (10.0.2.2 from inside).
# So each tablet gets its own app port, forwarded from the same port on the computer.
CALLER = {"serial": "emulator-5570", "name": "Hallway", "port": 47576}
WALL = {"serial": "emulator-5572", "name": "Kitchen", "port": 47575}
ANNOUNCE_PORT = 47998  # on the computer, forwarded to the caller's discovery port
fails = []


def adb(dev, *a):
    r = subprocess.run([ADB, "-s", dev["serial"], *a], capture_output=True, text=True)
    return (r.stdout + r.stderr).strip()


def sh(dev, *a):
    return adb(dev, "shell", *a)


def check(name, passed, detail=""):
    print(f"{'PASS' if passed else 'FAIL'}  {name}" + (f"  [{str(detail)[:150]}]" if detail != "" and not passed else ""), flush=True)
    if not passed:
        fails.append(name)


def resumed(dev):
    m = re.findall(r"mResumedActivity: ActivityRecord\{\S+ \S+ (\S+) ", sh(dev, "dumpsys", "activity", "activities"))
    return m[0] if m else ""


def nodes(dev):
    sh(dev, "uiautomator", "dump", "/sdcard/ui.xml")
    xml = subprocess.run([ADB, "-s", dev["serial"], "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
    try:
        return list(ET.fromstring(xml).iter("node"))
    except ET.ParseError:
        return []


def texts(dev):
    return [n.get("text") for n in nodes(dev) if n.get("text")]


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(dev, label, tries=12):
    for _ in range(tries):
        for n in nodes(dev):
            if (n.get("text") or "").lower() == label.lower():
                x, y = center(n)
                sh(dev, "input", "tap", str(x), str(y))
                return True
        time.sleep(0.7)
    print(f"      couldn't find {label!r} on {dev['name']}: {texts(dev)[:12]}", flush=True)
    return False


def wait_for(dev, pattern, seconds=15):
    started = time.time()
    while time.time() - started < seconds:
        if any(re.search(pattern, t) for t in texts(dev)):
            return True
        time.sleep(0.7)
    return False


def size(dev):
    return tuple(map(int, re.findall(r"(\d+)x(\d+)", sh(dev, "wm", "size"))[-1]))


def key(dev, k, wait=2.5):
    sh(dev, "input", "keyevent", k)
    time.sleep(wait)


def show_buttons(dev):
    """Taps the photo to show the buttons, unless they're showing already (a tap would hide them)."""
    if not any("⚙" in (n.get("text") or "") for n in nodes(dev)):
        w, h = size(dev)
        sh(dev, "input", "tap", str(w // 2), str(h // 3))
        time.sleep(1.5)


def row_tap(dev, label, button):
    """Taps a button on the same row as a tablet's name."""
    for _ in range(6):
        ns = nodes(dev)
        row = [n for n in ns if label in (n.get("text") or "")]
        buttons = [n for n in ns if (n.get("text") or "").lower() == button.lower()]
        if row and buttons:
            y = center(row[0])[1]
            x, yy = center(min(buttons, key=lambda n: abs(center(n)[1] - y)))
            sh(dev, "input", "tap", str(x), str(yy))
            return True
        show_buttons(dev)
    print(f"      no {button!r} for {label!r} on {dev['name']}: {texts(dev)[:12]}", flush=True)
    return False


def open_advanced_settings(dev):
    show_buttons(dev)
    gear = [n for n in nodes(dev) if "⚙" in (n.get("text") or "")]
    if not gear:
        raise RuntimeError(f"no settings gear on {dev['name']}: {resumed(dev)} {texts(dev)[:8]}")
    x, y = center(gear[0])
    sh(dev, "input", "swipe", str(x), str(y), str(x), str(y), "1200")
    time.sleep(2.5)
    w, h = size(dev)
    for _ in range(14):
        if "Advanced settings" in texts(dev):
            break
        sh(dev, "input", "swipe", str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 4), "400")
        time.sleep(0.7)
    if not tap(dev, "Advanced settings"):
        raise RuntimeError("no Advanced settings")
    time.sleep(1.5)


def leave_settings(dev):
    for _ in range(4):
        if "SettingsActivity" not in resumed(dev):
            return
        key(dev, "KEYCODE_BACK", 1.5)


def frames(dev):
    """The most frames each video view got in one of WebRTC's 4 second reports since the log was cleared."""
    log = subprocess.run([ADB, "-s", dev["serial"], "logcat", "-d", "-s", "org.webrtc.Logging"], capture_output=True, text=True).stdout
    got = {"remoteView": 0, "localView": 0}
    for view, n in re.findall(r"EglRenderer: (remoteView|localView)Duration: \d+ ms\. Frames received: (\d+)", log):
        got[view] = max(got[view], int(n))
    return got


def has_audio_focus(dev):
    """Whether the app holds audio focus, which asks other apps playing sound to pause."""
    stack = sh(dev, "dumpsys", "audio").split("Audio Focus stack entries")
    return len(stack) > 1 and f"pack: {PKG}" in stack[1].split("\n\n")[0]


def media_muted(dev):
    """Whether media sound is muted, which silences players that ignore audio focus."""
    m = re.search(r"- STREAM_MUSIC:\s*\n\s*Muted: (\w+)", sh(dev, "dumpsys", "audio"))
    return bool(m) and m[1] == "true"


def call(label):
    """The caller calls the wall tablet, which answers. Checks that its own camera reaches the screen,
    and that it pauses other sound during the call."""
    sh(WALL, "logcat", "-c")
    sh(CALLER, "logcat", "-c")
    sh(CALLER, "input", "keyevent", "KEYCODE_WAKEUP")
    row_tap(CALLER, WALL["name"], "Call")
    check(f"{label}: the wall tablet rings", wait_for(WALL, r"^Answer$"), texts(WALL)[:6])
    tap(WALL, "Answer")
    time.sleep(13)  # at least two of WebRTC's 4 second reports
    got = frames(WALL)
    print(f"      frames on the wall tablet: {got}, on the caller: {frames(CALLER)}", flush=True)
    check(f"{label}: the wall tablet shows its camera on screen", got["localView"] > 0, got)
    check(f"{label}: other sound is paused on both", has_audio_focus(WALL) and has_audio_focus(CALLER))
    check(f"{label}: media sound is muted on both", media_muted(WALL) and media_muted(CALLER))
    tap(WALL, "End call")
    time.sleep(4)
    check(f"{label}: other sound may play again after the call", not has_audio_focus(WALL) and not has_audio_focus(CALLER))
    check(f"{label}: media sound is unmuted after the call", not media_muted(WALL) and not media_muted(CALLER))


def announce(dev):
    """Tells the caller about another tablet, as its discovery broadcast would on a real network."""
    ident = re.search(r'name="deviceId">([^<]+)<', sh(dev, "run-as", PKG, "cat", "shared_prefs/settings.xml"))[1]
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.sendto(json.dumps({"t": "here", "id": ident, "name": dev["name"], "port": dev["port"], "v": 2}).encode(),
             ("127.0.0.1", ANNOUNCE_PORT))
    u.close()


def set_up(dev):
    if sh(dev, "getprop", "ro.kernel.qemu").strip() != "1":
        sys.exit(f"{dev['serial']} isn't an emulator. Stopping before touching it.")
    for ime in sh(dev, "ime", "list", "-s").split():
        sh(dev, "ime", "disable", ime)  # the on-screen keyboard covers dialog buttons
    adb(dev, "uninstall", "app.wallintercom")  # an old copy on the emulator's image takes the same port
    adb(dev, "uninstall", PKG)
    out = adb(dev, "install", "-g", APK)
    if "Success" not in out:
        sys.exit(f"Couldn't install the app on {dev['name']}: {out}")
    prefs = (f"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"myName\">{dev['name']}</string>"
             f"<int name=\"port\" value=\"{dev['port']}\" /></map>")
    sh(dev, "run-as", PKG, "mkdir", "-p", "shared_prefs")
    subprocess.run([ADB, "-s", dev["serial"], "shell", "run-as", PKG, "sh", "-c", "'cat > shared_prefs/settings.xml'"],
                   input=prefs, text=True, capture_output=True)
    sh(dev, "input", "keyevent", "KEYCODE_WAKEUP")
    sh(dev, "wm", "dismiss-keyguard")
    sh(dev, "am", "start", "-n", f"{PKG}/.MainActivity")


def test():
    adb(WALL, "emu", "redir", "add", f"tcp:{WALL['port']}:{WALL['port']}")
    adb(CALLER, "emu", "redir", "add", f"tcp:{CALLER['port']}:{CALLER['port']}")
    adb(CALLER, "emu", "redir", "add", f"udp:{ANNOUNCE_PORT}:{CALLER['port']}")
    for dev in (CALLER, WALL):
        set_up(dev)
    time.sleep(8)

    print("\n-- Pairing", flush=True)
    for _ in range(3):
        announce(WALL)
        time.sleep(1)
    show_buttons(CALLER)
    announce(WALL)
    time.sleep(2)
    row_tap(CALLER, WALL["name"], "Bell")
    tap(CALLER, "Pair")
    check("the wall tablet is asked to pair", wait_for(WALL, "wants to pair"), texts(WALL)[:4])
    tap(WALL, "Accept")
    time.sleep(3)
    tap(CALLER, "OK", tries=2)  # if it's still showing
    time.sleep(20)  # until they've pinged each other and show as online

    print("\n-- A call", flush=True)
    call("first call")

    print("\n-- The main screen opened twice: as the Home screen, then as the app again", flush=True)
    open_advanced_settings(WALL)
    tap(WALL, "Use as home screen")  # turns it on and opens Android's list of home apps
    time.sleep(3)
    out = sh(WALL, "cmd", "package", "set-home-activity", f"{PKG}/.HomeScreen")  # choosing it in that list
    check("it can be made the home app", "Success" in out, out)
    key(WALL, "KEYCODE_HOME", 4)
    check("Home opens it as the Home screen", resumed(WALL).endswith("/.HomeScreen"), resumed(WALL))
    open_advanced_settings(WALL)
    tap(WALL, "Use as home screen")  # off again
    time.sleep(3)
    leave_settings(WALL)
    time.sleep(3)
    check("back to the normal app", resumed(WALL).endswith("/.MainActivity"), resumed(WALL))
    call("call after that")

    print("\n-- After restarting the app", flush=True)
    sh(WALL, "am", "force-stop", PKG)
    sh(WALL, "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(8)
    call("call after a restart")

    for dev in (CALLER, WALL):
        crashes = sh(dev, "logcat", "-d", "-b", "crash")
        check(f"no crashes on {dev['name']}", PKG not in crashes, crashes[-200:] if PKG in crashes else "")


def main():
    parser = argparse.ArgumentParser(description="Tests calls on two copies of an emulator it starts itself.")
    parser.add_argument("--avd", required=True, help="the emulator to start (see: emulator -list-avds)")
    parser.add_argument("--no-build", action="store_true", help="use the wall-intercom.apk that's already built")
    args = parser.parse_args()

    if not args.no_build:
        subprocess.run([os.path.join(REPO, "build.sh"), "-Pemulator"], check=True, stdout=subprocess.DEVNULL)
    running = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    for dev in (CALLER, WALL):
        if dev["serial"] in running:
            sys.exit(f"{dev['serial']} is already running. This test only uses emulators it starts itself.")

    emulators = []
    try:
        for dev in (CALLER, WALL):
            print(f"Starting {args.avd} on {dev['serial']} as {dev['name']}…", flush=True)
            emulators.append(subprocess.Popen(
                [EMULATOR, "-avd", args.avd, "-read-only", "-no-window", "-no-audio", "-no-boot-anim",
                 "-no-snapshot", "-port", dev["serial"].split("-")[1]],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        started = time.time()
        for dev in (CALLER, WALL):
            while sh(dev, "getprop", "sys.boot_completed").strip() != "1":
                if time.time() - started > 500:
                    sys.exit("The emulators didn't start.")
                time.sleep(3)
        time.sleep(8)
        test()
    finally:
        for dev in (CALLER, WALL):
            adb(dev, "emu", "kill")
        for e in emulators:
            e.wait(timeout=60)

    print(f"\n{len(fails)} failed" + (": " + "; ".join(fails) if fails else ""))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
