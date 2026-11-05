#!/usr/bin/env bash
# Builds the APK. Output: app/build/outputs/apk/debug/app-debug.apk
set -e
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/.local/opt/jdk-21.0.13+9"
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug "$@"
cp app/build/outputs/apk/debug/app-debug.apk wall-intercom.apk
echo "Built: $(pwd)/wall-intercom.apk"
