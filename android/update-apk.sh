#!/usr/bin/env bash
# Builds the app, stages the APK for the /apk download link, and syncs
# version.json to the APK's ACTUAL versionCode. Run from repo root:
#   ./android/update-apk.sh
# Prevents the v2.1.1 bug where /apk and /version.json went out of sync
# and the in-app updater looped on a refused install.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/android-tools/jdk-17.0.12+7}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-tools/sdk}"

GRADLE="${GRADLE:-$HOME/android-tools/gradle-8.7/bin/gradle}"
AAPT="$ANDROID_HOME/build-tools/34.0.0/aapt"

"$GRADLE" assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk apk/turkishtv.apk

badging="$($AAPT dump badging apk/turkishtv.apk | grep '^package:' | head -1)"
code="$(echo "$badging" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
name="$(echo "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
[ -n "$code" ] || { echo "could not read versionCode from APK"; exit 1; }
printf '{"versionCode": %s, "versionName": "%s"}\n' "$code" "$name" > apk/version.json
echo "staged: turkishtv.apk v$name (code $code); version.json updated to match"
