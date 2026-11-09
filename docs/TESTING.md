# Testing

Run on the TV itself (an Android TV 11 set, 4-core, 920 MB) on 8 November 2026, over ADB, with screenshots and
`uiautomator` focus dumps. Helpers: [`scripts/tv-test.sh`](../scripts/tv-test.sh).

Keys sent over ADB do not count as someone using the TV, so the screensaver can start part way through a long test
run; keep runs under two minutes.

## Home

| Scenario | Result |
|---|---|
| Down from the apps reaches the action row; Right steps through all six buttons and stops at Settings | Pass |
| Up from the action row returns to the last app | Pass |
| Left at the grid's edge stays | Pass |
| Clear RAM | Pass: "Freed 118 MB · 418 MB available" |
| Wi-Fi opens network settings; Back returns to the button | Pass |
| QR decodes to `http://<TV address>:8080` (decoded from a screenshot with zxing-cpp) | Pass |
| Screensaver starts by itself after idle | Pass (2-minute setting) |

## All apps

| Scenario | Result |
|---|---|
| Down / Up in every one of the six columns | Pass |
| Left / Right stop at the edges; Up on the first row and Down on the last stay | Pass |
| Glow moves under the selected app and takes its colour | Pass |
| Back closes | Pass |
| Holding OK opens the options menu | Not confirmed: ADB's simulated long press acts as a click on this TV (also on Home's cards); try with the real remote |

## Screensaver and Now playing

A throwaway test app published a silent media session with a song, art and progress, then was uninstalled.

| Scenario | Result |
|---|---|
| Nothing playing | Hidden |
| Song playing | Shown with art, title, artist, progress |
| Progress moves | Pass (6 px in 5.5 s for a 3:20 song) |
| Next song | Title, artist and art change, progress resets |
| Paused | Stays, then fades away after 30 s |
| Resumed | Comes back |
| App closed | Hidden |
| YouTube Music | Never shows: YouTube stops playback when covered and publishes no metadata (YouTube's limit) |

## Phone remote

| Scenario | Result |
|---|---|
| Pairing: wrong code refused, right code gives a token, calls without it get 401 | Pass |
| Arrow keys move focus | Pass (~15 ms per call) |
| Open Netflix; "Now on TV" follows | Pass |
| Home key | Pass |
| YouTube link plays; play/pause pauses (state 3 → 2) | Pass |
| HDMI 1 | Pass |
| Unknown app, unknown key | Refused |
| `javascript:`, `intent:`, `file:`, bare words, `localhost` as links | Refused |
| Sleep timer (1 min) | Pass: TV asleep after 60 s |
| Typing into a search box | Not checked on screen (no safe search box to open); same connection as the keys |

## Recovery

| Scenario | Result |
|---|---|
| Launcher force-stopped, Home opened again | Server back within 2 s; ADB reconnected; first key works |
| Reinstall | Same; the TV does not ask again once "Always allow" was ticked |

## Performance

| Situation | Launcher CPU | Launcher memory (PSS) |
|---|---|---|
| Home, idle | 0.0 to 0.6 % | 54 MB |
| Screensaver with Now playing ticking | 0.6 % | 55 MB |

## Unit tests

`launcher/app/src/test/java/com/gathin/tvhome/IcsTest.java`: UTC and time-zone events, weekly repeats on several
days, excluded and moved occurrences, `COUNT`, all-day and cancelled events.

```sh
cd launcher && ./gradlew testReleaseUnitTest --tests com.gathin.tvhome.IcsTest
```
