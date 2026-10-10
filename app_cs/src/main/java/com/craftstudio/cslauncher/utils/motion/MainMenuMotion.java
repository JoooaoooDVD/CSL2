package com.craftstudio.cslauncher.utils.motion;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;

/**
 * Motion of the main menu chrome: the icon rail glides back in from the left edge (and its
 * buttons pop in one after another) and the top bar drops back down when a focus screen such
 * as the profile editor closes.
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

    /**
     * Hides / shows the launcher top bar (logo, account, home, overflow).
     * Hiding is instant (the focus screen's own entrance covers the jump), showing drops the
     * bar down from above with a fade.
     */
    public static void topBar(@Nullable Activity activity, boolean show) {
        if (activity == null) return;
        View bar = activity.findViewById(R.id.cs_top_bar);
        if (bar == null) return;
        bar.animate().cancel();
        if (!show) {
            bar.setVisibility(View.GONE);
            return;
        }
        boolean wasHidden = bar.getVisibility() != View.VISIBLE;
        bar.setVisibility(View.VISIBLE);
        if (!wasHidden || !Motion.content(bar) || activity.isFinishing()) {
            bar.setAlpha(1f);
            bar.setTranslationY(0f);
            return;
        }
        bar.setAlpha(0f);
        bar.setTranslationY(-Motion.dp(bar, 28));
        bar.animate().alpha(1f).translationY(0f).setStartDelay(30)
                .setDuration(Motion.MEDIUM + 60).setInterpolator(Motion.ENTER).setListener(null).start();
    }
}
