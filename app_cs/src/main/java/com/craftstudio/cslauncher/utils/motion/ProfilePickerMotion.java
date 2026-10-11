package com.craftstudio.cslauncher.utils.motion;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Motion of the profile picker popup.
 * <ul>
 *   <li>enter: the scrim fades in, the card rises and scales up from 94%, the info column
 *       cascades in, the Create button pops and the visible rows rise one after another;</li>
 *   <li>choose: the tapped row gives a short pulse and the "current" card swaps;</li>
 *   <li>exit: card shrinks slightly + fades, scrim fades, then the dialog is dismissed.</li>
 * </ul>
 * GPU friendly properties only; everything is skipped when content animations are off.
 */
public final class ProfilePickerMotion {

    private static final int MAX_ANIMATED_ROWS = 8;

    private ProfilePickerMotion() {}

    public static void enter(@NonNull View scrim, @NonNull View card, @Nullable ViewGroup sideContent,
                             @Nullable View create, @Nullable RecyclerView list) {
        if (!Motion.content(card)) return;
        scrim.animate().cancel();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setStartDelay(0).setDuration(220).setInterpolator(Motion.STANDARD)
                .setListener(null).start();

        card.animate().cancel();
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.setTranslationY(Motion.dp(card, 14));
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(30)
                .setDuration(Motion.MEDIUM + 40).setInterpolator(Motion.ENTER).setListener(null)
                .withLayer().start();

        Motion.cascadeChildren(sideContent, 120, 40, 10);
        Motion.popIn(create, 300, 0.9f);

        if (list != null) {
            // Rows exist after the first layout pass
            list.post(() -> {
                int count = Math.min(list.getChildCount(), MAX_ANIMATED_ROWS);
                for (int i = 0; i < count; i++) {
                    Motion.riseIn(list.getChildAt(i), 150 + i * 45L, 14);
                }
            });
        }
    }

    public static void exit(@NonNull View scrim, @NonNull View card, @NonNull Runnable onEnd) {
        if (!Motion.content(card)) {
            onEnd.run();
            return;
        }
        // Runs exactly once: on animation end, or from the fallback if the animation got cancelled
        final boolean[] done = {false};
        final Runnable end = () -> {
            if (done[0]) return;
            done[0] = true;
            onEnd.run();
        };
        card.postDelayed(end, 450);
        card.animate().cancel();
        card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(Motion.dp(card, 8))
                .setStartDelay(0).setDuration(Motion.SHORT).setInterpolator(Motion.EXIT).setListener(null)
                .withLayer().start();
        scrim.animate().cancel();
        scrim.animate().alpha(0f).setStartDelay(40).setDuration(Motion.SHORT + 30)
                .setInterpolator(Motion.STANDARD).setListener(null)
                .withEndAction(end).start();
    }

    /** Tapped row: a quick press-release pulse */
    public static void rowChosen(@Nullable View row) {
        if (row == null || !Motion.buttons(row)) return;
        row.animate().cancel();
        row.animate().scaleX(0.97f).scaleY(0.97f).setStartDelay(0).setDuration(90)
                .setInterpolator(Motion.STANDARD).setListener(null)
                .withEndAction(() -> row.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(Motion.SPRING).start())
                .start();
    }

    /** The "current" card in the info column got a new profile */
    public static void currentChanged(@Nullable View current) {
        if (current == null || !Motion.content(current)) return;
        current.animate().cancel();
        current.setAlpha(0.35f);
        current.setTranslationX(Motion.dp(current, -10));
        current.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(Motion.MEDIUM)
                .setInterpolator(Motion.ENTER).setListener(null).start();
    }
}
