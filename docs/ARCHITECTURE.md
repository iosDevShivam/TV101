# Architecture

The launcher is one Android app, `com.gathin.tvhome`, built on [cleanyourtv](https://github.com/Gathin23/cleanyourtv)'s
launcher. This page covers the parts added or redesigned for TV101.

## Components

| Piece | Files | Role |
|---|---|---|
| Home dashboard | `res/layout/activity_main.xml`, `MainActivity.java` | Left column (greeting, clock, date, weather, Up next, QR), 3 × 2 app grid, full-width action row |
| All apps | `AppLibraryDialog.java` | Full-screen A–Z grid on uniform tiles, glow in the focused app's colour |
| Screensaver | `ClockDream.java`, `ClockScene.java`, `res/layout/dream_clock.xml` | Clock, date, weather, month, name, Now playing, QR |
| Month | `MonthView.java` | The whole month drawn by one view in one pass |
| Now playing | `NowPlaying.java`, `MediaListener.java` | Follows the playing media session |
| Weather, calendar | `LockInfo.java`, `Ics.java` | Open-Meteo and iCal, cached in SharedPreferences, shared by Home and the screensaver |
| Phone remote | `RemoteService.java`, `RemoteServer.java`, `TvControl.java`, `Adb.java`, `assets/remote/index.html` | Web page and API on port 8080 |
| QR | `RemoteQr.java` | `http://<address>:8080` as a QR bitmap (ZXing core) |
| Theme keys | `Config.overlay*` | Reads `config.json` once, again only when the file changes |

## Performance decisions

The TV has a 4-core MediaTek CPU and 920 MB of RAM, of which the system leaves little free. Measured results are in
[TESTING.md](TESTING.md): 0 to 0.6 % CPU and about 55 MB for the launcher.

- **The month is one view.** `MonthView` draws the title, weekday initials and up to 31 days with four `Paint`s
  instead of ~50 `TextView`s, and only invalidates when the date changes (checked once a minute).
- **Now playing is event-driven.** `MediaSessionManager` and `MediaController.Callback` say when the song or the
  play state changes. The only timer is the progress bar's, once a second, and only while something plays and the
  screensaver is up. Album art is scaled to 160 px once; a URI is fetched off the main thread.
- **Network reads are cached and throttled.** Weather at most every 30 minutes, the calendar every 15, the location
  once a day; each on a background thread, guarded so two fetches never overlap. Home and the screensaver read the
  same cache.
- **The QR is built once** per address (a bitmap of one pixel per module, drawn without filtering) and reused by Home
  and the screensaver.
- **The theme file is cached.** Home and the screensaver ask for theme keys every minute; `Config.overlay` re-reads
  `config.json` only when its timestamp or size changes.
- **Keys for the phone remote don't start a process.** `input keyevent` spawns a Java process each time (~1.7 s on
  this CPU). Instead the shell's `hid` tool registers a virtual remote once and stays running, so a key is one HID
  report written to it (~15 ms). It is registered as soon as the launcher connects, so the first key is not lost.
- **The All apps glow is one drawable.** A radial `GradientDrawable` behind the grid; focus moves its centre and fades
  its colour. Each app's colour is computed once, on the icon loader thread, from a 16 × 16 render of its icon.
- **No film grain.** It was tried on the screensaver and removed: visible on the panel, and a full-screen layer for
  nothing.

## Robustness

- The remote server retries binding port 8080 (with back-off) if it is briefly busy after a restart, and is started
  again each time Home resumes; the service is a foreground service with `START_STICKY`.
- The ADB connection reconnects on demand; the virtual remote is re-registered if its stream closes, with
  `input keyevent` as a fallback.
- Pairing: 4-digit code shown on the TV, 2-minute lifetime, 5 tries; 128-bit tokens.
- Links are limited to http(s) with a real host.
- Now playing does nothing (and the screensaver is unchanged) without notification access.
- Every network read has timeouts and size limits; failures keep the last good reading.

## Data flow of the phone remote

```
phone  ──HTTP :8080──▶  RemoteServer ──▶ TvControl ──┬─▶ startActivity (apps, links, inputs, screensaver)
                         (token check)               ├─▶ Adb (127.0.0.1:5555) ─▶ hid  (keys, ~15 ms)
                                                     │                        └─▶ input (text)
                                                     └─▶ UsageStats (what is on screen)
```
