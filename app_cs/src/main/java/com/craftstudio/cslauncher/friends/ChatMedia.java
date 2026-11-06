package com.craftstudio.cslauncher.friends;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photos in CS Friends chat: screenshots and gallery pictures are scaled to a JPEG (longest side
 * 1280 px, ≤ ~700 KB), uploaded to the public {@code cs-chat} bucket of the friends backend and
 * sent as the message's image URL. Received images are fetched once and cached in memory.
 */
public final class ChatMedia {
    private static final String TAG = "ChatMedia";
    private static final String BUCKET_UPLOAD = FriendsApi.BASE + "/storage/v1/object/cs-chat/";
    private static final String BUCKET_PUBLIC = FriendsApi.BASE + "/storage/v1/object/public/cs-chat/";
    private static final int MAX_SIDE = 1280;
    private static final int MAX_BYTES = 700 * 1024;

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "cs-chat-media");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<String>());

    private ChatMedia() {}

    public interface Callback { void done(@Nullable String url, @Nullable String error); }

    // ------------------------------------------------------------------ send

    /** Scales + uploads a picture from a content/file Uri; the callback runs on the main thread. */
    public static void send(@NonNull Context context, @NonNull Uri uri, @NonNull Callback cb) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                byte[] jpeg = toJpeg(app, uri);
                if (jpeg == null) { post(cb, null, "unreadable"); return; }
                String url = upload(jpeg);
                post(cb, url, null);
            } catch (IOException e) {
                Log.w(TAG, "send failed: " + e.getMessage());
                post(cb, null, e.getMessage());
            }
        });
    }

    /** Same for a file on disk (screenshots). */
    public static void send(@NonNull File file, @NonNull Callback cb) {
        IO.execute(() -> {
            try {
                byte[] jpeg = toJpeg(file);
                if (jpeg == null) { post(cb, null, "unreadable"); return; }
                post(cb, upload(jpeg), null);
            } catch (IOException e) {
                Log.w(TAG, "send failed: " + e.getMessage());
                post(cb, null, e.getMessage());
            }
        });
    }

    private static void post(Callback cb, String url, String error) { MAIN.post(() -> cb.done(url, error)); }

    @NonNull
    private static String upload(@NonNull byte[] jpeg) throws IOException {
        String me = CsAccount.id();
        if (me == null || me.isEmpty()) me = "anon";
        String name = me + "/" + System.currentTimeMillis() + "-" + Integer.toHexString((int) (Math.random() * 0x7fffffff)) + ".jpg";
        HttpURLConnection c = (HttpURLConnection) new URL(BUCKET_UPLOAD + name).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(jpeg.length);
        c.setRequestProperty("apikey", FriendsApi.ANON_KEY);
        c.setRequestProperty("Authorization", "Bearer " + FriendsApi.ANON_KEY);
        c.setRequestProperty("Content-Type", "image/jpeg");
        c.setRequestProperty("Cache-Control", "public, max-age=31536000");
        try (OutputStream out = c.getOutputStream()) { out.write(jpeg); }
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String body = "";
            try (InputStream err = c.getErrorStream()) { if (err != null) body = new String(readAll(err), "UTF-8"); } catch (IOException ignored) {}
            throw new IOException("upload " + code + (body.isEmpty() ? "" : ": " + body));
        }
        c.disconnect();
        return BUCKET_PUBLIC + name;
    }

    // ------------------------------------------------------------------ JPEG preparation

    @Nullable
    private static byte[] toJpeg(@NonNull Context context, @NonNull Uri uri) throws IOException {
        byte[] raw;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            raw = readAll(in);
        }
        return encode(raw);
    }

    @Nullable
    private static byte[] toJpeg(@NonNull File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return encode(readAll(in));
        }
    }

    @Nullable
    private static byte[] encode(@NonNull byte[] raw) throws IOException {
        if (raw.length > 40 * 1024 * 1024) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (opts.inSampleSize * 2) >= MAX_SIDE) opts.inSampleSize *= 2;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        if (bmp == null) return null;
        // Exact fit + EXIF orientation (photos from the camera)
        int w = bmp.getWidth(), h = bmp.getHeight();
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));
        int rotation = exifRotation(raw);
        if (scale < 1f || rotation != 0) {
            Matrix m = new Matrix();
            if (scale < 1f) m.postScale(scale, scale);
            if (rotation != 0) m.postRotate(rotation);
            Bitmap t = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true);
            if (t != bmp) { bmp.recycle(); bmp = t; }
        }
        int quality = 86;
        byte[] out;
        do {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            out = bos.toByteArray();
            quality -= 12;
        } while (out.length > MAX_BYTES && quality >= 40);
        bmp.recycle();
        return out;
    }

    private static int exifRotation(byte[] raw) {
        if (Build.VERSION.SDK_INT < 24) return 0;
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(new ByteArrayInputStream(raw));
            switch (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ screenshots

    /** Newest screenshots of every profile (Java {@code screenshots/} and the CS Engine folder), newest first. */
    @NonNull
    public static List<File> screenshots(int max) {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (Instance i : Instances.loadAllInstances()) {
                File dir = i.isHorizon()
                        ? new File(com.craftstudio.cslauncher.engine.horizon.HorizonProfile.gameHome(i), "screenshots")
                        : new File(i.getGameDirectory(), "screenshots");
                collect(dir, out, seen);
            }
        } catch (IOException ignored) {}
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private static void collect(File dir, List<File> out, Set<String> seen) {
        if (!seen.add(dir.getAbsolutePath())) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && f.length() > 0 && (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg"))) out.add(f);
        }
    }

    /** Small thumbnail for the picker grid (decoded with sub-sampling). */
    @Nullable
    public static Bitmap thumbnail(@NonNull File f, int sidePx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (opts.inSampleSize * 2) >= sidePx) opts.inSampleSize *= 2;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    public interface ThumbCallback { void done(@Nullable Bitmap bitmap); }

    public static void thumbnailAsync(@NonNull File f, int sidePx, @NonNull ThumbCallback cb) {
        IO.execute(() -> {
            Bitmap b = thumbnail(f, sidePx);
            MAIN.post(() -> cb.done(b));
        });
    }

    // ------------------------------------------------------------------ receive

    public interface ImageCallback { void done(@Nullable Bitmap bitmap); }

    /** Cached fetch of a chat image; callback on the main thread (null when it cannot be loaded). */
    public static void load(@NonNull String url, @NonNull ImageCallback cb) {
        Bitmap hit = CACHE.get(url);
        if (hit != null) { cb.done(hit); return; }
        if (FAILED.contains(url)) { cb.done(null); return; }
        IO.execute(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("apikey", FriendsApi.ANON_KEY);
                try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                    byte[] raw = readAll(in);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    b = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                }
                c.disconnect();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "load " + url + ": " + e.getMessage());
            }
            if (b != null) CACHE.put(url, b); else FAILED.add(url);
            final Bitmap fb = b;
            MAIN.post(() -> cb.done(fb));
        });
    }

    /** Binds a chat image into a view once it is available (no-op if the view was reused). */
    public static void into(@NonNull String url, @NonNull ImageView view) {
        view.setTag(url);
        load(url, bmp -> {
            if (!url.equals(view.getTag())) return;
            if (bmp != null) view.setImageBitmap(bmp);
        });
    }

    /** Saves a received photo to Pictures/CS Launcher (MediaStore on Android 10+). */
    public static void save(@NonNull Context context, @NonNull String url, @NonNull Callback cb) {
        final Context app = context.getApplicationContext();
        load(url, bmp -> {
            if (bmp == null) { cb.done(null, "unreadable"); return; }
            IO.execute(() -> {
                try {
                    String name = "cs-chat-" + System.currentTimeMillis() + ".jpg";
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CS Launcher");
                        Uri item = app.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                        if (item == null) throw new IOException("insert failed");
                        try (OutputStream out = app.getContentResolver().openOutputStream(item)) {
                            if (out == null) throw new IOException("open failed");
                            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
                        }
                    } else {
                        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CS Launcher");
                        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdirs");
                        try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
                        }
                    }
                    post(cb, url, null);
                } catch (IOException | RuntimeException e) {
                    post(cb, null, e.getMessage());
                }
            });
        });
    }
}
