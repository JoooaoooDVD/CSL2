package com.craftstudio.cslauncher.utils.motion;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * Motion engine of the install page (ModInstallFragment).
 * <ul>
 *   <li>entrance: the top bar drops in, the project card rises, the install column glides
 *       in from the right (from below on narrow screens), tags pop one by one and the
 *       download / follower numbers count up;</li>
 *   <li>version card: when another version is picked the content slides and cross-fades;</li>
 *   <li>main button: background and text colors morph between Install / Update / Uninstall
 *       (see {@link Motion#tintButton}) and spring on every state change;</li>
 *   <li>gallery: thumbnails sweep in from the right and fade up as their images load; the
 *       full screen viewer zooms open;</li>
 *   <li>dependencies: rows cascade in.</li>
 * </ul>
 */
public final class InstallMotion {

    private InstallMotion() {}

    public static void playEntrance(@Nullable View topBar, @Nullable View infoPanel, @Nullable View actionPanel,
                                    boolean wide, @Nullable ViewGroup tags) {
        if (topBar != null) {
            if (Motion.content(topBar)) {
                topBar.animate().cancel();
                topBar.setAlpha(0f);
                topBar.setTranslationY(-Motion.dp(topBar, 16));
                topBar.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM)
                        .setInterpolator(Motion.ENTER).setListener(null).start();
            } else {
                Motion.settle(topBar);
            }
        }
        if (wide) {
            Motion.riseIn(infoPanel, 60, 26);
            Motion.slideInX(actionPanel, 140, 36);
        } else {
            Motion.riseIn(actionPanel, 60, 26);
            Motion.riseIn(infoPanel, 150, 26);
        }
        Motion.popChildren(tags, 320, 60, 0.7f);
    }

    /** Version details slide in from the side the user "moved" to */
    public static void versionChanged(@Nullable View versionBox) {
        if (versionBox == null) return;
        if (!Motion.content(versionBox)) {
            Motion.settle(versionBox);
            return;
        }
        versionBox.animate().cancel();
        versionBox.setAlpha(0.2f);
        versionBox.setTranslationX(Motion.dp(versionBox, 22));
        versionBox.animate().alpha(1f).translationX(0f).setDuration(Motion.MEDIUM + 40)
                .setInterpolator(Motion.ENTER).setListener(null).start();
    }

    /** First reveal of the version details after loading */
    public static void versionLoaded(@Nullable View versionBox) {
        Motion.riseIn(versionBox, 0, 10, Motion.MEDIUM);
    }

    public static void buttonStateChanged(@Nullable View button) {
        Motion.bounce(button);
    }

    /** A thumbnail slides into the strip */
    public static void galleryItemIn(@Nullable View item, int position) {
        if (item == null) return;
        if (!Motion.content(item)) {
            Motion.settle(item);
            return;
        }
        item.animate().cancel();
        item.setAlpha(0f);
        item.setTranslationX(Motion.dp(item, 40));
        item.animate().alpha(1f).translationX(0f)
                .setStartDelay(Math.min(position, 6) * 70L)
                .setDuration(Motion.LONG)
                .setInterpolator(Motion.ENTER)
                .setListener(null)
                .withLayer()
                .start();
    }

    /** Image fades and settles once its bitmap is decoded */
    public static void imageLoaded(@Nullable ImageView image) {
        if (image == null) return;
        if (!Motion.content(image)) {
            image.setAlpha(1f);
            return;
        }
        image.animate().cancel();
        image.setAlpha(0f);
        image.setScaleX(1.06f);
        image.setScaleY(1.06f);
        image.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.LONG)
                .setInterpolator(Motion.ENTER).setListener(null).start();
    }

    /** Full screen gallery viewer opening */
    public static void viewerOpen(@Nullable View content) {
        if (content == null) return;
        if (!Motion.content(content)) {
            Motion.settle(content);
            return;
        }
        content.setAlpha(0f);
        content.setScaleX(0.9f);
        content.setScaleY(0.9f);
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM + 60)
                .setInterpolator(Motion.ENTER).setListener(null).start();
    }

    public static void dependenciesIn(@Nullable ViewGroup list) {
        Motion.cascadeChildren(list, 40, 55, 12);
    }

    /** Success pop of the main button after an install */
    public static void success(@Nullable View button) {
        Motion.pulse(button);
    }
}
