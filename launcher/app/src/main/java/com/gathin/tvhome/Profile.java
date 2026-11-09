package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;

/**
 * The owner's picture and name on Home and the screensaver. Both come from the theme, so nothing personal is built
 * into the app: "lock_name" in config.json, and avatar.jpg next to it (pushed by scripts/push-theme.sh). Without
 * them the app shows its neutral placeholder picture and no name.
 */
final class Profile {
    static final String AVATAR = "avatar.jpg";
    /** The longest side the picture is decoded to: a 94 dp circle at most, so 256 px is plenty. */
    private static final int MAX_PX = 256;

    private static String loadedStamp;
    private static Bitmap loaded;

    private Profile() {
    }

    /** Puts the theme's picture into avatar (keeping the placeholder when there is none). */
    static void showAvatar(ImageView avatar) {
        if (avatar == null) return;
        Bitmap picture = picture(avatar.getContext());
        if (picture != null) avatar.setImageBitmap(picture);
    }

    /** The theme's "lock_name", or config.xml's; the view is hidden when both are empty. */
    static void showName(TextView name) {
        if (name == null) return;
        Context ctx = name.getContext();
        String value = Config.overlayString(ctx, "lock_name", ctx.getString(R.string.lock_name));
        name.setText(value);
        name.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** avatar.jpg from the theme folder, decoded small once and again only when the file changes. */
    private static synchronized Bitmap picture(Context ctx) {
        File dir = Config.dir(ctx);
        File file = dir == null ? null : new File(dir, AVATAR);
        if (file == null || !file.isFile()) return null;
        String stamp = file.lastModified() + ":" + file.length();
        if (stamp.equals(loadedStamp)) return loaded;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        loaded = BitmapFactory.decodeFile(file.getPath(), options);
        loadedStamp = stamp;
        return loaded;
    }
}
