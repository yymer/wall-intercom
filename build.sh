#!/usr/bin/env bash
# Builds the APK.
#   ./build.sh                 debug build -> ./wall-intercom.apk (ARM only, real tablets)
#   ./build.sh -Pemulator      debug build that also includes x86_64, for the Android emulator
#   ./build.sh release         signed release -> ./wall-intercom-<version>.apk and .sha256
#                              (needs keystore.properties, see the README)
#
# Needs JDK 17 or 21 and the Android SDK (platform 35). Set JAVA_HOME / ANDROID_HOME,
# or it falls back to a few common install locations.
set -e
cd "$(dirname "$0")"

if [ -z "$JAVA_HOME" ]; then
    for d in "$HOME"/.local/opt/jdk-21* /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-17-openjdk*; do
        if [ -x "$d/bin/java" ]; then export JAVA_HOME="$d"; break; fi
    done
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"

if [ "$1" = "release" ]; then
    shift
    if [ ! -f keystore.properties ]; then
        echo "No keystore.properties: see \"Publishing a release\" in the README." >&2
        exit 1
    fi
    ./gradlew clean assembleRelease "$@"
    version=$(sed -n 's/^val appVersion = "\(.*\)"$/\1/p' app/build.gradle.kts)
    apk="wall-intercom-$version.apk"
    cp app/build/outputs/apk/release/app-release.apk "$apk"
    sha256sum "$apk" > "$apk.sha256"
    echo "Built: $(pwd)/$apk"
    cat "$apk.sha256"
else
    ./gradlew assembleDebug "$@"
    cp app/build/outputs/apk/debug/app-debug.apk wall-intercom.apk
    echo "Built: $(pwd)/wall-intercom.apk"
fi
