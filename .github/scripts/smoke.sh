#!/bin/bash
# Starts the app on the emulator and collects what happened. It never fails on a crash,
# because the logs are what the run is for.
PACKAGE=org.telegram.messenger.beta
OUT=smoke
WAIT_SECONDS=45

mkdir -p "$OUT"
adb install -r -t dist/cogram-x86_64-debug.apk
adb logcat -c
adb shell am start -n "$PACKAGE/org.telegram.ui.LaunchActivity"
sleep "$WAIT_SECONDS"

adb shell pidof "$PACKAGE" > "$OUT/pid.txt" || true
adb exec-out screencap -p > "$OUT/screen.png" || true
adb logcat -d -v threadtime > "$OUT/logcat.txt" || true
adb logcat -d -b crash -v threadtime > "$OUT/logcat-crash.txt" || true
adb shell dumpsys activity exit-info "$PACKAGE" > "$OUT/exit-info.txt" || true
adb shell dumpsys activity activities > "$OUT/activities.txt" || true
adb shell run-as "$PACKAGE" cat files/cogram_crash.txt > "$OUT/java-crash.txt" || true
adb shell uiautomator dump /sdcard/ui.xml || true
adb pull /sdcard/ui.xml "$OUT/ui.xml" || true
exit 0
