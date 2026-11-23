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
# The app's private files: through run-as on debug builds. Production builds refuse run-as, as they are not
# debuggable, so there the emulator's root is used; nothing in the app opens them.
APP_ROOT=""
if ! adb shell run-as "$PACKAGE" true > /dev/null 2>&1; then
  APP_ROOT=1
  adb root > /dev/null 2>&1 || true
  adb wait-for-device
  sleep 3
fi
as_app() {
  if [ -n "$APP_ROOT" ]; then
    adb shell "cd /data/data/$PACKAGE && $*"
  else
    adb shell run-as "$PACKAGE" "$@"
  fi
}
as_app mkdir -p shared_prefs || true
as_app cp /data/local/tmp/cogram_gateway.xml shared_prefs/cogram_gateway.xml || true
if [ -n "$APP_ROOT" ]; then
  # Written by root: the file is given back to the app, with the app's own security label.
  OWNER=$(adb shell stat -c %u:%g "/data/data/$PACKAGE" | tr -d '\r')
  adb shell chown -R "$OWNER" "/data/data/$PACKAGE/shared_prefs" || true
  adb shell chmod 660 "/data/data/$PACKAGE/shared_prefs/cogram_gateway.xml" || true
  adb shell restorecon -R "/data/data/$PACKAGE/shared_prefs" || true
fi
as_app ls -lZ shared_prefs > "$OUT/prefs.txt" || true
adb shell am start -n "$PACKAGE/org.telegram.messenger.DefaultIcon"
sleep "$START_SECONDS"
adb exec-out screencap -p > "$OUT/1-chats.png" || true
# Builds given the update address (usmoke* tags) show the update sheet soon after opening.
sleep 8
adb exec-out screencap -p > "$OUT/1-update.png" || true
adb logcat -d -s PhogramUpdate:W | tee -a "$OUT/steps.txt" > /dev/null || true
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

# Live TV: Al Jazeera's broadcast plays on its screen, then goes on in the system's small window when the app
# leaves; the activities' dump shows whether the window is pinned. The launcher brings the screen back.
if tap_view 'text="Live TV"'; then
  sleep 3
  dump_screen 6-live-channels
  tap_view 'text="Al Jazeera"' || true
  sleep 20
  dump_screen 6-live-player
  adb shell input keyevent 3 || true
  sleep 6
  adb exec-out screencap -p > "$OUT/6-live-pip.png" || true
  adb shell dumpsys activity activities | grep -iE "pinned|mInPictureInPicture|isInPictureInPictureMode" > "$OUT/6-live-pip.txt" || true
  adb shell am start -n "$PACKAGE/org.telegram.messenger.DefaultIcon"
  sleep 6
  dump_screen 6-live-back
  adb shell input keyevent 4 || true
  sleep 2
  adb shell input keyevent 4 || true
  sleep 3
