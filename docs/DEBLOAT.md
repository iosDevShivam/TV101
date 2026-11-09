# Debloat

Most TVs come with extra apps you never use: feedback and telemetry agents, the maker's assistant, file browsers,
e-manuals, game stores, and the stock home screen's recommendations. Turning them off frees memory and stops
background work.

TV101 itself doesn't remove anything. Use [cleanyourtv](https://github.com/Gathin23/cleanyourtv): it has ready-made,
tested lists for many TV models in its `examples/` folder, and it **disables** apps rather than uninstalling them, so
every change can be undone.

```sh
# with cleanyourtv, pick the list for your TV from its examples/ folder
TV_HOST=<tv> tools/disable.sh examples/<your-tv>/debloat.txt   # turn them off
TV_HOST=<tv> tools/restore.sh examples/<your-tv>/debloat.txt   # turn them back on
```

## What is usually safe to turn off

| Kind | Examples |
|---|---|
| Telemetry | `com.google.android.feedback` |
| Stock home's ads and recommendations | `com.google.android.tvrecommendations` |
| Stock home, once TV101 is Home | `com.google.android.tvlauncher` |
| Maker extras | phone-link, smart-home, assistant, file browser, e-manual apps |
| Preloaded stores and games | `com.google.android.play.games` |

**Keep:** Google Assistant, Chromecast, Play Store and Play Services (they also deliver system updates), the Live TV
app (HDMI inputs go through it), and anything with "factory" or "update" in its name.

Apps you only want out of sight can stay installed: list them under `hidden_apps` in your theme instead.

## Undo

```sh
adb shell pm enable <package>      # one app
```

Re-enable `com.google.android.tvlauncher` before uninstalling TV101, so the TV has a Home to go to.
