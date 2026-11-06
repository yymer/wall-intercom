#!/usr/bin/env python3
"""
Tests the locked-down kiosk mode from start to end on a release build: turning it on, what it
blocks, updating the app, and getting out again with "Stop being device owner".

    test/device_owner_test.py --avd <name of an Android emulator>

It builds the APK and starts its own emulator, in read-only mode so that nothing is kept. Use an
English emulator image with no accounts on it. Needs keystore.properties, like any release build.

This test turns on device owner mode, which can't be undone from outside the app, so it refuses to
run on anything but an emulator it started itself. Exits with 0 only if every check passed.
"""
import argparse, os, re, subprocess, sys, time, xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Android/Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb")
EMULATOR = os.path.join(SDK, "emulator", "emulator")
APK = os.path.join(REPO, "build", "wall-intercom-test.apk")
PKG = "se.graw.wallintercom"
ADMIN = f"{PKG}/.AdminReceiver"
BUTTON = "Stop being device owner"
PORT = 5560
SERIAL = f"emulator-{PORT}"
fails = []


def run(*a):
    r = subprocess.run([ADB, "-s", SERIAL, *a], capture_output=True, text=True)
    return (r.stdout + r.stderr).strip()


def sh(*a):
    return run("shell", *a)


def check(name, passed, detail=""):
    print(f"{'PASS' if passed else 'FAIL'}  {name}" + (f"  [{str(detail)[:150]}]" if detail != "" and not passed else ""), flush=True)
    if not passed:
        fails.append(name)


def resumed():
    m = re.findall(r"mResumedActivity: ActivityRecord\{\S+ \S+ (\S+) ", sh("dumpsys", "activity", "activities"))
    return m[0] if m else ""


def focus():
    return "".join(l.strip() for l in sh("dumpsys", "window").splitlines() if "mCurrentFocus" in l)


def pinned():
    return "mLockTaskModeState=LOCKED" in sh("dumpsys", "activity", "activities")


def is_owner():
    parts = sh("dumpsys", "device_policy").split("Device Owner")
    return len(parts) > 1 and PKG in parts[1][:300]


def lock_screen_disabled():
    return sh("locksettings", "get-disabled").strip() == "true"


