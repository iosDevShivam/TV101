package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The lock screen's weather and next-event pills (dream_clock). Weather comes from Open-Meteo (no account) for
 * "weather_city" in the theme, else config.xml's latitude and longitude, else where the internet connection says
 * the TV is. The event comes from the private iCal link in "calendar_ics_url". Both are cached, so the screensaver
 * shows the last ones at once and fetches again in the background: weather every 30 minutes, the calendar every 15.
 */
final class LockInfo {
    private static final String TAG = "TvHome.LockInfo";
    private static final String PREFS = "lock_info";
    private static final long WEATHER_EVERY = 30 * 60_000L, CALENDAR_EVERY = 15 * 60_000L, PLACE_EVERY = 24 * 3600_000L;
    /** Older than this, a reading is not shown at all. */
    private static final long WEATHER_STALE = 3 * 3600_000L;
    /** How far ahead an event counts as "next": the rest of today, and tomorrow morning once it is evening. */
    private static final long LOOK_AHEAD = 16 * 3600_000L;

    private final Context ctx;
    private final SharedPreferences prefs;
    private volatile boolean fetching;

    LockInfo(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Fetches whatever is due, off the main thread, then runs done on the view's thread. */
    void refresh(View anyView, Runnable done) {
        if (fetching) return;
        fetching = true;
        new Thread(() -> {
            boolean changed = false;
            try {
                changed |= fetchWeather();
                changed |= fetchCalendar();
            } finally {
                fetching = false;
            }
            if (changed) anyView.post(done);
        }, "lock-info").start();
    }

    /** Fills the screensaver's weather (dream_clock) from the cache. */
    void bind(View root) {
        bindWeather(root);
    }

    /** Home's dashboard column: "35°  Clear" with its icon, and the next event under "Up next". */
    void bindHome(View root) {
        View weather = root.findViewById(R.id.homeWeather);
        if (weather != null) {
            boolean on = weatherFresh();
            if (on) {
                int code = prefs.getInt("code", 0);
                ((ImageView) root.findViewById(R.id.homeWeatherIcon)).setImageResource(icon(code, prefs.getBoolean("day", true)));
                ((TextView) root.findViewById(R.id.homeWeatherText)).setText(prefs.getInt("temp", 0) + "°  " + sky(code));
            }
            weather.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        View event = root.findViewById(R.id.homeEvent);
        if (event != null) {
            JSONObject next = nextEvent(System.currentTimeMillis());
            if (next != null) {
                ((TextView) root.findViewById(R.id.homeEventTitle)).setText(next.optString("t"));
                ((TextView) root.findViewById(R.id.homeEventTime)).setText(next.optString("when"));
            }
            event.setVisibility(next != null ? View.VISIBLE : View.GONE);
        }
    }

    private boolean weatherFresh() {
        long at = prefs.getLong("weather_at", 0);
        return weatherOn() && at != 0 && System.currentTimeMillis() - at <= WEATHER_STALE;
    }

    // ---- weather ----

    private boolean weatherOn() {
        return Config.overlayBool(ctx, "lock_weather", ctx.getResources().getBoolean(R.bool.lock_weather));
    }

    private boolean fetchWeather() {
        if (!weatherOn()) return false;
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("weather_at", 0) < WEATHER_EVERY) return false;
        double[] place = place();
        if (place == null) return false;
        try {
            String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                    + "&timezone=auto&forecast_days=1", place[0], place[1]);
            JSONObject o = new JSONObject(get(url));
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            prefs.edit()
                    .putInt("temp", (int) Math.round(cur.getDouble("temperature_2m")))
                    .putInt("code", cur.getInt("weather_code"))
                    .putBoolean("day", cur.optInt("is_day", 1) == 1)
                    .putInt("hi", (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))
                    .putInt("lo", (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)))
                    .putLong("weather_at", now)
                    .apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "weather: " + e.getMessage());
            return false;
        }
    }

    /** Latitude and longitude: the theme's city, config.xml's coordinates, or the connection's location. */
    private double[] place() {
        String city = Config.overlayString(ctx, "weather_city", "");
        String lat = ctx.getString(R.string.latitude).trim(), lon = ctx.getString(R.string.longitude).trim();
        String key = !city.isEmpty() ? "city:" + city : !lat.isEmpty() ? "xml:" + lat + "," + lon : "ip";
        long now = System.currentTimeMillis();
        if (key.equals(prefs.getString("place_key", "")) && now - prefs.getLong("place_at", 0) < PLACE_EVERY) {
            return new double[]{Double.longBitsToDouble(prefs.getLong("lat", 0)), Double.longBitsToDouble(prefs.getLong("lon", 0))};
        }
        double[] found = null;
        try {
            if (!city.isEmpty()) {
                JSONArray results = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                        + URLEncoder.encode(city, "UTF-8"))).optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject r = results.getJSONObject(0);
                    found = new double[]{r.getDouble("latitude"), r.getDouble("longitude")};
                }
            } else if (!lat.isEmpty() && !lon.isEmpty()) {
                found = new double[]{Double.parseDouble(lat), Double.parseDouble(lon)};
            } else {
                JSONObject r = new JSONObject(get("https://ipwho.is/"));
                if (r.optBoolean("success")) found = new double[]{r.getDouble("latitude"), r.getDouble("longitude")};
            }
        } catch (Exception e) {
            Log.w(TAG, "location: " + e.getMessage());
        }
        if (found == null) {
            // Keep using the last place rather than showing nothing.
            return prefs.contains("lat") ? new double[]{Double.longBitsToDouble(prefs.getLong("lat", 0)),
                    Double.longBitsToDouble(prefs.getLong("lon", 0))} : null;
        }
        prefs.edit().putString("place_key", key).putLong("place_at", now)
                .putLong("lat", Double.doubleToLongBits(found[0])).putLong("lon", Double.doubleToLongBits(found[1]))
                .apply();
        return found;
    }

    private void bindWeather(View root) {
        View pill = root.findViewById(R.id.dreamWeather);
        if (pill == null) return;
        if (!weatherFresh()) {
            pill.setVisibility(View.GONE);
            return;
        }
        int code = prefs.getInt("code", 0);
        boolean day = prefs.getBoolean("day", true);
        ((ImageView) root.findViewById(R.id.dreamWeatherIcon)).setImageResource(icon(code, day));
        ((TextView) root.findViewById(R.id.dreamTemp)).setText(prefs.getInt("temp", 0) + "°");
        ((TextView) root.findViewById(R.id.dreamWeatherText)).setText(sky(code));
        pill.setVisibility(View.VISIBLE);
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    private static String sky(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    private static int icon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.wx_sun : R.drawable.wx_moon;
        if (code == 2) return day ? R.drawable.wx_partly : R.drawable.wx_cloud;
        if (code == 3) return R.drawable.wx_cloud;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.wx_snow;
        if (code >= 95) return R.drawable.wx_storm;
        return R.drawable.wx_rain;
    }

    // ---- calendar ----

    private String calendarUrl() {
        String url = Config.overlayString(ctx, "calendar_ics_url", "");
        if (url.startsWith("webcal://")) url = "https://" + url.substring(9);
        return url;
    }

    private boolean fetchCalendar() {
        String url = calendarUrl();
        if (url.isEmpty()) {
            boolean had = prefs.contains("events");
            if (had) prefs.edit().remove("events").remove("events_at").apply();
            return had;
        }
        long now = System.currentTimeMillis();
        if (url.equals(prefs.getString("events_url", "")) && now - prefs.getLong("events_at", 0) < CALENDAR_EVERY) return false;
        try {
            String text = get(url);
            // Keep the next two days' occurrences; the screensaver picks from these every minute.
            List<Ics.Event> events = Ics.events(text, now, now + 2 * 86_400_000L, ZoneId.systemDefault());
            JSONArray list = new JSONArray();
            for (Ics.Event e : events) {
                list.put(new JSONObject().put("s", e.start).put("e", e.end).put("d", e.allDay).put("t", e.title));
            }
            prefs.edit().putString("events", list.toString()).putLong("events_at", now).putString("events_url", url).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "calendar: " + e.getMessage());
            return false;
        }
    }


    /**
     * The event to show: one going on now, else the next to start within LOOK_AHEAD; an all-day event of today only
     * when no timed one is coming. Its "when" is "Now", "6:30 PM", "Tomorrow 9:00 AM" or "Today".
     */
    private JSONObject nextEvent(long now) {
        if (calendarUrl().isEmpty()) return null;
        try {
            JSONArray list = new JSONArray(prefs.getString("events", "[]"));
            ZoneId zone = ZoneId.systemDefault();
            JSONObject allDay = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.getJSONObject(i);
                long s = e.getLong("s"), end = e.getLong("e");
                if (end <= now) continue;
                if (e.optBoolean("d")) {
                    if (allDay == null && Ics.daysFrom(now, s, zone) <= 0) allDay = e;
                    continue;
                }
                if (s <= now) return e.put("when", ctx.getString(R.string.event_now));
                if (s - now > LOOK_AHEAD) break;
                String time = time(s, zone);
                if (Ics.daysFrom(now, s, zone) >= 1) time = ctx.getString(R.string.event_tomorrow) + " " + time;
                return e.put("when", time);
            }
            return allDay == null ? null : allDay.put("when", ctx.getString(R.string.event_today));
        } catch (Exception e) {
            return null;
        }
    }

    private String time(long at, ZoneId zone) {
        String pattern = DateFormat.is24HourFormat(ctx) ? "H:mm" : "h:mm a";
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.ofEpochMilli(at).atZone(zone));
    }

    // ---- network ----

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "tvhome-launcher");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                    if (buf.size() > 8 * 1024 * 1024) throw new IOException("too large");
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
