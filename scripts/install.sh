#!/usr/bin/env bash
# Builds the launcher, installs it on the TV and sets everything it needs, in one go:
#   TV_HOST=192.168.1.50 scripts/install.sh            # build, install, set up, push the theme
#   TV_HOST=192.168.1.50 scripts/install.sh --no-build # install the APK already built
#   THEME=theme/default TV_HOST=... scripts/install.sh  # a particular theme (default: theme/my-tv if it exists)
#
# Needs: adb (Android platform-tools), JDK 17+ and the Android SDK (ANDROID_HOME) for the build, and USB
# debugging switched on in the TV's Developer options (on this TV that also opens network ADB on port 5555).
# The first connection makes the TV ask "Allow USB debugging?": tick "Always allow", then Allow.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.gathin.tvhome
APK="$ROOT/launcher/app/build/outputs/apk/release/app-release.apk"
: "${TV_HOST:?set TV_HOST to the address of the TV, e.g. TV_HOST=192.168.1.50}"
TARGET="$TV_HOST:5555"
export ANDROID_SERIAL="$TARGET"

if [ "${1:-}" != "--no-build" ]; then
  echo "Building the launcher..."
  (cd "$ROOT/launcher" && ./gradlew -q assembleRelease)
fi
[ -f "$APK" ] || { echo "No APK at $APK. Build first (drop --no-build)." >&2; exit 1; }

echo "Connecting to $TARGET..."
adb connect "$TARGET" >/dev/null
adb wait-for-device

echo "Installing..."
adb install -r "$APK" >/dev/null

echo "Making it Home..."
adb shell cmd package set-home-activity "$PKG/.MainActivity" >/dev/null

echo "Granting what the features need..."
# The Wi-Fi name on Home (Android 10+ ties it to location).
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
# Phone remote: the pairing code over other apps, opening apps from the background, and the app on screen.
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell appops set "$PKG" GET_USAGE_STATS allow
# Screensaver Now playing: see what is playing (reads no notification).
adb shell cmd notification allow_listener "$PKG/$PKG.MediaListener"

echo "Setting the screensaver..."
adb shell settings put secure screensaver_components "$PKG/.ClockDream"
adb shell settings put secure screensaver_enabled 1
adb shell settings put secure screensaver_activate_on_sleep 1
adb shell settings put system screen_off_timeout 120000   # 2 minutes

# Your own theme (name, photo, your apps) if you made one, else the neutral default.
if [ -z "${THEME:-}" ]; then
  if [ -d "$ROOT/theme/my-tv" ]; then THEME="$ROOT/theme/my-tv"; else THEME="$ROOT/theme/default"; fi
fi
"$ROOT/scripts/push-theme.sh" "$THEME"

echo "Done. Phone remote: http://$TV_HOST:8080"
