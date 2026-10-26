package com.craftstudio.cslauncher.modding.modpacks.imagecache;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * Optional extension of {@link ImageReceiver}: receivers that implement it get animated icons
 * (GIF / animated WebP, as used by CurseForge and Modrinth projects) as a running {@link Drawable}
 * instead of a still first frame.
 */
public interface AnimatedImageReceiver extends ImageReceiver {
    /** Called on the UI thread with an animated drawable that is ready to be started */
    void onAnimatedImageAvailable(@NonNull Drawable drawable);
}
