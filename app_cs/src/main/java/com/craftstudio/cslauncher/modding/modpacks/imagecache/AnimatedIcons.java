package com.craftstudio.cslauncher.modding.modpacks.imagecache;

import android.graphics.Movie;
import android.graphics.Outline;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Animated project icons (CurseForge / Modrinth ship GIF and animated WebP icons).
 *
 * <ul>
 *   <li>{@link #mayBeAnimated(File)} is a cheap header sniff used by the cache to keep such files
 *       untouched (no JPEG re-encode, which would freeze them).</li>
 *   <li>{@link #decode(File)} returns a ready-to-start drawable, or {@code null} when the file is
 *       not actually animated (single-frame GIF) or the platform cannot play it.</li>
 *   <li>{@link #show(ImageView, Drawable, float)} puts it on screen with rounded corners and starts it.</li>
 * </ul>
 * Android 9+ uses {@link android.graphics.ImageDecoder} (GIF + animated WebP); older devices play
 * GIFs through {@link MovieDrawable} and show the first frame of animated WebP.
 */
public final class AnimatedIcons {
    private static final int MAX_EDGE = 256;

    private AnimatedIcons() {}

    /** True when the file header says GIF or animated WebP (APNG is not animatable on Android). */
    public static boolean mayBeAnimated(@Nullable File file) {
        if (file == null || !file.isFile()) return false;
        byte[] head = new byte[64];
        int n;
        try (InputStream in = new FileInputStream(file)) {
            n = in.read(head);
        } catch (IOException e) {
            return false;
        }
        if (n < 12) return false;
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') return true;
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            // VP8X extended header: byte 20 carries the feature flags, bit 1 = animation
            if (n > 20 && head[12] == 'V' && head[13] == 'P' && head[14] == '8' && head[15] == 'X') {
                return (head[20] & 0x02) != 0;
            }
        }
        return false;
    }

    /** Decodes an animated drawable, or null if the image is still or unsupported. Call off the UI thread. */
    @Nullable
    public static Drawable decode(@NonNull File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(file);
                Drawable d = android.graphics.ImageDecoder.decodeDrawable(src, (decoder, info, source) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    int edge = Math.max(w, h);
                    if (edge > MAX_EDGE) {
                        float s = MAX_EDGE / (float) edge;
                        decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
                    }
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                if (d instanceof android.graphics.drawable.AnimatedImageDrawable) {
                    ((android.graphics.drawable.AnimatedImageDrawable) d)
                            .setRepeatCount(android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE);
                    return d;
                }
                return null; // single frame: the normal bitmap path handles it
            } catch (Throwable t) {
                return null;
            }
        }
        // Legacy: GIF only
        try {
            Movie movie = Movie.decodeFile(file.getAbsolutePath());
            if (movie == null || movie.duration() <= 0 || movie.width() <= 0 || movie.height() <= 0) return null;
            return new MovieDrawable(movie);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Shows the drawable with rounded corners (clip outline) and starts it. */
    public static void show(@NonNull ImageView view, @NonNull Drawable drawable, final float cornerRadiusPx) {
        if (cornerRadiusPx > 0f) {
            view.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cornerRadiusPx);
                }
            });
            view.setClipToOutline(true);
        }
        if (drawable instanceof MovieDrawable) {
            // Movie needs a software canvas
            if (view.getLayerType() != View.LAYER_TYPE_SOFTWARE) view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        } else if (view.getLayerType() == View.LAYER_TYPE_SOFTWARE) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        view.setImageDrawable(drawable);
        if (drawable instanceof Animatable) ((Animatable) drawable).start();
    }

    /** Undo what {@link #show} did so the view can be reused for a still bitmap. */
    public static void reset(@NonNull ImageView view) {
        Drawable old = view.getDrawable();
        if (old instanceof Animatable) ((Animatable) old).stop();
        if (view.getLayerType() == View.LAYER_TYPE_SOFTWARE) view.setLayerType(View.LAYER_TYPE_NONE, null);
    }
}
