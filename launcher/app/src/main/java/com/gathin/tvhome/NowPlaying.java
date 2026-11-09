package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * The screensaver's Now playing: the song, artist, album art and progress of whatever app is playing music
 * (YouTube Music, Spotify, ...), from its media session. Event driven: Android calls back when the song or the
 * play state changes; the only timer is the progress bar's, once a second, and only while something plays.
 * It shows as soon as music plays and fades away HIDE_AFTER_MS after it stops.
 *
 * Needs notification access for MediaListener, given over adb once:
 * cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener
 * Without it nothing shows and nothing else changes.
 */
final class NowPlaying {
    private static final long HIDE_AFTER_MS = 30_000L;
    private static final int ART_PX = 160;

    private final Context ctx;
    private final View group;
    private final ImageView art;
    private final TextView title, artist;
    private final ProgressBar progress;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private MediaController controller;
    private String artKey;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = list -> pick(list);

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            show();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            show();
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            main.postDelayed(this, 1000);
        }
    };

    private final Runnable hide = this::fadeOut;

    NowPlaying(View root) {
        ctx = root.getContext();
        group = root.findViewById(R.id.dreamMusic);
        art = root.findViewById(R.id.dreamMusicArt);
        title = root.findViewById(R.id.dreamMusicTitle);
        artist = root.findViewById(R.id.dreamMusicArtist);
        progress = root.findViewById(R.id.dreamMusicProgress);
        art.setClipToOutline(true);
    }

    private void fadeOut() {
        group.animate().alpha(0f).setDuration(800).withEndAction(() -> group.setVisibility(View.GONE)).start();
    }

    void start() {
        if (started) return;
        started = true;
        sessions = ctx.getSystemService(MediaSessionManager.class);
        ComponentName listener = new ComponentName(ctx, MediaListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, listener, main);
            pick(sessions.getActiveSessions(listener));
        } catch (SecurityException noAccess) {
            sessions = null;   // notification access not given: Now playing stays hidden
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacksAndMessages(null);
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
        sessions = null;
    }

    /** Follows the session that is playing, else the first one Android lists. */
    private void pick(List<MediaController> list) {
        if (list == null && sessions != null) {
            try {
                list = sessions.getActiveSessions(new ComponentName(ctx, MediaListener.class));
            } catch (SecurityException e) {
                list = null;
            }
        }
        MediaController next = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    next = c;
                    break;
                }
            }
            if (next == null && !list.isEmpty()) next = list.get(0);
        }
        if (controller != null && (next == null || !next.getSessionToken().equals(controller.getSessionToken()))) {
            controller.unregisterCallback(callback);
            controller = null;
        }
        if (next != null && controller == null) {
            controller = next;
            controller.registerCallback(callback, main);
        }
        show();
    }

    private void show() {
        MediaMetadata md = controller == null ? null : controller.getMetadata();
        PlaybackState st = controller == null ? null : controller.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        String song = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        main.removeCallbacks(tick);
        if (song == null || song.isEmpty()) {
            main.removeCallbacks(hide);
            hide.run();
            return;
        }
        title.setText(song);
        String by = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (by == null) by = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        artist.setText(by == null ? "" : by);
        loadArt(md);
        updateProgress();
        if (playing) {
            main.removeCallbacks(hide);
            main.postDelayed(tick, 1000);
            if (group.getVisibility() != View.VISIBLE || group.getAlpha() < 1f) {
                group.setVisibility(View.VISIBLE);
                group.animate().alpha(1f).setDuration(600).start();
            }
        } else {
            main.removeCallbacks(hide);
            if (group.getVisibility() == View.VISIBLE) main.postDelayed(hide, HIDE_AFTER_MS);
        }
    }

    private void updateProgress() {
        MediaMetadata md = controller == null ? null : controller.getMetadata();
        PlaybackState st = controller == null ? null : controller.getPlaybackState();
        long length = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (st == null || length <= 0) {
            progress.setVisibility(View.INVISIBLE);
            return;
        }
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        progress.setVisibility(View.VISIBLE);
        progress.setProgress((int) (1000L * Math.max(0, Math.min(pos, length)) / length));
    }

    /** The art the session hands over, scaled down once; or its http(s) URI, fetched off the main thread. */
    private void loadArt(MediaMetadata md) {
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (uri == null) uri = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
        if (uri == null) uri = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        String key = md.getString(MediaMetadata.METADATA_KEY_TITLE) + "|" + (bmp != null ? bmp.getGenerationId() : uri);
        if (key.equals(artKey)) return;
        artKey = key;
        if (bmp != null) {
            art.setImageBitmap(Bitmap.createScaledBitmap(bmp, ART_PX, ART_PX * bmp.getHeight() / Math.max(1, bmp.getWidth()), true));
            return;
        }
        art.setImageResource(R.drawable.music_art_placeholder);
        if (uri == null || !uri.startsWith("http")) return;
        String url = uri;
        new Thread(() -> {
            Bitmap fetched = fetch(url);
            if (fetched != null) main.post(() -> {
                if (key.equals(artKey)) art.setImageBitmap(fetched);
            });
        }, "now-playing-art").start();
    }

    private static Bitmap fetch(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeStream(in, null, o);
                if (b == null) return null;
                Bitmap small = Bitmap.createScaledBitmap(b, ART_PX, ART_PX * b.getHeight() / Math.max(1, b.getWidth()), true);
                if (small != b) b.recycle();
                return small;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
