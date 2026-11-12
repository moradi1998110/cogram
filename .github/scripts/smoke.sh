#!/bin/bash
# Starts the app on the emulator, signs in through Telegram's login pages, sends a message and
# collects what happened. It never fails on a crash, because the logs are what the run is for.
# Buttons are found by their text in the UI dump, and the chat taps assume the 320x640 screen.
PACKAGE=app.phogram
OUT=smoke
INTRO_SECONDS=30
PAGE_SECONDS=4
SIGN_IN_SECONDS=75
START_SECONDS=45
ECHO_SECONDS=8

mkdir -p "$OUT"
# Permission dialogs would swallow the taps below, so grant everything at install.
adb install -r -t -g dist/cogram-x86_64-debug.apk
adb logcat -c

# Taps the centre of the first view whose UI dump attributes match the pattern.
tap_view() {
  local pattern="$1"
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null || true
  local bounds
  bounds=$(adb exec-out cat /sdcard/ui.xml | grep -oE "<node [^>]*${pattern}[^>]*>" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  set -- $bounds
  if [ $# -ne 4 ]; then
    echo "No view matches $pattern" | tee -a "$OUT/steps.txt"
    return 1
  fi
  echo "Tap $pattern" >> "$OUT/steps.txt"
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

dump_screen() {
  adb exec-out screencap -p > "$OUT/$1.png" || true
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null || true
  adb exec-out cat /sdcard/ui.xml > "$OUT/$1.xml" || true
}

# First run as a phone sees it: the launcher icon with no account opens Telegram's intro.
adb shell am start -n "$PACKAGE/org.telegram.messenger.DefaultIcon"
sleep "$INTRO_SECONDS"
dump_screen 0-intro
adb shell pidof "$PACKAGE" > "$OUT/pid-intro.txt" || true

tap_view 'text="Start Messaging"'
sleep "$PAGE_SECONDS"
dump_screen 0-login

tap_view 'text="Log in with username"'
sleep "$PAGE_SECONDS"
dump_screen 0-username

# A failed sign-in starts the engine for real. This address cannot resolve, so nothing leaves the runner.
adb shell input text smoke@cogram.invalid
tap_view 'content-desc="Done"'
sleep "$PAGE_SECONDS"
dump_screen 0-password
adb shell input text smoke-test
tap_view 'content-desc="Done"'
sleep "$SIGN_IN_SECONDS"
dump_screen 0-sign-in
cp "$OUT/0-sign-in.xml" "$OUT/sign-in.xml"
adb shell pidof "$PACKAGE" > "$OUT/pid-sign-in.txt" || true
adb logcat -d -v threadtime > "$OUT/logcat-sign-in.txt" || true
adb shell am force-stop "$PACKAGE"

# The demo backend serves a chat without a server. Its switch is a preference the release app never writes.
# adb joins its arguments into one device command, so the file is pushed rather than echoed through quotes.
echo '<?xml version="1.0" encoding="utf-8"?><map><boolean name="demo" value="true" /></map>' > "$OUT/cogram_gateway.xml"
adb push "$OUT/cogram_gateway.xml" /data/local/tmp/cogram_gateway.xml > /dev/null || true
adb shell chmod 644 /data/local/tmp/cogram_gateway.xml || true
adb shell run-as "$PACKAGE" mkdir -p shared_prefs || true
adb shell run-as "$PACKAGE" cp /data/local/tmp/cogram_gateway.xml shared_prefs/cogram_gateway.xml || true
adb shell run-as "$PACKAGE" ls -l shared_prefs > "$OUT/prefs.txt" || true
adb shell am start -n "$PACKAGE/org.telegram.messenger.DefaultIcon"
sleep "$START_SECONDS"
adb exec-out screencap -p > "$OUT/1-chats.png" || true
adb shell dumpsys meminfo "$PACKAGE" > "$OUT/meminfo-chats.txt" || true

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

# Phogram's Services tab, where Telegram has Contacts. The second picture shows whether the adverts turn
# by themselves, and the third whether the page follows the system's dark mode.
adb shell input keyevent 4
sleep 3
adb shell input keyevent 4
sleep 3
if ! tap_view 'text="Services"'; then
  # Back may have left the app; the launcher brings back the chats page with its tabs.
  adb shell am start -n "$PACKAGE/org.telegram.messenger.DefaultIcon"
  sleep 8
  tap_view 'text="Services"' || true
fi
sleep 3
dump_screen 5-services
sleep 6
adb exec-out screencap -p > "$OUT/5-services-later.png" || true
adb shell cmd uimode night yes || true
sleep 5
adb exec-out screencap -p > "$OUT/6-services-dark.png" || true
adb shell cmd uimode night no || true
sleep 5

# Phogram's Maps tab holds Telegram's Settings slot, so Settings must open from the chats page's options menu.
tap_view 'text="Maps"' || true
sleep 3
dump_screen 7-maps
sleep 5
adb exec-out screencap -p > "$OUT/7-maps-later.png" || true
adb shell dumpsys meminfo "$PACKAGE" > "$OUT/meminfo-maps.txt" || true
tap_view 'text="Chats"' || true
sleep 3
tap_view 'content-desc="More options"' || true
sleep 2
dump_screen 8-chats-menu
tap_view 'text="Settings"' || true
sleep 3
dump_screen 9-settings

adb shell pidof "$PACKAGE" > "$OUT/pid.txt" || true
adb logcat -d -v threadtime > "$OUT/logcat.txt" || true
adb logcat -d -b crash -v threadtime > "$OUT/logcat-crash.txt" || true
adb shell dumpsys activity exit-info "$PACKAGE" > "$OUT/exit-info.txt" || true
adb shell run-as "$PACKAGE" cat files/cogram_crash.txt > "$OUT/java-crash.txt" || true
exit 0
