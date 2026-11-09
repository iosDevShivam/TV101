package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Keeps the phone remote's server (RemoteServer) running while other apps are on screen. A foreground service, so
 * Android does not reclaim it under memory pressure; Home starts it (MainActivity.onCreate) unless the theme sets
 * "phone_remote": false. The pairing code shows as a small card in the corner of whatever is playing.
 */
public final class RemoteService extends Service {
    private static final String CHANNEL = "phone_remote";
    private static final long CODE_MS = 120_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TvControl tv;
    private RemoteServer server;
    private View codeCard;

    static void start(Context ctx) {
        try {
            Intent intent = new Intent(ctx, RemoteService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent);
            else ctx.startService(intent);
        } catch (RuntimeException ignored) {
            // Not allowed right now (started from the background on a newer Android): the next Home tries again.
        }
    }

    static boolean enabled(Context ctx) {
        return Config.overlayBool(ctx, "phone_remote", ctx.getResources().getBoolean(R.bool.phone_remote));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.phone_remote),
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast)
                .setContentTitle(getString(R.string.phone_remote))
                .build();
        startForeground(41, n);
        tv = new TvControl(this);
        server = new RemoteServer(this, tv, code -> main.post(() -> showCode(code)));
        server.start();
        tv.warmUp();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        showCode(null);
        if (server != null) server.stop();
        if (tv != null) tv.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable hideCode = () -> showCode(null);

    /** The pairing code in a card at the top right, over whatever is on screen; null takes it away. */
    private void showCode(String code) {
        WindowManager wm = getSystemService(WindowManager.class);
        if (codeCard != null) {
            try {
                wm.removeView(codeCard);
            } catch (RuntimeException ignored) {
            }
            codeCard = null;
        }
        main.removeCallbacks(hideCode);
        if (code == null) return;
        String spaced = code.replace("", " ").trim();
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.phone_remote_code, spaced), Toast.LENGTH_LONG).show();
            return;
        }
        View card = LayoutInflater.from(this).inflate(R.layout.remote_code, null);
        ((TextView) card.findViewById(R.id.remoteCode)).setText(spaced);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        float d = getResources().getDisplayMetrics().density;
        lp.x = (int) (44 * d);
        lp.y = (int) (40 * d);
        try {
            wm.addView(card, lp);
            codeCard = card;
            main.postDelayed(hideCode, CODE_MS);
        } catch (RuntimeException e) {
            Toast.makeText(this, getString(R.string.phone_remote_code, spaced), Toast.LENGTH_LONG).show();
        }
    }
}
