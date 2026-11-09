# TV101: notes for Claude

Custom launcher (Home dashboard, All apps, screensaver, phone remote) for an Android TV. Read `README.md`
and `docs/ARCHITECTURE.md` for the design; `docs/GUIDE.md` is the user-facing guide.

## The TV

- Details of the owner's own TV (address, model) are in `CLAUDE.local.md`, which is git-ignored. If it is missing,
  ask for the TV's address.
- Tested on an Android TV 11 set: 4-core CPU, 920 MB RAM, screen 1280 × 720 at density 1.33.
- Launcher package `com.gathin.tvhome`; phone remote on port 8080.
- Connect: `adb connect <ip>:5555`, then `export ANDROID_SERIAL=<ip>:5555`. If the TV doesn't answer a ping it is off.

## Commands

```sh
cd launcher && ./gradlew -q assembleRelease          # build (JDK 17+, ANDROID_HOME set)
./gradlew testReleaseUnitTest                         # unit tests (IcsTest and the base launcher's)
TV_HOST=<ip> scripts/install.sh [--no-build]          # install + grants + screensaver settings + theme
TV_HOST=<ip> scripts/push-theme.sh theme/my-tv       # theme only (config.json, avatar.jpg, wallpapers)
source scripts/tv-test.sh                             # foc, key, front, shot, api, playing
python3 phone-remote/dev-server.py --art <dir>        # phone page without a TV
```

## Rules when working on the real TV

- **Check first whether someone is using it** (`front`, `playing`). Don't send keys, start the screensaver or open
  apps while something is playing or an app other than Home is in front; ask instead.
- Keys sent with `input keyevent` don't count as activity: the screensaver starts after 2 minutes and closes
  popups such as All apps. Keep test runs short; it is not a launcher bug.
- `input keyevent --longpress` acts as a click on this TV; long-press menus need the real remote.
- Never accept security prompts ("Allow USB debugging?") or change persistent debug settings for the owner; ask.
- If you change the volume for a test, put it back (`dumpsys audio`, STREAM_MUSIC speaker value).
- Remove any throwaway test app you install.
- Show mockups (Pillow or headless Chrome renders) before visual changes and let the owner pick; render at 2× with
  the Outfit font from `launcher/app/src/main/res/font/outfit.ttf`; no film grain (the owner dislikes it).
- Measure after changes: `dumpsys meminfo com.gathin.tvhome` (about 55 MB) and `top` (about 0 to 0.6 % CPU).

## Code conventions

- Java, plain Android views, no new libraries without a reason (ZXing core is the one addition).
- Match the surrounding comment style: a short doc comment saying why, not what.
- Performance first: no per-frame work, no polling where a callback exists, cache network reads, build bitmaps once.

## Secrets

- Never commit ADB keys, a paired phone's token or GitHub tokens (`.gitignore` covers the common ones).
- Never put a token in a git remote URL or git config. Push with the owner's own login (`gh auth login`).
- Nothing personal goes in the repo: names, photos, addresses and TV models belong in `theme/my-tv/` and
  `CLAUDE.local.md` (both git-ignored). Check with `git grep` before committing.
