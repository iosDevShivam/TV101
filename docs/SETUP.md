# Setup

## What you need

- The TV: an Android TV, version 8 or newer. Built and tested on an Android TV 11 set.
- On the computer: `adb` (Android platform-tools), JDK 17 or newer, the Android SDK with platform 34
  (`ANDROID_HOME` set). On a Mac: `brew install --cask android-commandlinetools temurin`, then
  `sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"`.

## 1. Switch on USB debugging

Settings → Device Preferences → About → press **Build** seven times; then Developer options → **USB debugging** on.
On many TVs, including the one this was tested on, that alone also opens network ADB on port 5555, and it stays
open after a reboot for as long as USB debugging is on. Find the TV's address under Settings → Network.

## 2. Install

```sh
TV_HOST=192.168.1.50 scripts/install.sh
```

It builds the launcher, installs it, makes it Home, grants what the features need (see the script for each one and
why), sets the screensaver to start after 2 minutes, and pushes the theme. The first time, the TV asks **"Allow USB
debugging?"**: tick **Always allow from this computer**, then **Allow**. The launcher's own connection for the phone
remote asks once more the same way, with its own key; accept that too.

## 3. Pair your phone

Scan the QR on Home (or open `http://<TV>:8080`), tap **Show code on TV**, enter the 4 digits. Add it to the home
screen: Chrome ⋮ → *Add to Home screen*, or Safari Share → *Add to Home Screen* (full screen on iPhone).
Tip: reserve the TV's address in the router so the link and the QR never change.

## 4. Personal settings

Make your own theme once: `cp -R theme/default theme/my-tv`. It stays on your computer (`theme/my-tv/` is in
`.gitignore`), so your name and photo never reach GitHub. Edit `theme/my-tv/config.json`, then
`TV_HOST=... scripts/push-theme.sh theme/my-tv`:

| Key | What it does |
|---|---|
| `greeting_name` | The name in "Good evening, …" on Home |
| `lock_name` | Your full name next to your picture on the screensaver |
| `weather_city` | City for the weather, e.g. `"London"`; empty uses the internet connection's location |
| `calendar_ics_url` | Google Calendar → Settings → your calendar → *Secret address in iCal format* |
| `dock_apps` | The six apps on Home, by package name |
| `hidden_apps` | Apps kept out of Home |
| `clear_ram_button`, `show_network_pill` | Show Clear RAM and Wi-Fi in the action row |
| `phone_remote`, `lock_weather` | Turn the phone remote or the screensaver weather off |

**Your picture:** put a square photo named `avatar.jpg` in `theme/my-tv/` and push the theme. No rebuild. Without one,
Home and the screensaver show a neutral placeholder.

## Undo

- Back to the stock home: Settings → Apps → *Home* (com.gathin.tvhome) → uninstall, or `adb uninstall com.gathin.tvhome`
  (re-enable the stock launcher first if you disabled it, see [DEBLOAT.md](DEBLOAT.md)).
- Screensaver back to the TV's own: Settings → Device Preferences → Screen saver.
- Phone remote off: `"phone_remote": false` in the theme.
- Close network ADB: switch USB debugging off.