fi

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
  as_app ls -l files 2>/dev/null > "$OUT/files.txt" || true
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
  adb logcat -d -s PhogramSearch:D | tee -a "$OUT/steps.txt" > /dev/null || true
  # The first result, at the top of the results list.
  tap_view_top 'content-desc="Search results"' || adb shell input tap 160 175
  sleep 5
  dump_screen 7-maps-place
  # A short trip is enough to see the navigation: the card is closed, and a place dropped by a long press a few
  # hundred metres up the map from the driver becomes the destination (about 1 km by road).
  adb shell input keyevent 4
  sleep 2
  tap_view 'content-desc="My location"' || true
  send_fix
  sleep 3
  adb shell input swipe 160 70 160 70 900 || true
  sleep 4
  dump_screen 7-maps-pin
  # Directions by car, offline: how long the route takes is written to the log. The button reads "Go · 1 min".
  adb logcat -c || true
  send_fix
  tap_view 'text="Go' || adb shell input tap 130 522
  for wait in $(seq 1 36); do
    sleep 5
    send_fix
    adb logcat -d -s PhogramRoute:D | grep -q PhogramRoute && break
  done
  sleep 4
  adb exec-out screencap -p > "$OUT/7-maps-route.png" || true
  adb logcat -d -s PhogramRoute:D | tee -a "$OUT/steps.txt" > "$OUT/route.txt" || true
  adb logcat -d | grep -E "net.osmand|System.err" | tail -40 > "$OUT/route-errors.txt" || true
  # The weather on the route: the line under the route's time and the bubbles on it, once the tiles have come.
  sleep 8
  adb exec-out screencap -p > "$OUT/7-maps-weather.png" || true
  adb logcat -d -s PhogramWeather:D | tee -a "$OUT/steps.txt" > "$OUT/weather.txt" || true
  # The navigation: the green strip with the next turn, and the panel with the arrival and End.
  tap_view 'text="Start"' || adb shell input tap 90 540
  for wait in 1 2 3 4; do
    sleep 2
    send_fix
  done
  adb exec-out screencap -p > "$OUT/7-maps-navigation.png" || true
  # Driving a few steps up the road: the part of the route already driven disappears behind the arrow.
  for step in 1 2 3; do
    lat=$(awk -v s="$step" 'BEGIN { printf "%.6f", 30.5085 + s * 0.0003 }')
    adb shell cmd location providers set-test-provider-location gps --location "$lat,47.7804" --accuracy 5 || true
    adb emu geo fix 47.7804 "$lat" || true
    sleep 2
  done
  adb exec-out screencap -p > "$OUT/7-maps-driven.png" || true
  send_fix
  sleep 2
  # Moving the map pops up the route's road names in Telegram's message bubbles; then back to the driver.
  adb shell input swipe 160 360 160 200 400 || true
  sleep 3
  adb exec-out screencap -p > "$OUT/7-maps-names.png" || true
  # The way back to driving counts down from 10 s, then follows the driver again by itself.
  sleep 2
  adb exec-out screencap -p > "$OUT/7-maps-countdown.png" || true
  sleep 9
  send_fix
  sleep 2
  adb exec-out screencap -p > "$OUT/7-maps-back-to-driving.png" || true
  # No position for over half a minute (a tunnel, a weak signal): the arrow stays an arrow, in grey.
  sleep 36
  adb exec-out screencap -p > "$OUT/7-maps-stale.png" || true
  send_fix
  sleep 2
  # The overview frames the whole route ahead at the first press.
  if tap_view 'content-desc="Overview"'; then
    sleep 3
    adb exec-out screencap -p > "$OUT/7-maps-overview.png" || true
    tap_view 'content-desc="Back to my position"' || true
  fi
  sleep 2
  # The trip panel opened by a tap, then the fuel stations on the road ahead as pins.
  tap_view 'text="Time left"' || adb shell input tap 160 520
  sleep 2
  adb exec-out screencap -p > "$OUT/7-maps-trip.png" || true
  if tap_view 'text="Fuel"'; then
    for wait in 1 2 3 4 5 6; do
      sleep 3
      send_fix
    done
    adb exec-out screencap -p > "$OUT/7-maps-along.png" || true
    adb logcat -d -s PhogramAlong:D | tee -a "$OUT/steps.txt" > /dev/null || true
  fi
  # End finishes the trip: no route, no card, the map as before; Back would ask first.
  tap_view 'text="End"' || adb shell input tap 280 505
  sleep 3
  adb exec-out screencap -p > "$OUT/7-maps-after-navigation.png" || true
  # The same trip in dark mode: the map's dark palette, the speed gauge, the road-name bubbles and the trip figures.
  adb shell cmd uimode night yes || true
  sleep 5
  send_fix
  adb exec-out screencap -p > "$OUT/7-maps-dark.png" || true
  adb shell input swipe 160 70 160 70 900 || true
  sleep 4
  send_fix
  if tap_view 'text="Go' || adb shell input tap 130 522; then
    for wait in $(seq 1 12); do
      sleep 3
      send_fix
      tap_view 'text="Start"' && break
    done
    for wait in 1 2 3; do
      sleep 2
      send_fix
    done
    adb exec-out screencap -p > "$OUT/7-maps-navigation-dark.png" || true
    adb shell input swipe 160 360 160 200 400 || true
    sleep 3
    adb exec-out screencap -p > "$OUT/7-maps-names-dark.png" || true
    tap_view 'text="End"' || adb shell input tap 280 505
    sleep 2
  fi
  adb shell cmd uimode night no || true
  sleep 3
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
  # The app may come back inside a chat (the map's "Send to chat" leaves it there), where the tabs
  # are hidden and "More options" is the chat's menu, so back is pressed until the tabs show.
  for attempt in 1 2 3 4; do
    if tap_view 'text="Chats"'; then
      break
    fi
    adb shell input keyevent 4 || true
    sleep 2
  done
  sleep 2
}

