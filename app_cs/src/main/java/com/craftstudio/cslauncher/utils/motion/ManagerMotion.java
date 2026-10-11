package com.craftstudio.cslauncher.utils.motion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Motion of the content manager (mods / resource packs / shader packs).
 * Every view gets at most one entrance; everything is transform/alpha only (60fps).
 */
public final class ManagerMotion {
    private ManagerMotion() {}

    /** Page entrance: header slides from the left, toolbar rises, tabs pop */
    public static void playEntrance(@Nullable View back, @Nullable View tile, @Nullable View titleBlock,
                                    @Nullable View tabs, @Nullable ViewGroup toolbar) {
        Motion.slideInX(back, 0, -24);
        Motion.popIn(tile, 60, 0.55f);
        Motion.slideInX(titleBlock, 110, -18);
        Motion.riseIn(tabs, 140, 10);
        Motion.cascadeChildren(toolbar, 180, 45, 12);
    }

    /**
     * One-time staggered entrance of the rows that are on screen after the first layout.
     * Rows bound later (scrolling) are left alone.
     */
    public static void rowsIn(@NonNull RecyclerView recycler, int spanCount, long startDelay) {
        recycler.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                recycler.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!Motion.content(recycler)) return true;
                int span = Math.max(1, spanCount);
                for (int i = 0; i < recycler.getChildCount(); i++) {
                    View row = recycler.getChildAt(i);
                    int row_ = i / span;
                    int column = i % span;
                    long delay = startDelay + Math.min(row_ * 55L + column * 40L, 480L);
                    row.animate().cancel();
                    row.setAlpha(0f);
                    row.setTranslationY(Motion.dp(row, 26));
                    row.setScaleX(0.96f);
                    row.setScaleY(0.96f);
                    row.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                            .setStartDelay(delay).setDuration(Motion.LONG)
                            .setInterpolator(Motion.ENTER).setListener(null).withLayer().start();
                }
                return true;
            }
        });
    }

    /**
     * Content type switch: the list slides out toward the old tab, then the new content
     * comes in from the other side. {@code swap} runs between the two halves.
     */
    public static void typeSwitch(@NonNull View content, boolean toRight, @NonNull Runnable swap) {
        content.animate().cancel();
        if (!Motion.content(content)) {
            swap.run();
            Motion.settle(content);
            return;
        }
        float distance = Motion.dp(content, 36) * (toRight ? -1 : 1);
        content.animate().alpha(0f).translationX(distance)
                .setStartDelay(0).setDuration(Motion.SHORT)
                .setInterpolator(Motion.EXIT)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        content.animate().setListener(null);
                        swap.run();
                        content.setTranslationX(-distance);
                        content.animate().alpha(1f).translationX(0f)
                                .setDuration(Motion.MEDIUM + 40)
                                .setInterpolator(Motion.ENTER).start();
                    }
                }).start();
    }

    /** Header tile flips around its Y axis while the glyph/colours change */
    public static void tileFlip(@NonNull View tile, @NonNull Runnable change) {
        tile.animate().cancel();
        if (!Motion.buttons(tile)) {
            change.run();
            tile.setRotationY(0f);
            return;
        }
        tile.setCameraDistance(tile.getResources().getDisplayMetrics().density * 8000);
        tile.animate().rotationY(90f).setDuration(Motion.SHORT).setStartDelay(0)
                .setInterpolator(Motion.EXIT)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        tile.animate().setListener(null);
                        change.run();
                        tile.setRotationY(-90f);
                        tile.animate().rotationY(0f).setDuration(Motion.MEDIUM)
                                .setInterpolator(Motion.SPRING).start();
                    }
                }).start();
    }

    /** Count of a filter chip changed: swap the text and give it a small bump */
    public static void chipCount(@NonNull TextView chip, @NonNull CharSequence text, boolean animate) {
        if (text.toString().contentEquals(chip.getText())) return;
        chip.setText(text);
        if (animate) Motion.pulse(chip);
    }

    /** Empty state entrance: glow breathes in, tile pops, texts and buttons rise */
    public static void emptyIn(@Nullable View glow, @Nullable View tile, @NonNull ViewGroup group) {
        if (glow != null) {
            // The glow rests at a low alpha: breathe it in to that value, not to 1
            float rest = 0.35f;
            glow.animate().cancel();
            if (Motion.content(glow)) {
                glow.setAlpha(0f);
                glow.setScaleX(0.6f);
                glow.setScaleY(0.6f);
                glow.animate().alpha(rest).scaleX(1f).scaleY(1f).setStartDelay(60)
                        .setDuration(Motion.LONG + 200).setInterpolator(Motion.ENTER)
                        .setListener(null).start();
            } else {
                glow.setAlpha(rest);
                glow.setScaleX(1f);
                glow.setScaleY(1f);
            }
        }
        Motion.popIn(tile, 80, 0.5f);
        for (int i = 1; i < group.getChildCount(); i++) {
            Motion.riseIn(group.getChildAt(i), 160 + i * 60L, 14);
        }
    }

    /** Small rise for the "no results" block */
    public static void noResultsIn(@NonNull View view) {
        Motion.riseIn(view, 0, 12);
    }
}
