package com.craftstudio.cslauncher.utils.cursor;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The player's own mouse cursor for the game (Java and CS Engine draw it through
 * {@code GameCursorView}). Any still image Android can decode is accepted (PNG, JPG, WEBP, BMP,
 * GIF, HEIF…); it is stored as one PNG under {@code <data>/cursor/} with a hotspot (the pixel that
 * clicks, as a fraction of the image) so the game process reads a single file. Animated cursors
 * are not supported: an animated GIF/WEBP contributes its first frame only.
 */
public final class CursorStore {
    private static final String PREFS = "cs_cursor";
    private static final String K_HOT_X = "hot_x", K_HOT_Y = "hot_y", K_STAMP = "stamp";
    /** The stored image never exceeds this edge (the game scales it down to cursor size anyway). */
    public static final int MAX_EDGE = 256;

    private CursorStore() {}

    @NonNull
    public static File file(@NonNull Context context) {
        String base = Tools.DIR_DATA != null ? Tools.DIR_DATA : context.getFilesDir().getAbsolutePath();
        return new File(base, "cursor/custom.png");
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static boolean hasCustom(@NonNull Context context) { return file(context).isFile(); }

    /** Hotspot X as a fraction 0..1 of the image width (default 0: the top-left corner clicks). */
    public static float hotX(@NonNull Context context) { return prefs(context).getFloat(K_HOT_X, 0f); }
    public static float hotY(@NonNull Context context) { return prefs(context).getFloat(K_HOT_Y, 0f); }

    public static void setHotspot(@NonNull Context context, float x, float y) {
        prefs(context).edit().putFloat(K_HOT_X, clamp(x)).putFloat(K_HOT_Y, clamp(y)).putLong(K_STAMP, System.currentTimeMillis()).apply();
    }

    private static float clamp(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** Changes whenever the image or hotspot changes; the game re-reads when it differs. */
    public static long stamp(@NonNull Context context) { return prefs(context).getLong(K_STAMP, 0L); }

    /**
     * Decodes the picked image, scales it to at most {@link #MAX_EDGE} and stores it. Returns the
     * stored bitmap, or throws with a readable message (not an image, too small, unreadable).
     */
    @NonNull
    public static Bitmap importFrom(@NonNull Context context, @NonNull Uri uri) throws IOException {
        Bitmap decoded = decode(context, uri);
        if (decoded == null) throw new IOException("This file is not an image the device can decode");
        if (decoded.getWidth() < 4 || decoded.getHeight() < 4) throw new IOException("The image is too small for a cursor");
        Bitmap scaled = decoded;
        int w = decoded.getWidth(), h = decoded.getHeight();
        if (w > MAX_EDGE || h > MAX_EDGE) {
            float f = Math.min(MAX_EDGE / (float) w, MAX_EDGE / (float) h);
            scaled = Bitmap.createScaledBitmap(decoded, Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)), true);
        }
        File out = file(context);
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        File tmp = new File(dir, "custom.png.tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, fos)) throw new IOException("Could not write the cursor image");
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(out)) {
            if (out.exists() && !out.delete()) throw new IOException("Could not replace the previous cursor");
            if (!tmp.renameTo(out)) throw new IOException("Could not store the cursor image");
        }
        prefs(context).edit().putLong(K_STAMP, System.currentTimeMillis()).apply();
        return scaled;
    }

    @Nullable
    private static Bitmap decode(Context context, Uri uri) throws IOException {
        // Two passes: bounds first so a huge photo is subsampled instead of exhausting memory
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("The file could not be opened");
        try { BitmapFactory.decodeStream(in, null, bounds); } finally { in.close(); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= MAX_EDGE * 2 && bounds.outHeight / (opts.inSampleSize * 2) >= MAX_EDGE * 2) {
            opts.inSampleSize *= 2;
        }
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("The file could not be opened");
        try { return BitmapFactory.decodeStream(in, null, opts); } finally { in.close(); }
    }

    /** The stored cursor image, or null when the default pointer is in use. */
    @Nullable
    public static Bitmap load(@NonNull Context context) {
        File f = file(context);
        if (!f.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Back to the launcher's own pointer. */
    public static void reset(@NonNull Context context) {
        File f = file(context);
        if (f.exists() && !f.delete()) f.deleteOnExit();
        prefs(context).edit().remove(K_HOT_X).remove(K_HOT_Y).putLong(K_STAMP, System.currentTimeMillis()).apply();
    }
}
