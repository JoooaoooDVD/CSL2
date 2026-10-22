package com.craftstudio.cslauncher.discord;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * High-performance, memory-safe avatar loader and circular cropper for Discord profile images.
 */
public final class DiscordAvatarLoader {

    private static final String TAG = "DiscordAvatarLoader";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static final int MAX_CACHE_ENTRIES = 30;
    private static final LruCache<String, Bitmap> sMemoryCache = new LruCache<>(MAX_CACHE_ENTRIES);

    private DiscordAvatarLoader() {}

    /**
     * Loads the Discord avatar from URL into the ImageView with smooth circular cropping.
     * Gracefully falls back to the default Discord icon if the URL is empty or load fails.
     */
    public static void loadAvatar(@NonNull Context context, @Nullable String avatarUrl, @NonNull ImageView targetView) {
        if (avatarUrl == null || avatarUrl.trim().isEmpty()) {
            targetView.setTag(R.id.discord_avatar_image, null);
            targetView.setImageResource(R.drawable.ic_brand_discord);
            return;
        }

        final String cleanUrl = avatarUrl.trim();

        // Check memory cache
        Bitmap cached = sMemoryCache.get(cleanUrl);
        if (cached != null && !cached.isRecycled()) {
            targetView.setTag(R.id.discord_avatar_image, cleanUrl);
            targetView.setImageBitmap(cached);
            return;
        }

        // Set default icon while loading and tag view
        targetView.setTag(R.id.discord_avatar_image, cleanUrl);
        targetView.setImageResource(R.drawable.ic_brand_discord);

        CSLauncherApplication.sExecutorService.execute(() -> {
            Bitmap circular = null;
            HttpURLConnection connection = null;
            InputStream is = null;
            try {
                URL url = new URL(cleanUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.setInstanceFollowRedirects(true);
                connection.connect();

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    is = connection.getInputStream();
                    Bitmap raw = BitmapFactory.decodeStream(is);
                    if (raw != null) {
                        circular = getCircularBitmap(raw);
                        sMemoryCache.put(cleanUrl, circular);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to load Discord avatar from " + cleanUrl + ": " + t.getMessage());
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Throwable ignored) {}
                }
                if (connection != null) {
                    try { connection.disconnect(); } catch (Throwable ignored) {}
                }
            }

            final Bitmap finalCircular = circular;
            sMainHandler.post(() -> {
                Object currentTag = targetView.getTag(R.id.discord_avatar_image);
                if (cleanUrl.equals(currentTag)) {
                    if (finalCircular != null) {
                        targetView.setImageBitmap(finalCircular);
                    } else {
                        targetView.setImageResource(R.drawable.ic_brand_discord);
                    }
                }
            });
        });
    }

    /**
     * Produces a clean circular bitmap with antialiasing.
     */
    @NonNull
    public static Bitmap getCircularBitmap(@NonNull Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int size = Math.min(width, height);

        int x = (width - size) / 2;
        int y = (height - size) / 2;

        Bitmap squared = Bitmap.createBitmap(source, x, y, size, size);
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setFilterBitmap(true);
        paint.setDither(true);
        paint.setShader(new BitmapShader(squared, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));

        float radius = size / 2f;
        canvas.drawCircle(radius, radius, radius, paint);

        return output;
    }
}
