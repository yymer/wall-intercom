#!/usr/bin/env bash
# Builds the APK.
#   ./build.sh                 debug build -> ./wall-intercom.apk (ARM only, real tablets)
#   ./build.sh -Pemulator      debug build that also includes x86_64, for the Android emulator
#   ./build.sh release         signed release -> ./wall-intercom-<version>.apk and .sha256
#                              (needs keystore.properties next to this file)
#   ./build.sh release-test    signed release that also includes x86_64, for tests in the emulator
#                              -> build/wall-intercom-test.apk. Don't publish this one.
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

if [ "$1" = "release" ] || [ "$1" = "release-test" ]; then
    if [ ! -f keystore.properties ]; then
        echo "No keystore.properties, with storeFile, storePassword, keyAlias and keyPassword." >&2
        exit 1
    fi
fi

if [ "$1" = "release-test" ]; then
    shift
    ./gradlew assembleRelease -Pemulator "$@"
    mkdir -p build
    cp app/build/outputs/apk/release/app-release.apk build/wall-intercom-test.apk
    echo "Built: $(pwd)/build/wall-intercom-test.apk"
elif [ "$1" = "release" ]; then
    shift
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
