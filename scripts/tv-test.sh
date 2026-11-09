# Helpers used to test the launcher on the TV. Source it, then call the functions:
#   export TV_HOST=192.168.1.50 TV_TOKEN=<a paired phone's token>   # token: optional, for api
#   source scripts/tv-test.sh
#   foc            # the focused view: id, description, bounds
#   key 20         # press a key (19 up, 20 down, 21 left, 22 right, 23 OK, 4 back, 3 home) and wait
#   front          # the window in front
#   shot home      # screenshot to docs/screenshots/tv/home.png
#   api /api/state # call the phone remote's API
#   playing        # media sessions and their play state (3 = playing, 2 = paused)
#
# Keys sent this way do not count as someone using the TV, so the screensaver still starts after 2 minutes;
# keep a test run short, or it will cover the screen part way through.

: "${TV_HOST:?set TV_HOST to the address of the TV}"
export ANDROID_SERIAL="$TV_HOST:5555"
TV101_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"

foc() {
  adb shell 'uiautomator dump /data/local/tmp/u.xml >/dev/null 2>&1; grep -o "<node[^>]*focused=\"true\"[^>]*" /data/local/tmp/u.xml' \
    | grep -o 'resource-id="[^"]*"\|content-desc="[^"]*"\|bounds="[^"]*"' \
    | sed 's/resource-id="com.gathin.tvhome:id\//id=/; s/"//g' | tr '\n' ' '
  echo
}
key() { adb shell input keyevent "$@"; sleep 1.2; }
front() { adb shell "dumpsys window | grep mCurrentFocus" | sed 's/.*u0 //; s/}//'; }
shot() {
  mkdir -p "$TV101_ROOT/docs/screenshots/tv"
  adb shell screencap -p /data/local/tmp/s.png
  adb pull /data/local/tmp/s.png "$TV101_ROOT/docs/screenshots/tv/$1.png" >/dev/null
  echo "$TV101_ROOT/docs/screenshots/tv/$1.png"
}
api() { curl -s -m 10 -H "X-Token: ${TV_TOKEN:-}" "http://$TV_HOST:8080$1" ${2:+-d "$2"}; echo; }
playing() {
  adb shell "dumpsys media_session" | grep -E "package=|state=PlaybackState \{state=" | paste - - \
    | sed 's/ *package=//; s/state=PlaybackState {state=/ state=/; s/,.*//'
}
