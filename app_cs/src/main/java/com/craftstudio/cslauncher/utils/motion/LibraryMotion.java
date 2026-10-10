package com.craftstudio.cslauncher.utils.motion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;

import com.craftstudio.cslauncher.R;

/**
 * Motion engine of the content library (ModsSearchFragment + its cards).
 * Choreography:
 * <ul>
 *   <li>screen entrance: title slides in, subtitle follows, type tabs pop, the toolbar rises
 *       and the category chips cascade from left to right;</li>
 *   <li>type tabs: a pill indicator glides under the selected tab while the text colors
 *       cross-fade;</li>
 *   <li>cards: rise + scale in, staggered by row and column so the grid "waves" in, the
 *       icon pops when its image arrives and the action button morphs between
 *       Install / Update / Uninstall;</li>
 *   <li>loading: skeleton cards breathe instead of a lone spinner; the grid dims while a
 *       new search is running.</li>
 * </ul>
 */
public final class LibraryMotion {

    private LibraryMotion() {}

    // ─── Screen entrance ─────────────────────────────────────────────────────

    public static void playEntrance(@Nullable View title, @Nullable View subtitle, @Nullable View tabs,
                                    @Nullable View toolbar, @Nullable ViewGroup chips) {
        Motion.slideInX(title, 0, -28);
        Motion.slideInX(subtitle, 70, -20);
        Motion.popIn(tabs, 120, 0.9f);
        Motion.riseIn(toolbar, 160, 16);
        cascadeChips(chips, 240);
    }

    /** Category chips fall into place one after another */
    public static void cascadeChips(@Nullable ViewGroup chips, long startDelay) {
        if (chips == null) return;
        for (int i = 0; i < chips.getChildCount(); i++) {
            View chip = chips.getChildAt(i);
            if (!Motion.content(chip)) {
                Motion.settle(chip);
                continue;
            }
            chip.animate().cancel();
            chip.setAlpha(0f);
            chip.setTranslationX(Motion.dp(chip, 14));
            chip.animate().alpha(1f).translationX(0f)
                    .setStartDelay(startDelay + Math.min(i, 10) * 40L)
                    .setDuration(Motion.MEDIUM)
                    .setInterpolator(Motion.ENTER)
                    .setListener(null)
                    .start();
        }
    }

    /** Selected chip springs, the previously selected one relaxes */
    public static void selectChip(@Nullable View chip) {
        Motion.bounce(chip);
    }

    // ─── Type tabs ───────────────────────────────────────────────────────────