def nodes():
    sh("uiautomator", "dump", "/sdcard/ui.xml")
    xml = subprocess.run([ADB, "-s", SERIAL, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
    try:
        return list(ET.fromstring(xml).iter("node"))
    except ET.ParseError:
        return []


def texts():
    return [n.get("text") for n in nodes() if n.get("text")]


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(label, tries=10, button=False):
    """Taps the text. With button=True, only a button in a dialog, which may have the same text."""
    for _ in range(tries):
        for n in nodes():
            is_button = "android:id/button" in (n.get("resource-id") or "")
            if (n.get("text") or "").lower() == label.lower() and (is_button or not button):
                x, y = center(n)
                sh("input", "tap", str(x), str(y))
                return True
        time.sleep(0.7)
    print(f"      couldn't find {label!r} in {texts()[:12]}", flush=True)
    return False


def size():
    return tuple(map(int, re.findall(r"(\d+)x(\d+)", sh("wm", "size"))[-1]))


def key(k, wait=2.5):
    sh("input", "keyevent", k)
    time.sleep(wait)


def open_app(wait=4):
    sh("am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(wait)


def open_advanced_settings():
    w, h = size()
    gear = []
    for _ in range(4):
        gear = [n for n in nodes() if "⚙" in (n.get("text") or "")]
        if gear:
            break
        sh("input", "tap", str(w // 2), str(h // 3))
        time.sleep(1.5)
    if not gear:
        raise RuntimeError(f"no settings gear: {resumed()} {texts()[:8]}")
    x, y = center(gear[0])
    sh("input", "swipe", str(x), str(y), str(x), str(y), "1200")
    time.sleep(2.5)
    for _ in range(14):
        if "Advanced settings" in texts():
            break
        sh("input", "swipe", str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 4), "400")
        time.sleep(0.7)
    if not tap("Advanced settings"):
        raise RuntimeError("no Advanced settings")
    time.sleep(1.5)


def leave_settings():
    for _ in range(4):
        if "SettingsActivity" not in resumed():
            return
        key("KEYCODE_BACK", 1.5)


def status_bar_opens():
    """Swipes from the top edge a few times, as a person would, and sees if the status bar panel opens."""
    w, h = size()
    for _ in range(6):
        sh("input", "swipe", str(w // 2), "1", str(w // 2), str(h * 2 // 3), "500")
        time.sleep(1.5)
        if "StatusBar" in focus() or "NotificationShade" in focus():
            key("KEYCODE_BACK", 1.5)
            return True
    return False


def test():
    props = sh("getprop", "ro.kernel.qemu")
    if props.strip() != "1":
        sys.exit(f"{SERIAL} isn't an emulator. Stopping before touching it.")

    accounts = re.findall(r"Accounts: (\d+)", sh("dumpsys", "account"))
    check("the emulator has no accounts", accounts[:1] == ["0"], accounts)
    run("uninstall", PKG)  # the emulator's image may hold a copy signed with another key
    out = run("install", "-g", APK)
    if "Success" not in out:
        sys.exit(f"Couldn't install the release build, so there's nothing to test: {out}")
    package = sh("dumpsys", "package", PKG)
    check("it's a release build, not debuggable", "DEBUGGABLE" not in "".join(re.findall(r"pkgFlags=\[.*?\]", package)))
    launcher = sh("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                  "-c", "android.intent.category.HOME").splitlines()[-1].split("/")[0]

    print("\n-- A normal app, before device owner", flush=True)
    open_app()
    open_advanced_settings()
    check("the button is hidden", BUTTON not in texts())
    leave_settings()
    check("the status bar opens", status_bar_opens())

    print("\n-- Turning on device owner", flush=True)
    out = sh("dpm", "set-device-owner", ADMIN)
    check("set-device-owner succeeds", "Success" in out, out)
    check("Android reports it as device owner", is_owner())
    key("KEYCODE_HOME", 2)
    open_app()
    check("the app pins itself", pinned())
    check("the lock screen is disabled", lock_screen_disabled())
    check("the status bar is blocked", not status_bar_opens())
    key("KEYCODE_HOME", 3)
    check("Home doesn't leave the app", PKG in resumed(), resumed())
    key("KEYCODE_BACK", 3)
    check("Back doesn't leave the app", PKG in resumed(), resumed())
    sh("am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(3)
    check("other apps can't open", PKG in resumed(), resumed())
    out = run("uninstall", PKG)
    check("adb uninstall is refused", "Success" not in out, out)
    out = sh("dpm", "remove-active-admin", ADMIN)
    check("dpm remove-active-admin is refused", "Success" not in out, out)
    check("it's still device owner after those attempts", is_owner())

    print("\n-- Updating over adb while it's device owner", flush=True)
    out = run("install", "-r", APK).splitlines()[-1]
    check("adb install -r succeeds", "Success" in out, out)
    check("it's still device owner after the update", is_owner())
    time.sleep(3)
    print(f"      after the update, without opening it: {resumed() or 'nothing on screen'}, pinned={pinned()}", flush=True)
    open_app()
    check("it's pinned again once opened", pinned())

    print("\n-- Open Android settings, the temporary way out", flush=True)
    open_advanced_settings()
    tap("Open Android settings")
    time.sleep(3)
    check("Android's settings open", "settings" in resumed().lower() and PKG not in resumed(), resumed())
    check("the app is unpinned meanwhile", not pinned())
    open_app()
    check("the main screen pins itself again", pinned())

    print("\n-- The button", flush=True)
    open_advanced_settings()
    check("the button shows", BUTTON in texts(), texts()[:8])
    tap(BUTTON)
    time.sleep(1.5)
    check("it asks first", any("kiosk mode" in t for t in texts()), texts()[:3])
    tap("Cancel", button=True)
    time.sleep(1.5)
    check("Cancel keeps device owner", is_owner())
    check("Cancel keeps it pinned", pinned())
    tap(BUTTON)
    time.sleep(1.5)
    tap(BUTTON, button=True)
    time.sleep(3)
    check("it's no longer device owner", not is_owner())
    check("it's no longer pinned", not pinned())
    check("the lock screen is enabled again", not lock_screen_disabled())
    check("the settings stay open", "SettingsActivity" in resumed(), resumed())
    check("the button is gone", BUTTON not in texts(), texts()[:8])
    leave_settings()
    time.sleep(1.5)
    check("the main screen doesn't pin itself again", not pinned())
    check("the status bar opens again", status_bar_opens())
    key("KEYCODE_HOME", 3)
    check("Home leaves the app again", launcher in resumed(), resumed())
    open_app(3)
    check("the app still opens", PKG in resumed(), resumed())
    check("and stays unpinned", not pinned())
    crashes = sh("logcat", "-d", "-b", "crash")
    check("no crashes", PKG not in crashes, crashes[-200:] if PKG in crashes else "")
    out = run("uninstall", PKG)
    check("the app can be uninstalled", "Success" in out, out)

    print("\n-- Doing it all again", flush=True)
    run("install", "-g", APK)
    out = sh("dpm", "set-device-owner", ADMIN)
    check("device owner can be turned on again", "Success" in out, out)


def main():
    parser = argparse.ArgumentParser(description="Tests device owner mode on a release build, on an emulator it starts itself.")
    parser.add_argument("--avd", required=True, help="the emulator to start (see: emulator -list-avds)")
    parser.add_argument("--no-build", action="store_true", help="use the APK that's already in build/")
    args = parser.parse_args()

    if not args.no_build:
        subprocess.run([os.path.join(REPO, "build.sh"), "release-test"], check=True, stdout=subprocess.DEVNULL)
    if SERIAL in subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout:
        sys.exit(f"{SERIAL} is already running. This test only uses an emulator it starts itself.")

    print(f"Starting {args.avd} on {SERIAL}…", flush=True)
    emulator = subprocess.Popen([EMULATOR, "-avd", args.avd, "-read-only", "-no-window", "-no-audio", "-no-boot-anim",
                                 "-no-snapshot", "-port", str(PORT)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        started = time.time()
        while sh("getprop", "sys.boot_completed").strip() != "1":
            if time.time() - started > 400:
                sys.exit("The emulator didn't start.")
            time.sleep(3)
        time.sleep(8)
        sh("input", "keyevent", "KEYCODE_WAKEUP")
        sh("wm", "dismiss-keyguard")
        test()
    finally:
        run("emu", "kill")
        emulator.wait(timeout=60)

    print(f"\n{len(fails)} failed" + (": " + "; ".join(fails) if fails else ""))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
