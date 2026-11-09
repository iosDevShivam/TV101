package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The phone remote's web server on port 8080: the page (assets/remote/index.html) and a small JSON API under
 * /api. A phone pairs once: it asks for a code, the TV shows four digits, and the right digits earn it a token
 * that every later call carries. Five wrong tries throw the code away.
 */
final class RemoteServer {
    static final int PORT = 8080;
    private static final String TAG = "TvHome.Remote";
    private static final String PREFS = "phone_remote";
    private static final int MAX_BODY = 16 * 1024;

    private final Context ctx;
    private final TvControl tv;
    /** Shows a pairing code on the TV (null hides it). Called on a server thread. */
    private final Consumer<String> showCode;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final LruCache<String, byte[]> art = new LruCache<>(48);
    private ServerSocket server;
    private String code;
    private long codeUntil;
    private int codeTries;

    RemoteServer(Context ctx, TvControl tv, Consumer<String> showCode) {
        this.ctx = ctx.getApplicationContext();
        this.tv = tv;
        this.showCode = showCode;
    }

    private volatile boolean stopped;

    void start() {
        Thread accept = new Thread(() -> {
            // The port can still be held for a moment after Home restarts: keep trying, slower each time.
            long wait = 1000;
            while (!stopped) {
                try (ServerSocket s = new ServerSocket()) {
                    s.setReuseAddress(true);
                    s.bind(new java.net.InetSocketAddress(PORT));
                    server = s;
                    wait = 1000;
                    while (!s.isClosed()) {
                        Socket client = s.accept();
                        pool.execute(() -> serve(client));
                    }
                } catch (IOException | java.util.concurrent.RejectedExecutionException e) {
                    if (stopped) return;
                    Log.w(TAG, "server: " + e.getMessage() + ", retrying");
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    return;
                }
                wait = Math.min(wait * 2, 30_000);
            }
        }, "remote-server");
        accept.setDaemon(true);
        accept.start();
    }

