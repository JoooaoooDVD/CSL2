package com.craftstudio.cslauncher.utils.motion;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;

/**
 * Motion of the launcher chrome:
 * <ul>
 *   <li>top bar dock: the highlight glides (with a small stretch) behind the active button,
 *       icons brighten / dim, a tapped icon gives a short bounce;</li>
 *   <li>top bar: hidden for full screen focus screens (profile editor, install page) and
 *       dropped back down when they close.</li>
 * </ul>
 * Only alpha / translation / scale are animated.
 */
public final class MainMenuMotion {

    private MainMenuMotion() {}

    // ─── Dock ────────────────────────────────────────────────────────────────

    /** Slides the dock highlight to {@code x} (left edge inside the dock frame) */
    public static void dockIndicatorTo(@NonNull View indicator, float x, boolean animate) {
        float targetX = x - indicator.getLeft();
        indicator.animate().cancel();
        indicator.animate().setUpdateListener(null);
        boolean wasHidden = indicator.getVisibility() != View.VISIBLE || indicator.getAlpha() < 0.05f;
        indicator.setVisibility(View.VISIBLE);
        if (!animate || !Motion.buttons(indicator)) {
            indicator.setTranslationX(targetX);
            indicator.setAlpha(1f);
            indicator.setScaleX(1f);
            indicator.setScaleY(1f);
            return;
        }
        if (wasHidden) {
            // Appears in place with a pop
            indicator.setTranslationX(targetX);
            indicator.setAlpha(0f);
            indicator.setScaleX(0.6f);
            indicator.setScaleY(0.6f);
            indicator.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0)
                    .setDuration(Motion.MEDIUM).setInterpolator(Motion.SPRING).setListener(null).start();
            return;
        }
        if (Math.abs(indicator.getTranslationX() - targetX) < 1f) return;
        // Glide with a slight horizontal stretch while moving
        indicator.setScaleY(1f);
        indicator.animate().translationX(targetX).scaleX(1f).alpha(1f).setStartDelay(0)
                .setDuration(Motion.MEDIUM + 40).setInterpolator(Motion.SPRING).setListener(null)
                .setUpdateListener(animation -> {
                    float f = animation.getAnimatedFraction();
                    indicator.setScaleX(1f + 0.18f * (float) Math.sin(Math.PI * Math.min(1f, f)));
                })
                .withEndAction(() -> {
                    indicator.animate().setUpdateListener(null);
                    indicator.setScaleX(1f);
                })
                .start();
    }

    public static void dockIndicatorHide(@NonNull View indicator) {
        indicator.animate().cancel();
        indicator.animate().setUpdateListener(null);
        if (indicator.getVisibility() != View.VISIBLE) return;
        if (!Motion.buttons(indicator)) {
            indicator.setVisibility(View.INVISIBLE);
            return;
        }
        indicator.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setStartDelay(0)
                .setDuration(Motion.SHORT).setInterpolator(Motion.STANDARD).setListener(null)
                .withEndAction(() -> indicator.setVisibility(View.INVISIBLE)).start();
    }

    /** Active icons brighten, inactive ones dim */
    public static void dockIcon(@NonNull View icon, float alpha) {
        if (!Motion.buttons(icon)) {
            icon.setAlpha(alpha);
            return;
        }
        icon.animate().alpha(alpha).setStartDelay(0).setDuration(Motion.MEDIUM)
                .setInterpolator(Motion.STANDARD).setListener(null).start();
    }

    /** Short squash + spring back on tap */
    public static void dockTap(@NonNull View icon) {
        if (!Motion.buttons(icon)) return;
        icon.setScaleX(0.82f);
        icon.setScaleY(0.82f);
        icon.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(Motion.MEDIUM)
                .setInterpolator(Motion.SPRING_STRONG).setListener(null).start();
    }

    // ─── Top bar ─────────────────────────────────────────────────────────────

    /**
     * Hides / shows the launcher top bar (logo, dock, account, settings, overflow).
     * Every hide call must be balanced by a show call (onStart / onStop of the focus screen).
     * Hiding is instant (the focus screen's own entrance covers the jump), showing drops the
     * bar down from above with a fade.
     */
    public static void topBar(@Nullable Activity activity, boolean show) {
        if (activity == null) return;
        View bar = activity.findViewById(R.id.cs_top_bar);
        if (bar == null) return;
        // Counts the focus screens that asked to hide it, so overlapping start / stop calls of
        // two focus screens (one replacing the other) cannot show it in the middle
        Object tag = bar.getTag(R.id.cs_top_bar);
        int hidden = tag instanceof Integer ? (Integer) tag : 0;
        hidden = Math.max(0, hidden + (show ? -1 : 1));
        bar.setTag(R.id.cs_top_bar, hidden);
        if (show && hidden > 0) return;
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
