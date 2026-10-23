package com.craftstudio.cslauncher.discord;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.InstanceIconProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Discord can only show images that live behind a public https URL, while a profile icon is a
 * local file (custom picture) or a built-in drawable. This publisher renders the icon exactly as
 * the profile list shows it, hashes the PNG and uploads it once to a public, write-once bucket
 * (key = SHA-1 of the bytes, so the same icon is never uploaded twice and nothing can be
 * overwritten). The resulting URL is cached on disk and reused by both launcher and game process.
 */
final class DiscordProfileIconPublisher {
    private static final String TAG = "DiscordIcon";

    private static final String STORAGE_BASE = "https://ygteavpkursyeyfthlxm.supabase.co/storage/v1/object/";
    private static final String BUCKET = "presence-icons";
    private static final String ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlndGVhdnBrdXJzeWV5ZnRobHhtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI2ODk5NjksImV4cCI6MjEwODI2NTk2OX0."
            + "wqCioElyk_GlIkeaiRHlls7hk-WDABhigwb1EGy8iJU";
    private static final int ICON_SIZE = 256;

    private final Context mContext;
    private final File mCacheFile;
    private final Map<String, String> mUrlBySha = new HashMap<>();
    private final Set<String> mFailedSha = new HashSet<>();
    private boolean mCacheLoaded;

    DiscordProfileIconPublisher(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mCacheFile = new File(mContext.getFilesDir(), "discord_presence_icons.json");
    }

    /** Render the profile icon the same way the profile list does (custom file or built-in art) */
    @Nullable
    static Bitmap renderIcon(@NonNull Context context, @NonNull Instance instance) {
        try {
            Drawable drawable = InstanceIconProvider.fetchIcon(context.getResources(), instance);
            InstanceIconProvider.dropIcon(instance);
            Drawable copy = drawable.getConstantState() != null ? drawable.getConstantState().newDrawable(context.getResources()).mutate() : drawable;
            Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            copy.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
            copy.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            Log.w(TAG, "Could not render profile icon", t);
            return null;
        }
    }

    /** The icon provider keeps an unsynchronised cache for the UI, so draw where the UI draws */
    @Nullable
    private Bitmap renderOnMainThread(@NonNull final Instance instance) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return renderIcon(mContext, instance);
        final java.util.concurrent.FutureTask<Bitmap> task = new java.util.concurrent.FutureTask<>(() -> renderIcon(mContext, instance));
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        try {
            return task.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            task.cancel(true);
            return null;
        }
    }

    /**
     * Blocking: returns the public URL of the profile's icon, uploading it first when it is not
     * published yet. Null when rendering/uploading failed (callers fall back to a generic icon).
     * Must run on a background thread.
     */
    @Nullable
    synchronized String publish(@NonNull Instance instance) {
        loadCache();
        Bitmap bitmap = renderOnMainThread(instance);
        if (bitmap == null) return null;
        byte[] png;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            png = out.toByteArray();
        } finally {
            bitmap.recycle();
        }
        return publishPng(png);
    }

    /**
     * Server icons are 64x64; Discord renders external art at up to 512px and tiny images can be
     * dropped or blurred. Re-encode small PNGs at ICON_SIZE with nearest-neighbour scaling so the
     * pixel look stays crisp. Returns the input when it is already big enough or decoding fails.
     */
    @Nullable
    static byte[] upscalePixelArt(@Nullable byte[] png) {
        if (png == null) return null;
        Bitmap source = null;
        Bitmap scaled = null;
        try {
            source = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
            if (source == null) return png;
            if (source.getWidth() >= ICON_SIZE / 2 && source.getHeight() >= ICON_SIZE / 2) return png;
            scaled = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(scaled);
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setFilterBitmap(false);
            paint.setAntiAlias(false);
            canvas.drawBitmap(source, null, new android.graphics.Rect(0, 0, ICON_SIZE, ICON_SIZE), paint);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Throwable t) {
            return png;
        } finally {
            if (scaled != null) scaled.recycle();
            if (source != null) source.recycle();
        }
    }

    /** Blocking: publish any PNG (profile icon, server icon) and return its public URL */
    @Nullable
    synchronized String publishPng(@Nullable byte[] png) {
        if (png == null || png.length < 8 || png.length > 250_000) return null;
        if ((png[0] & 0xff) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return null;
        loadCache();
        String sha = sha1(png);
        if (sha == null) return null;
        String cached = mUrlBySha.get(sha);
        if (cached != null) return cached;
        if (mFailedSha.contains(sha)) return null;

        String publicUrl = STORAGE_BASE + "public/" + BUCKET + "/" + sha + ".png";
        boolean ok = exists(publicUrl) || upload(sha, png) || exists(publicUrl);
        if (ok) {
            mUrlBySha.put(sha, publicUrl);
            saveCache();
            return publicUrl;
        }
        mFailedSha.add(sha);
        return null;
    }

    private static boolean exists(@NonNull String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            return connection.getResponseCode() == 200;
        } catch (Throwable t) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean upload(@NonNull String sha, @NonNull byte[] png) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(STORAGE_BASE + BUCKET + "/" + sha + ".png").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("apikey", ANON_KEY);
            connection.setRequestProperty("Authorization", "Bearer " + ANON_KEY);
            connection.setRequestProperty("Content-Type", "image/png");
            connection.setRequestProperty("Cache-Control", "max-age=31536000");
            connection.setFixedLengthStreamingMode(png.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(png);
            }
            int code = connection.getResponseCode();
            if (code == 200 || code == 201) return true;
            // 400 "Duplicate" = another device published the same icon first: fine as well
            String body = readError(connection);
            Log.w(TAG, "Icon upload HTTP " + code + ": " + body);
            return body.contains("Duplicate") || body.contains("KeyAlreadyExists");
        } catch (Throwable t) {
            Log.w(TAG, "Icon upload failed", t);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @NonNull
    private static String readError(@NonNull HttpURLConnection connection) {
        try (InputStream in = connection.getErrorStream()) {
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 4096) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    @Nullable
    private static String sha1(@NonNull byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void loadCache() {
        if (mCacheLoaded) return;
        mCacheLoaded = true;
        if (!mCacheFile.isFile()) return;
        try (InputStream in = new FileInputStream(mCacheFile)) {
            byte[] bytes = new byte[(int) Math.min(mCacheFile.length(), 256 * 1024)];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n <= 0) break;
                read += n;
            }
            JSONObject json = new JSONObject(new String(bytes, 0, read, "UTF-8"));
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String url = json.optString(key, "");
                if (url.startsWith("https://")) mUrlBySha.put(key, url);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Icon cache unreadable, starting fresh");
        }
    }

    private void saveCache() {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : mUrlBySha.entrySet()) json.put(entry.getKey(), entry.getValue());
            File tmp = new File(mCacheFile.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(json.toString().getBytes("UTF-8"));
            }
            if (!tmp.renameTo(mCacheFile)) {
                //noinspection ResultOfMethodCallIgnored
                mCacheFile.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(mCacheFile);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Icon cache not saved", t);
        }
    }
}
