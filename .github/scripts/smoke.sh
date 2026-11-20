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

# Taps near the top of the first matching view, where a list shows its first row.
tap_view_top() {
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
  echo "Tap top of $pattern" >> "$OUT/steps.txt"
  adb shell input tap $(( ($1 + $3) / 2 )) $(( $2 + 30 ))
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
# A tap on the echoed message opens Telegram's menu with the reaction bar, served by the gateway (a long press
# selects the message instead).
adb shell input keyevent 4 || true
sleep 1
adb shell input tap 100 555 || true
sleep 3
dump_screen 4-reactions
adb shell input keyevent 4 || true
sleep 2

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
# How smoothly the map pans: frames drawn and janky frames over eight drags across the map.
adb shell dumpsys gfxinfo "$PACKAGE" reset > /dev/null || true
adb logcat -c || true
for drag in 1 2 3 4 5 6 7 8; do
  adb shell input swipe 260 360 60 300 400 || true
  adb shell input swipe 60 300 260 360 400 || true
done
sleep 2
adb shell dumpsys gfxinfo "$PACKAGE" > "$OUT/gfxinfo-maps.txt" || true
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile" "$OUT/gfxinfo-maps.txt" | head -6 | tee -a "$OUT/steps.txt"
# A map drawn on its own GPU surface (MapLibre) reports its frame rate in the log instead.
adb logcat -d -s PhogramMapFps:D > "$OUT/map-fps.txt" || true
awk '/PhogramMapFps/ {v=$NF; n++; s+=v; if (min=="" || v<min) min=v; if (v>max) max=v} END {if (n) printf "Map frames per second: %d readings, average %.1f, lowest %.1f, highest %.1f\n", n, s/n, min, max}' \
  "$OUT/map-fps.txt" | tee -a "$OUT/steps.txt"
# The app ships without OsmAnd's world map, so the page offers it (about 38 MB from OsmAnd's servers).
dump_screen 7-maps-offer
if tap_view 'content-desc="Download"'; then
  sleep 4
  dump_screen 7-maps-downloading
  # The download runs in Android's DownloadManager, so it goes on when the app is in the background.
  adb shell dumpsys notification --noredact > "$OUT/notifications-downloading.txt" || true
  sleep 40
  dump_screen 7-maps-downloaded
fi
# Then the offer of OsmAnd's file for the search and the directions (about 170 MB from OsmAnd's server, unpacked on
# the phone), which the page offers once the map has been still for a few seconds.
sleep 8
if tap_view 'content-desc="Download"'; then
  for wait in $(seq 1 30); do
    sleep 10
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
    adb exec-out cat /sdcard/ui.xml | grep -q 'content-desc="Cancel"' || break
  done
  sleep 20
  dump_screen 7-maps-search-data
  adb shell run-as "$PACKAGE" ls -l files 2>/dev/null > "$OUT/files.txt" || true
  adb shell ls -l /sdcard/Android/data/$PACKAGE/files/maps /sdcard/Android/data/$PACKAGE/files/maps/osmand >> "$OUT/files.txt" 2>&1 || true
fi
# The emulator reports a fixed position, in Basra, where the directions to Mosul start; the app was given the
# location permission at install, and the emulator's location service is turned on first.
adb shell cmd location set-location-enabled true || true
# The emulator's own GPS did not report to the app, so Android's test provider stands in for the GPS: the shell may
# give positions to it, and the app reads them as the GPS's.
adb shell appops set com.android.shell android:mock_location allow || true
adb shell cmd location providers add-test-provider gps || true
adb shell cmd location providers set-test-provider-enabled gps true || true
send_fix() {
  adb shell cmd location providers set-test-provider-location gps --location 30.5085,47.7804 --accuracy 5 || true
  adb emu geo fix 47.7804 30.5085 || true
}
send_fix
tap_view 'content-desc="My location"' || true
# A phone's GPS keeps reporting; the emulator reports only when told, so the position is sent again while the map
# listens for it.
for fix in 1 2 3; do
  sleep 1
  send_fix
done
sleep 5
adb exec-out screencap -p > "$OUT/7-maps-location.png" || true
# The search sheet with a word nothing matches: it must say so, and back must close the sheet, not leave it stuck.
# The moving position marker can keep the screen reader from reading the page; the bar is then tapped where it is.
if tap_view 'content-desc="Where to?"' || adb shell input tap 160 532; then
  sleep 2
  dump_screen 7-maps-sheet
  adb shell input text zzqqxx
  sleep 6
  dump_screen 7-maps-no-results
  adb shell input keyevent 4
  sleep 1
  adb shell input keyevent 4
  sleep 2
  dump_screen 7-maps-sheet-closed
fi
# Offline search in the downloaded world map, then the place card of the first result.
# The moving position marker can keep the screen reader from reading the page; the bar is then tapped where it is.
if tap_view 'content-desc="Where to?"' || adb shell input tap 160 532; then
  sleep 2
  adb shell input text Mosul
  sleep 6
  dump_screen 7-maps-search
  # The first result, at the top of the results list.
  tap_view_top 'content-desc="Search results"' || adb shell input tap 160 175
  sleep 5
  dump_screen 7-maps-place
  # Directions by car from Basra to Mosul, offline: how long the route takes is written to the log.
  adb logcat -c || true
  send_fix
  tap_view 'text="Directions"' || adb shell input tap 88 523
  for wait in $(seq 1 36); do
    sleep 5
    send_fix
    adb logcat -d -s PhogramRoute:D | grep -q PhogramRoute && break
  done
  sleep 4
  adb exec-out screencap -p > "$OUT/7-maps-route.png" || true
  adb logcat -d -s PhogramRoute:D | tee -a "$OUT/steps.txt" > "$OUT/route.txt" || true
  adb logcat -d | grep -E "net.osmand|System.err" | tail -40 > "$OUT/route-errors.txt" || true
  # The place goes to the demo chat as a location, through Telegram's chat picker.
  if tap_view 'text="Send to chat"'; then
    sleep 3
    dump_screen 7-maps-picker
    adb shell input tap 160 160
    sleep 4
    dump_screen 7-maps-sent
  fi
  adb shell input keyevent 4
  sleep 2
fi
tap_view 'text="Chats"' || true
sleep 3
# The demo chat with the location just sent, shown as Telegram's location bubble.
adb shell input tap 160 160
sleep 4
dump_screen 7-chat-location

# Back presses above may leave the app, so the next steps start from the chats page through the launcher.
back_to_chats() {
  adb shell am start -n "$PACKAGE/org.telegram.messenger.DefaultIcon" > /dev/null
  sleep 5
  tap_view 'text="Chats"' || true
  sleep 2
}

back_to_chats
tap_view 'content-desc="More options"' || true
sleep 2
dump_screen 8-chats-menu
tap_view 'text="Settings"' || true
sleep 3
dump_screen 9-settings
# Phogram's assistant tab, where Telegram has the Profile tab.
adb shell input keyevent 4
sleep 2
back_to_chats
tap_view 'text="Assistant"' || true
sleep 3
dump_screen 10-assistant
# A conversation with the assistant: Telegram's chat screen, the reply written out, then the conversation listed.
if tap_view 'text="New conversation"'; then
  sleep 4
  dump_screen 11-assistant-chat
  adb shell input tap 120 600
  sleep 2
  adb shell input text "Hello%sassistant"
  sleep 1
  adb shell input keycombination 113 66 || true
  sleep 3
  dump_screen 11-assistant-streaming
  sleep 10
  dump_screen 11-assistant-reply
  adb shell input keyevent 4
  sleep 2
  adb shell input keyevent 4
  sleep 3
  dump_screen 12-assistant-recent
fi

adb shell pidof "$PACKAGE" > "$OUT/pid.txt" || true
adb logcat -d -v threadtime > "$OUT/logcat.txt" || true
adb logcat -d -b crash -v threadtime > "$OUT/logcat-crash.txt" || true
adb shell dumpsys activity exit-info "$PACKAGE" > "$OUT/exit-info.txt" || true
adb shell run-as "$PACKAGE" cat files/cogram_crash.txt > "$OUT/java-crash.txt" || true
exit 0
