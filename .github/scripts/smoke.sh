#!/bin/bash
# Starts the app on the emulator, sends a message and collects what happened. It never fails
# on a crash, because the logs are what the run is for. Tap positions assume the 320x640 screen.
PACKAGE=org.telegram.messenger.beta
OUT=smoke
LOGIN_SECONDS=30
SIGN_IN_SECONDS=75
START_SECONDS=45
ECHO_SECONDS=8

mkdir -p "$OUT"
# Permission dialogs would swallow the taps below, so grant everything at install.
adb install -r -t -g dist/cogram-x86_64-debug.apk
adb logcat -c

# First run as a phone sees it: the launcher icon with no account leads to the login screen.
adb shell am start -n "$PACKAGE/org.telegram.messenger.DefaultIcon"
sleep "$LOGIN_SECONDS"
adb exec-out screencap -p > "$OUT/0-login.png" || true
adb shell pidof "$PACKAGE" > "$OUT/pid-login.txt" || true
adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" > "$OUT/top-login.txt" || true

# A failed sign-in starts the engine for real. This address cannot resolve, so nothing leaves the runner.
adb shell input text smoke@cogram.invalid
adb shell input keyevent 61
adb shell input text smoke-test
adb shell input keyevent 61
adb shell input keyevent 66
sleep "$SIGN_IN_SECONDS"
adb exec-out screencap -p > "$OUT/0-sign-in.png" || true
adb shell pidof "$PACKAGE" > "$OUT/pid-sign-in.txt" || true
adb shell uiautomator dump /sdcard/sign-in.xml > /dev/null || true
adb exec-out cat /sdcard/sign-in.xml > "$OUT/sign-in.xml" || true
adb logcat -d -v threadtime > "$OUT/logcat-sign-in.txt" || true
adb shell am force-stop "$PACKAGE"

adb shell am start -n "$PACKAGE/org.telegram.cogram.CogramLoginActivity" --ez demo true
sleep "$START_SECONDS"
adb exec-out screencap -p > "$OUT/1-chats.png" || true

adb shell input tap 160 160
sleep 5
adb exec-out screencap -p > "$OUT/2-chat.png" || true
adb shell input tap 120 600
sleep 3
adb shell input text hello
sleep 2
adb exec-out screencap -p > "$OUT/3-typed.png" || true
# Ctrl+Enter sends without needing the position of the send button.
adb shell input keycombination 113 66 || true
sleep "$ECHO_SECONDS"
adb exec-out screencap -p > "$OUT/4-echo.png" || true

adb shell pidof "$PACKAGE" > "$OUT/pid.txt" || true
adb logcat -d -v threadtime > "$OUT/logcat.txt" || true
adb logcat -d -b crash -v threadtime > "$OUT/logcat-crash.txt" || true
adb shell dumpsys activity exit-info "$PACKAGE" > "$OUT/exit-info.txt" || true
adb shell run-as "$PACKAGE" cat files/cogram_crash.txt > "$OUT/java-crash.txt" || true
exit 0
