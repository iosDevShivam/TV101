#!/usr/bin/env bash
# Pushes a theme folder (config.json and wallpapers/) to the launcher and restarts Home so it takes effect:
#   TV_HOST=192.168.1.50 scripts/push-theme.sh theme/my-tv
#
# A theme folder holds config.json, optionally wallpapers/ and avatar.jpg (the picture on Home and the screensaver).
#
# Files go through the launcher's own ThemeProvider (content://com.gathin.tvhome.theme), so they belong to the
# launcher; a plain copy into Android/data would belong to the shell and the launcher could not read it.
set -euo pipefail

PKG=com.gathin.tvhome
THEME="${1:?usage: push-theme.sh <theme folder>}"
[ -f "$THEME/config.json" ] || { echo "no config.json in $THEME" >&2; exit 2; }
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$THEME/config.json" \
  || { echo "$THEME/config.json is not valid JSON; nothing was sent" >&2; exit 2; }
if [ -n "${TV_HOST:-}" ]; then
  export ANDROID_SERIAL="$TV_HOST:5555"
  adb connect "$ANDROID_SERIAL" >/dev/null
fi

URI="content://$PKG.theme"
TMP=/data/local/tmp/tv101-theme

put() {   # put <local file> <path inside the theme>
  adb push "$1" "$TMP" >/dev/null
  adb shell "content write --uri $URI/$2 < $TMP; rm -f $TMP"
}

echo "Pushing $(basename "$THEME")..."
put "$THEME/config.json" config.json
adb shell "content delete --uri $URI/wallpapers 2>/dev/null" || true
if [ -d "$THEME/wallpapers" ]; then
  for f in "$THEME"/wallpapers/*; do
    [ -f "$f" ] && put "$f" "wallpapers/$(basename "$f")"
  done
fi
# The picture on Home and the screensaver: the theme's own, or back to the neutral placeholder.
if [ -f "$THEME/avatar.jpg" ]; then
  put "$THEME/avatar.jpg" avatar.jpg
else
  adb shell "content delete --uri $URI/avatar.jpg 2>/dev/null" || true
fi
# A new revision tells Home the theme changed.
date +%s > /tmp/tv101-revision && put /tmp/tv101-revision theme_revision && rm -f /tmp/tv101-revision

echo "Restarting Home..."
adb shell "am force-stop $PKG; am start -a android.intent.action.MAIN -c android.intent.category.HOME" >/dev/null
echo "Theme is on the TV."