    void stop() {
        stopped = true;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    // ---- HTTP ----

    private static final class Request {
        String method, path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        JSONObject json() {
            try {
                return new JSONObject(new String(body, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                return new JSONObject();
            }
        }
    }

    private void serve(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            Request req = parse(in);
            OutputStream out = s.getOutputStream();
            if (req == null) return;
            route(req, out);
        } catch (Exception e) {
            Log.w(TAG, "request: " + e);
        }
    }

    private static Request parse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Request req = new Request();
        req.method = parts[0];
        String target = parts[1];
        int q = target.indexOf('?');
        req.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) {
            for (String pair : target.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) req.query.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        String h;
        while ((h = readLine(in)) != null && !h.isEmpty()) {
            int colon = h.indexOf(':');
            if (colon > 0) req.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        String length = req.headers.get("content-length");
        if (length != null) {
            int n = Math.min(MAX_BODY, Math.max(0, Integer.parseInt(length.trim())));
            byte[] body = new byte[n];
            int read = 0, r;
            while (read < n && (r = in.read(body, read, n - read)) > 0) read += r;
            req.body = body;
        }
        return req;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 8192) throw new IOException("line too long");
        }
        if (c < 0 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void send(OutputStream out, int status, String type, byte[] body, boolean cache) throws IOException {
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + body.length + "\r\nCache-Control: " + (cache ? "max-age=86400" : "no-store")
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static void json(OutputStream out, int status, JSONObject body) throws IOException {
        send(out, status, "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    private static JSONObject ok() {
        return obj("ok", true);
    }

    private static JSONObject obj(String key, Object value) {
        JSONObject o = new JSONObject();
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
        return o;
    }

    // ---- routes ----

    private void route(Request req, OutputStream out) throws IOException, JSONException {
        String p = req.path;
        if (req.method.equals("GET")) {
            switch (p) {
                case "/":
                case "/index.html":
                    send(out, 200, "text/html; charset=utf-8", asset("remote/index.html"), false);
                    return;
                case "/manifest.webmanifest":
                    send(out, 200, "application/manifest+json", asset("remote/manifest.webmanifest"), true);
                    return;
                case "/icon.png":
                    send(out, 200, "image/png", asset("remote/icon.png"), true);
                    return;
                case "/outfit.ttf":
                    try (InputStream font = ctx.getResources().openRawResource(R.font.outfit)) {
                        send(out, 200, "font/ttf", readAll(font), true);
                    }
                    return;
                case "/api/hello":
                    json(out, 200, new JSONObject().put("name", tv.deviceName()).put("paired", authorized(req)));
                    return;
                default:
                    break;
            }
        }
        if (req.method.equals("POST") && p.equals("/api/pair")) {
            newCode();
            json(out, 200, ok());
            return;
        }
        if (req.method.equals("POST") && p.equals("/api/pair/confirm")) {
            String token = confirm(req.json().optString("code"));
            if (token == null) json(out, 403, obj("error", "wrong code"));
            else json(out, 200, obj("token", token));
            return;
        }
        if (!authorized(req)) {
            json(out, 401, obj("error", "pair first"));
            return;
        }
        if (req.method.equals("GET") && p.startsWith("/art/")) {
            byte[] png = art(p.substring(5));
            if (png == null) send(out, 404, "text/plain", new byte[0], false);
            else send(out, 200, "image/png", png, true);
            return;
        }
        if (req.method.equals("GET") && p.equals("/api/state")) {
            json(out, 200, state());
            return;
        }
        if (req.method.equals("GET") && p.equals("/api/apps")) {
            json(out, 200, apps());
            return;
        }
        if (!req.method.equals("POST")) {
            send(out, 404, "text/plain", new byte[0], false);
            return;
        }
        JSONObject body = req.json();
        boolean done = true;
        switch (p) {
            case "/api/key":
                String key = body.optString("key");
                if (tv.isKey(key)) tv.key(key);
                else done = false;
                break;
            case "/api/text":
                tv.delete(body.optInt("delete", 0));
                tv.type(body.optString("text"));
                if (body.optBoolean("enter")) tv.enter();
                break;
            case "/api/launch":
                done = tv.launch(body.optString("pkg"));
                break;
            case "/api/link":
                done = tv.openLink(body.optString("url"));
                break;
            case "/api/input":
                done = tv.openInput(body.optString("id"), liveTvPackage());
                break;
            case "/api/action":
                switch (body.optString("name")) {
                    case "screensaver":
                        done = tv.screensaver();
                        break;
                    case "clearram":
                        json(out, 200, obj("freed", tv.clearRam()));
                        return;
                    case "sleep":
                        tv.sleepIn(body.optInt("minutes", 0));
                        break;
                    case "power":
                        tv.key("sleep");
                        break;
                    default:
                        done = false;
                }
                break;
            default:
                send(out, 404, "text/plain", new byte[0], false);
                return;
        }
        json(out, done ? 200 : 400, obj("ok", done));
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        return buf.toByteArray();
    }

    // ---- pairing ----

    private synchronized void newCode() {
        code = String.format(Locale.ROOT, "%04d", random.nextInt(10_000));
        codeUntil = System.currentTimeMillis() + 120_000;
        codeTries = 0;
        showCode.accept(code);
    }

    private synchronized String confirm(String attempt) {
        if (code == null || System.currentTimeMillis() > codeUntil) return null;
        if (!code.equals(attempt.trim())) {
            if (++codeTries >= 5) {
                code = null;
                showCode.accept(null);
            }
            return null;
        }
        code = null;
        showCode.accept(null);
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        StringBuilder token = new StringBuilder();
        for (byte b : raw) token.append(String.format(Locale.ROOT, "%02x", b));
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> tokens = new ArrayList<>(prefs.getStringSet("tokens", Collections.emptySet()));
        tokens.add(token.toString());
        // Ten phones is plenty; the oldest pairing goes first.
        while (tokens.size() > 10) tokens.remove(0);
        prefs.edit().putStringSet("tokens", new LinkedHashSet<>(tokens)).apply();
        return token.toString();
    }

    private boolean authorized(Request req) {
        String token = req.headers.get("x-token");
        if (token == null) token = req.query.get("t");
        if (token == null || token.length() != 32) return false;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet("tokens", Collections.emptySet()).contains(token);
    }

    // ---- data ----

    private JSONObject state() throws JSONException {
        String control = tv.controlState();
        if (!control.equals("ok")) tv.warmUp();
        JSONObject o = new JSONObject().put("control", control).put("sleepAt", tv.sleepAt());
        String pkg = tv.foregroundPackage();
        if (pkg != null) {
            boolean home = pkg.equals(ctx.getPackageName());
            o.put("now", new JSONObject().put("pkg", pkg).put("home", home).put("label", home ? "Home" : label(pkg)));
        }
        return o;
    }

    private JSONObject apps() throws JSONException {
        Config cfg = Config.load(ctx);
        PackageManager pm = ctx.getPackageManager();
        Set<String> skip = new HashSet<>(cfg.hiddenApps);
        skip.addAll(cfg.libraryExcludedApps);
        skip.add(ctx.getPackageName());
        Map<String, String> all = new HashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!skip.contains(pkg) && !all.containsKey(pkg)) all.put(pkg, ri.loadLabel(pm).toString());
            }
        }
        JSONArray home = new JSONArray();
        for (String pkg : cfg.dockApps) {
            if (all.containsKey(pkg)) home.put(new JSONObject().put("pkg", pkg).put("label", all.get(pkg)));
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(all.entrySet());
        sorted.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        JSONArray list = new JSONArray();
        for (Map.Entry<String, String> e : sorted) list.put(new JSONObject().put("pkg", e.getKey()).put("label", e.getValue()));
        JSONArray inputs = new JSONArray();
        List<String[]> ports = tv.inputs();
        ports.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
        for (String[] in : ports) {
            inputs.put(new JSONObject().put("id", in[0]).put("label", in[1]).put("connected", in[2].equals("1")));
        }
        return new JSONObject().put("home", home).put("all", list).put("inputs", inputs);
    }

    private String liveTvPackage() {
        try {
            return Config.load(ctx).liveTvPackage;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** The app's TV banner (16:9), or its icon centred on a dark card when it has none; PNG, cached. */
    private byte[] art(String pkg) {
        byte[] cached = art.get(pkg);
        if (cached != null) return cached;
        PackageManager pm = ctx.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            Drawable banner = pm.getApplicationBanner(info);
            if (banner == null) {
                Intent leanback = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (leanback != null) banner = pm.getActivityBanner(leanback);
            }
            Bitmap bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            if (banner != null) {
                banner.setBounds(0, 0, 320, 180);
                banner.draw(c);
            } else {
                c.drawColor(0xFF1C1F27);
                Drawable icon = pm.getApplicationIcon(info);
                icon.setBounds(110, 40, 210, 140);
                icon.draw(c);
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
            bmp.recycle();
            byte[] bytes = png.toByteArray();
            art.put(pkg, bytes);
            return bytes;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
