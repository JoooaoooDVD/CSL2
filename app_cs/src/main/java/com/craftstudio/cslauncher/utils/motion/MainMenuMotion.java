package com.craftstudio.cslauncher.utils.motion;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;

/**
 * Motion of the main menu chrome: the icon rail glides back in from the left edge (and its
 * buttons pop in one after another) when a focus screen such as the profile editor closes.
 * Only alpha / translation / scale are animated.
 */
public final class MainMenuMotion {

    private MainMenuMotion() {}

    /** The rail slides in from the left, then its buttons pop in top to bottom */
    public static void railIn(@Nullable View rail) {
        if (rail == null || !Motion.content(rail)) return;
        rail.animate().cancel();
        rail.setAlpha(0f);
        rail.setTranslationX(-Motion.dp(rail, 36));
        rail.animate().alpha(1f).translationX(0f).setStartDelay(40)
                .setDuration(Motion.LONG).setInterpolator(Motion.ENTER).setListener(null).start();

        View content = rail.findViewById(R.id.left_sidebar_content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) content;
        int shown = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.animate().cancel();
            child.setAlpha(0f);
            child.setScaleX(0.7f);
            child.setScaleY(0.7f);
            child.setTranslationX(-Motion.dp(child, 14));
            child.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f)
                    .setStartDelay(120 + Math.min(shown, 12) * 35L)
                    .setDuration(Motion.MEDIUM + 40).setInterpolator(Motion.SPRING)
                    .setListener(null).start();
            shown++;
        }
    }
}
