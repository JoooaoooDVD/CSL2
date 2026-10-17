package com.craftstudio.cslauncher.multiplayer.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Motion for the Virtual LAN screens: screen swaps, status pulse, player rows, the thin
 * indeterminate bar. Everything degrades to an instant state change when animations are off
 * (system or launcher setting), so the UI is identical with motion disabled.
 */
final class MultiplayerMotion {
    private MultiplayerMotion() {}

    /** Entrance for a freshly shown screen: cards cascade up */
    static void screenEnter(@Nullable ViewGroup screen) {
        if (screen == null) return;
        screen.setVisibility(View.VISIBLE);
        screen.setAlpha(1f);
        if (!Motion.content(screen)) {
            for (int i = 0; i < screen.getChildCount(); i++) Motion.settle(screen.getChildAt(i));
            return;
        }
        Motion.cascadeChildren(screen, 0, 45, 14);
    }

    /** Fades a screen away, then marks it GONE */
    static void screenExit(@Nullable ViewGroup screen, @Nullable Runnable end) {
        if (screen == null) { if (end != null) end.run(); return; }
        if (screen.getVisibility() != View.VISIBLE || !Motion.content(screen)) {
            screen.setVisibility(View.GONE);
            if (end != null) end.run();
            return;
        }
        screen.animate().cancel();
        screen.animate().alpha(0f).translationY(-Motion.dp(screen, 6)).setDuration(Motion.SHORT).setInterpolator(Motion.EXIT)
                .withEndAction(() -> {
                    screen.setVisibility(View.GONE);
                    screen.setAlpha(1f);
                    screen.setTranslationY(0f);
                    if (end != null) end.run();
                }).start();
    }

    /** Expands / collapses an inline box (join code input, advanced settings) */
    static void toggleBox(@Nullable View box, boolean show) {
        if (box == null) return;
        if (show) {
            if (box.getVisibility() == View.VISIBLE) return;
            box.setVisibility(View.VISIBLE);
            if (!Motion.content(box)) { Motion.settle(box); return; }
            box.setAlpha(0f);
            box.setScaleY(0.92f);
            box.setPivotY(0f);
            box.animate().cancel();
            box.animate().alpha(1f).scaleY(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
        } else {
            Motion.hide(box, true);
        }
    }

    /** Success flourish on the status dot when the tunnel comes up */
    static void celebrate(@Nullable View dot) {
        if (dot == null || !Motion.content(dot)) return;
        dot.animate().cancel();
        dot.setScaleX(1f);
        dot.setScaleY(1f);
        dot.animate().scaleX(2.2f).scaleY(2.2f).alpha(0.5f).setDuration(220).setInterpolator(Motion.STANDARD)
                .withEndAction(() -> dot.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(360)
                        .setInterpolator(Motion.SPRING).start()).start();
    }

    /** Smoothly recolours a shape-drawable background (status dot, pills) */
    private static final java.util.Map<View, Integer> sShapeColors = new java.util.WeakHashMap<>();

    static void tintShape(@Nullable View view, int color) {
        if (view == null) return;
        Integer previous = sShapeColors.get(view);
        int from = previous != null ? previous : color;
        sShapeColors.put(view, color);
        if (!(view.getBackground() instanceof GradientDrawable)) return;
        GradientDrawable bg = (GradientDrawable) view.getBackground().mutate();
        if (from == color || !Motion.content(view)) {
            bg.setColor(color);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, color);
        anim.setDuration(Motion.MEDIUM);
        anim.addUpdateListener(a -> bg.setColor((Integer) a.getAnimatedValue()));
        anim.start();
    }

    /** New player row slides in from the side */
    static void memberIn(@Nullable View row, int index) {
        if (row == null) return;
        if (!Motion.content(row)) { Motion.settle(row); return; }
        Motion.slideInX(row, index * 40L, 18);
    }

    /** Player left: row collapses away before removal */
    static void memberOut(@Nullable View row, @Nullable Runnable end) {
        if (row == null) { if (end != null) end.run(); return; }
        if (!Motion.content(row)) { if (end != null) end.run(); return; }
        row.animate().cancel();
        row.animate().alpha(0f).translationX(Motion.dp(row, 24)).setDuration(Motion.SHORT + 40).setInterpolator(Motion.EXIT)
                .withEndAction(() -> { if (end != null) end.run(); }).start();
    }

    /** Failure: shake the hero card */
    static void fail(@Nullable View view) {
        Motion.shake(view);
    }

    static void textSwap(@Nullable TextView view, CharSequence text) {
        Motion.swapText(view, text);
    }

    /**
     * Thin indeterminate progress bar: a short platinum segment sweeps across the track.
     * Returns the running animator (null when motion is disabled, in which case the bar is hidden
     * and the track alone signals "working" through the phase text).
     */
    @Nullable
    static ValueAnimator sweep(@Nullable View track, @Nullable View bar) {
        if (track == null || bar == null) return null;
        if (!Motion.content(bar)) {
            bar.setVisibility(View.INVISIBLE);
            return null;
        }
        bar.setVisibility(View.VISIBLE);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1400);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            int width = track.getWidth();
            if (width <= 0) return;
            float eased = f < 0.5f ? 2 * f * f : 1 - (float) Math.pow(-2 * f + 2, 2) / 2;
            bar.setTranslationX(-bar.getWidth() + eased * (width + bar.getWidth()));
        });
        anim.start();
        return anim;
    }
}
