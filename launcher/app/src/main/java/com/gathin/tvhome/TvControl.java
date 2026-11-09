package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the phone remote can do to the TV. Keys go through a virtual remote control: the shell's `hid` tool
 * registers a consumer-control HID device and stays running, so a key press is one report and arrives at once
 * (`input keyevent` starts a Java process each time, close to two seconds on this TV's CPU). Text and the app on
 * screen need the shell as well (see Adb); opening apps, links and inputs the launcher does itself, which the
 * SYSTEM_ALERT_WINDOW grant allows from the background.
 */
final class TvControl {
    private static final String TAG = "TvHome.Remote";
    /** HID consumer-page usages; Linux maps them to KEY_SELECT, KEY_UP, ... and Generic.kl to the Android keys. */
    private static final Map<String, Integer> USAGES = new HashMap<>();
    static {
        USAGES.put("ok", 0x41);
        USAGES.put("up", 0x42);
        USAGES.put("down", 0x43);
        USAGES.put("left", 0x44);
        USAGES.put("right", 0x45);
        USAGES.put("home", 0x223);
        USAGES.put("back", 0x224);
        USAGES.put("playpause", 0xCD);
        USAGES.put("volup", 0xE9);
        USAGES.put("voldown", 0xEA);
        USAGES.put("mute", 0xE2);
        USAGES.put("sleep", 0x32);
    }
    /** The same keys as Android key codes, for `input keyevent` while the virtual remote is not there. */
    private static final Map<String, Integer> KEYCODES = new HashMap<>();
    static {
        KEYCODES.put("ok", 23);
        KEYCODES.put("up", 19);
        KEYCODES.put("down", 20);
        KEYCODES.put("left", 21);
        KEYCODES.put("right", 22);
        KEYCODES.put("home", 3);
        KEYCODES.put("back", 4);
        KEYCODES.put("playpause", 85);
        KEYCODES.put("volup", 24);
        KEYCODES.put("voldown", 25);
        KEYCODES.put("mute", 164);
        KEYCODES.put("sleep", 223);
    }
    /** A HID report descriptor: one 16-bit consumer usage per report. */
    private static final String DESCRIPTOR = "[5,12,9,1,161,1,21,0,38,255,3,25,0,42,255,3,117,16,149,1,129,0,192]";
    private static final Pattern YOUTUBE = Pattern.compile(
            "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})");
    private static final String YOUTUBE_TV = "com.google.android.youtube.tv";

    private final Context ctx;
    private final Adb adb;
    /** One thread for everything that talks to adb, in the order it was asked. */
    private final ExecutorService shell = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Adb.Stream hid;
    /** A sign-in is queued or waiting for Allow on the TV; warmUp does not queue a second one. */
    private final AtomicBoolean connecting = new AtomicBoolean();
    private long sleepAt;
    private final Runnable sleepNow = () -> {
        sleepAt = 0;
        key("sleep");
    };

    TvControl(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        adb = new Adb(this.ctx.getFilesDir());
    }

    /**
     * Connects in the background; the first time, the TV asks to allow it. The phone page calls this whenever it
     * finds the keys off, so a prompt that timed out comes back the next time the page is opened.
     */
    void warmUp() {
        if (adb.connected() || !connecting.compareAndSet(false, true)) return;
        shell.execute(() -> {
            try {
                ensureShell();
                // Register the virtual remote now, so the phone's first key press does not wait on it (and get
                // dropped while Android is still adding the new device).
                ensureHid();
            } catch (IOException e) {
                Log.i(TAG, "adb not available yet: " + e.getMessage());
            } finally {
                connecting.set(false);
            }
        });
    }

    /** "ok", "allow" (the TV is showing the allow prompt) or "off" (network debugging is off). */
    String controlState() {
        switch (adb.state()) {
            case CONNECTED: return "ok";
            case WAITING_FOR_ALLOW: return "allow";
            default: return "off";
        }
    }

    void shutdown() {
        main.removeCallbacks(sleepNow);
        shell.shutdownNow();
        adb.close();
    }

    private void ensureShell() throws IOException {
        if (adb.connected()) return;
        adb.connect(120_000);
        // What the remote needs from the shell once: opening apps from the background, and seeing the app on screen.
        String pkg = ctx.getPackageName();
        adb.exec("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; appops set " + pkg + " GET_USAGE_STATS allow", 5000);
    }

    private void ensureHid() throws IOException {
        if (hid != null && hid.isOpen()) return;
        ensureShell();
        hid = adb.open("exec:hid -");
        hid.write(("{\"id\":1,\"command\":\"register\",\"name\":\"Phone Remote\",\"vid\":4617,\"pid\":30292,"
                + "\"bus\":\"usb\",\"descriptor\":" + DESCRIPTOR + "}\n").getBytes(StandardCharsets.UTF_8));
        // Android needs a moment to add the new device before its first key counts (about 0.7 s on this TV).
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }
    }

    boolean isKey(String name) {
        return USAGES.containsKey(name);
    }

    void key(String name) {
        Integer usage = USAGES.get(name);
        if (usage == null) return;
        shell.execute(() -> {
            String down = "{\"id\":1,\"command\":\"report\",\"report\":[" + (usage & 0xFF) + "," + (usage >> 8) + "]}\n";
            String up = "{\"id\":1,\"command\":\"report\",\"report\":[0,0]}\n";
            try {
                ensureHid();
                hid.write((down + up).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "virtual remote: " + e.getMessage());
                if (hid != null) hid.close();
                hid = null;
                runShell("input keyevent " + KEYCODES.get(name));
            }
        });
    }

    /** Types into the field that has focus on the TV. */
    void type(String text) {
        if (text.isEmpty()) return;
        // `input text` reads %s as a space; everything else goes through single quotes untouched.
        String arg = text.replace("%", "\\%").replace(" ", "%s").replace("'", "'\\''");
        runShell("input text '" + arg + "'");
    }

    void delete(int count) {
        if (count <= 0) return;
        StringBuilder cmd = new StringBuilder("input keyevent");
        for (int i = 0; i < Math.min(count, 200); i++) cmd.append(" 67");
        runShell(cmd.toString());
    }

    void enter() {
        runShell("input keyevent 66");
    }

    private void runShell(String command) {
        shell.execute(() -> {
            try {
                ensureShell();
                adb.exec(command, 10_000);
            } catch (IOException e) {
                Log.w(TAG, "shell: " + e.getMessage());
            }
        });
    }

    /** Opens an installed app's TV start screen (or its phone one). */
    boolean launch(String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        return intent != null && start(intent);
    }

    /** A YouTube link plays in YouTube; any other http(s) link goes to whatever opens it. */
    boolean openLink(String url) {
        url = url.trim();
        // Only web links: another scheme ("javascript:", "intent:", "file:") is refused, and a bare
        // "youtu.be/..." gets https:// in front.
        if (url.matches("(?i)[a-z][a-z0-9+.-]*:.*") && !url.matches("(?i)https?://.+")) return false;
        if (!url.matches("(?i)https?://.+")) url = "https://" + url;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null || !host.contains(".") || url.matches(".*\\s.*")) return false;
        Matcher yt = YOUTUBE.matcher(url);
        if (yt.find()) {
            Intent watch = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + yt.group(1)));
            if (start(new Intent(watch).setPackage(YOUTUBE_TV))) return true;
            return start(watch);
        }
        return start(new Intent(Intent.ACTION_VIEW, uri));
    }

    boolean screensaver() {
        return start(new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui",
                "com.android.systemui.Somnambulator")) || start(new Intent(ctx, ClockActivity.class));
    }

    /** HDMI and the other passthrough inputs, by the standard channel URI that the Live TV app opens. */
    boolean openInput(String inputId, String liveTvPkg) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(
                TvContract.buildChannelUriForPassthroughInput(inputId), "vnd.android.cursor.item/channel");
        if (!liveTvPkg.isEmpty() && start(new Intent(view).setPackage(liveTvPkg))) return true;
        return start(view);
    }

    private boolean start(Intent intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** Clear RAM, as on Home: stops other apps' background processes. Returns the MB that came back. */
    long clearRam() {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        for (ApplicationInfo app : ctx.getPackageManager().getInstalledApplications(0)) {
            if (!app.packageName.equals(ctx.getPackageName())) am.killBackgroundProcesses(app.packageName);
        }
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
        }
        ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(after);
        return Math.max(0, after.availMem - before.availMem) / (1024 * 1024);
    }

    /** Puts the TV to sleep after minutes; 0 cancels. */
    void sleepIn(int minutes) {
        main.removeCallbacks(sleepNow);
        if (minutes <= 0) {
            sleepAt = 0;
            return;
        }
        sleepAt = System.currentTimeMillis() + minutes * 60_000L;
        main.postDelayed(sleepNow, minutes * 60_000L);
    }

    long sleepAt() {
        return sleepAt;
    }

    /** The app in front, from usage events (GET_USAGE_STATS, granted through adb); null when unknown. */
    String foregroundPackage() {
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm == null) return null;
        long now = System.currentTimeMillis();
        try {
            UsageEvents events = usm.queryEvents(now - 6 * 3600_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
            }
            return last;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** HDMI ports and other passthrough inputs, with their names and whether something is plugged in. */
    List<String[]> inputs() {
        List<String[]> list = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return list;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.getParentId() != null) continue;
                if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
                CharSequence label = info.loadLabel(ctx);
                boolean connected = tim.getInputState(info.getId()) != TvInputManager.INPUT_STATE_DISCONNECTED;
                list.add(new String[]{info.getId(), label == null ? "HDMI" : label.toString(), connected ? "1" : "0"});
            }
        } catch (RuntimeException ignored) {
        }
        return list;
    }

    /** The TV's own name (Settings > Device > About), for the page's title. */
    String deviceName() {
        String name = Settings.Global.getString(ctx.getContentResolver(), "device_name");
        return name == null || name.trim().isEmpty() ? "TV" : name.trim();
    }
}