    /**
     * Moves the pill indicator under {@code target}. The width is applied once (one layout
     * pass) and the glide itself only animates translation/scale, so it stays at 60fps.
     */
    public static void moveIndicator(@Nullable View indicator, @Nullable View target, boolean animate) {
        if (indicator == null || target == null) return;
        if (target.getWidth() == 0) {
            // Not laid out yet: retry once the tab has its size
            target.post(() -> moveIndicator(indicator, target, false));
            return;
        }
        final int targetWidth = target.getWidth();
        final float targetX = target.getLeft();
        final int oldWidth = indicator.getWidth();
        final float oldX = indicator.getTranslationX();
        indicator.animate().cancel();
        indicator.setPivotX(0f);
        indicator.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams params = indicator.getLayoutParams();
        boolean widthChanges = params.width != targetWidth;
        int targetHeight = target.getHeight();
        if (widthChanges || (targetHeight > 0 && params.height != targetHeight)) {
            params.width = targetWidth;
            if (targetHeight > 0) params.height = targetHeight;
            indicator.setLayoutParams(params);
        }
        if (!animate || oldWidth == 0 || !Motion.buttons(indicator)) {
            indicator.setScaleX(1f);
            indicator.setTranslationX(targetX);
            return;
        }
        Runnable start = () -> {
            indicator.setScaleX(oldWidth / (float) targetWidth);
            indicator.setTranslationX(oldX);
            indicator.animate()
                    .translationX(targetX)
                    .scaleX(1f)
                    .setDuration(Motion.MEDIUM + 60)
                    .setInterpolator(Motion.STANDARD)
                    .setListener(null)
                    .start();
        };
        if (!widthChanges) {
            start.run();
            return;
        }
        // Start the glide on the first frame after the new width is laid out
        indicator.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                indicator.getViewTreeObserver().removeOnPreDrawListener(this);
                start.run();
                return true;
            }
        });
    }

    /** Cross-fades the text and icon color of a tab */
    public static void tintTab(@NonNull TextView tab, int color, boolean animate) {
        Object running = tab.getTag(R.id.motion_tint_animator);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        int from = tab.getCurrentTextColor();
        if (!animate || from == color || !Motion.buttons(tab)) {
            tab.setTextColor(color);
            TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(color));
            return;
        }
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, color);
        animator.setDuration(Motion.MEDIUM);
        animator.setInterpolator(Motion.STANDARD);
        animator.addUpdateListener(a -> {
            int value = (int) a.getAnimatedValue();
            tab.setTextColor(value);
            TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(value));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                tab.setTag(R.id.motion_tint_animator, null);
            }
        });
        tab.setTag(R.id.motion_tint_animator, animator);
        animator.start();
    }

    // ─── Cards ───────────────────────────────────────────────────────────────

    /**
     * Grid entrance of one card. Delay grows with row and column so a freshly loaded page
     * sweeps in diagonally; cards bound later while scrolling only get a short rise.
     */
    public static void cardIn(@NonNull View card, int position, int spanCount, boolean firstPage) {
        if (!Motion.content(card)) {
            Motion.settle(card);
            return;
        }
        int span = Math.max(1, spanCount);
        long delay;
        if (firstPage) {
            int row = position / span;
            int column = position % span;
            delay = Math.min(row * 70L + column * 45L, 520L);
        } else {
            delay = (position % span) * 40L;
        }
        card.animate().cancel();
        card.setAlpha(0f);
        card.setTranslationY(Motion.dp(card, firstPage ? 30 : 18));
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delay)
                .setDuration(Motion.LONG)
                .setInterpolator(Motion.ENTER)
                .setListener(null)
                .withLayer()
                .start();
    }

    /** The card icon pops in when its bitmap arrives */
    public static void iconLoaded(@Nullable ImageView icon) {
        if (icon == null || !Motion.content(icon)) return;
        icon.animate().cancel();
        icon.setAlpha(0f);
        icon.setScaleX(0.7f);
        icon.setScaleY(0.7f);
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM)
                .setInterpolator(Motion.SPRING).setListener(null).start();
    }

    /** Small spring when the action button changes its meaning */
    public static void actionMorph(@Nullable View action) {
        if (action == null || !Motion.buttons(action)) return;
        action.animate().cancel();
        action.setScaleX(0.92f);
        action.setScaleY(0.92f);
        action.animate().scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM + 40)
                .setInterpolator(Motion.SPRING).setListener(null).start();
    }

    // ─── Loading ─────────────────────────────────────────────────────────────

    /** Starts the breathing pulse of a skeleton card (stopped with {@link #stopSkeleton}) */
    public static void startSkeleton(@NonNull View skeleton, int position) {
        stopSkeleton(skeleton);
        if (!Motion.content(skeleton)) {
            skeleton.setAlpha(0.7f);
            return;
        }
        ObjectAnimator pulse = ObjectAnimator.ofFloat(skeleton, View.ALPHA, 0.45f, 1f);
        pulse.setDuration(760);
        pulse.setStartDelay((position % 6) * 90L);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(Motion.STANDARD);
        skeleton.setTag(R.id.motion_pulse_animator, pulse);
        pulse.start();
    }

    public static void stopSkeleton(@NonNull View skeleton) {
        Object running = skeleton.getTag(R.id.motion_pulse_animator);
        if (running instanceof Animator) ((Animator) running).cancel();
        skeleton.setTag(R.id.motion_pulse_animator, null);
        skeleton.setAlpha(1f);
    }

    /** Dims the grid while a new search runs over existing results */
    public static void dimGrid(@Nullable View grid, boolean dim) {
        if (grid == null) return;
        float target = dim ? 0.45f : 1f;
        if (!Motion.content(grid)) {
            grid.setAlpha(target);
            return;
        }
        grid.animate().cancel();
        grid.animate().alpha(target).setDuration(dim ? Motion.SHORT : Motion.MEDIUM)
                .setInterpolator(Motion.STANDARD).setListener(null).start();
    }

    // ─── Toolbar details ─────────────────────────────────────────────────────

    /** Search box lifts and glows a bit while focused */
    public static void searchFocus(@Nullable View box, boolean focused) {
        Motion.lift(box, focused);
        if (box == null || !Motion.buttons(box)) return;
        box.animate().scaleX(focused ? 1.012f : 1f).scaleY(focused ? 1.012f : 1f)
                .setDuration(Motion.SHORT + 40).setInterpolator(Motion.STANDARD).setListener(null).start();
    }

    /** Clear (✕) button pops in when there is text, shrinks away when empty */
    public static void clearButton(@Nullable View clear, boolean visible) {
        if (clear == null) return;
        float targetAlpha = visible ? 1f : 0.35f;
        float targetScale = visible ? 1f : 0.75f;
        if (!Motion.buttons(clear)) {
            clear.setAlpha(targetAlpha);
            clear.setScaleX(targetScale);
            clear.setScaleY(targetScale);
            return;
        }
        if (Math.abs(clear.getAlpha() - targetAlpha) < 0.01f) return;
        clear.animate().cancel();
        clear.animate().alpha(targetAlpha).scaleX(targetScale).scaleY(targetScale)
                .setDuration(Motion.MEDIUM).setInterpolator(visible ? Motion.SPRING : Motion.STANDARD)
                .setListener(null).start();
    }

    /** Sort chevron flips while the sort menu is open */
    public static void chevron(@Nullable View chevron, boolean open) {
        Motion.rotateTo(chevron, open ? 180f : 0f);
    }

    /** Cross-fades the state panel (loading / empty / error) */
    public static void showState(@Nullable View state) {
        if (state == null) return;
        if (state.getVisibility() == View.VISIBLE && state.getAlpha() == 1f) return;
        state.setVisibility(View.VISIBLE);
        Motion.popIn(state, 0, 0.94f);
    }
}