back_to_chats
tap_view 'content-desc="More options"' || true
sleep 2
dump_screen 8-chats-menu
tap_view 'text="Settings"' || true
sleep 3
dump_screen 9-settings
adb shell input swipe 160 520 160 120 400 || true
sleep 2
dump_screen 9-settings-lower
adb shell input swipe 160 520 160 120 400 || true
sleep 2
dump_screen 9-settings-end
# Phogram has no Premium, Stars, Gram wallet or gifts: none of them may be offered in the menu or settings.
paid=$(cat "$OUT"/8-chats-menu.xml "$OUT"/9-settings*.xml 2>/dev/null \
  | grep -oiE 'text="[^"]*\b(Premium|Stars|Gram|TON|Wallet|Gifts?|Business)\b[^"]*"' | sort -u)
if [ -n "$paid" ]; then
  echo "PAID SURFACES SHOWN: $paid" | tee -a "$OUT/steps.txt"
else
  echo "Paid surfaces: none in the menu and settings" | tee -a "$OUT/steps.txt"
fi
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

# Chats' files, pictures, logs and caches stay in the app's private storage. Outside it only the downloaded
# map data may live (public OpenStreetMap data), so anything else there is reported.
outside=$(adb shell "find /sdcard/Android/data/$PACKAGE /sdcard/Android/media/$PACKAGE /sdcard/Pictures/Telegram -type f 2>/dev/null" \
  | tr -d '\r' | grep -v "/files/maps/" || true)
if [ -n "$outside" ]; then
  echo "FILES OUTSIDE PRIVATE STORAGE:" | tee -a "$OUT/steps.txt"
  echo "$outside" | head -40 | tee -a "$OUT/steps.txt"
else
  echo "Private storage: nothing outside it but map data" | tee -a "$OUT/steps.txt"
fi
as_app ls -R files/Telegram cache > "$OUT/private-files.txt" 2>&1 || true

# How much the installed app takes, as Settings > Apps counts "App size": the APK, libraries unpacked from it
# (none when they run from the APK) and the code Android prepared (oat). Read as root where the shell may not.
CODE_DIR=$(adb shell pm path "$PACKAGE" | head -1 | tr -d '\r' | sed 's|^package:||; s|/base.apk$||')
if [ -n "$CODE_DIR" ]; then
  sizes=$(adb shell "du -sk $CODE_DIR $CODE_DIR/lib $CODE_DIR/oat 2>/dev/null" | tr -d '\r')
  if [ -z "$sizes" ]; then
    sizes=$(adb shell su 0 du -sk "$CODE_DIR" "$CODE_DIR/lib" "$CODE_DIR/oat" 2>/dev/null | tr -d '\r')
  fi
  echo "Installed app size (KB):" | tee -a "$OUT/steps.txt"
  echo "$sizes" | sed "s|$CODE_DIR|<app>|" | tee -a "$OUT/steps.txt"
  adb shell ls -l "$CODE_DIR/base.apk" | tr -d '\r' | tee -a "$OUT/steps.txt" || true
fi

adb shell pidof "$PACKAGE" > "$OUT/pid.txt" || true
adb logcat -d -v threadtime > "$OUT/logcat.txt" || true
adb logcat -d -b crash -v threadtime > "$OUT/logcat-crash.txt" || true
adb shell dumpsys activity exit-info "$PACKAGE" > "$OUT/exit-info.txt" || true
as_app cat files/cogram_crash.txt > "$OUT/java-crash.txt" || true
exit 0
