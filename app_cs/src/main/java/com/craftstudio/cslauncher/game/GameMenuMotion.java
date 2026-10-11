package com.craftstudio.cslauncher.game;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Animations of the in-game menu. The drawer itself slides in (DrawerLayout), on top of that the
 * header pops and the rows cascade in from the right. Only transform/alpha: cheap while the game
 * renders behind.
 */
final class GameMenuMotion {
    private GameMenuMotion() {}

    /** Played every time the menu opens (and when the rows change, e.g. entering the editor) */
    static void enter(@Nullable View logo, @Nullable View titles, @NonNull ListView list) {
        if (!Motion.content(list)) {
            rest(logo);
            rest(titles);
            for (int i = 0; i < list.getChildCount(); i++) rest(list.getChildAt(i));
            return;
        }
        if (logo != null) {
            logo.animate().cancel();
            logo.setAlpha(0f);
            logo.setScaleX(0.6f);
            logo.setScaleY(0.6f);
            logo.setRotation(-20f);
            logo.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                    .setStartDelay(60).setDuration(Motion.LONG).setInterpolator(Motion.SPRING).start();
        }
        if (titles != null) {
            titles.animate().cancel();
            titles.setAlpha(0f);
            titles.setTranslationX(Motion.dp(titles, 14));
            titles.animate().alpha(1f).translationX(0f)
                    .setStartDelay(100).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
        }
        // The rows exist only after the first layout
        if (list.getChildCount() == 0) {
            list.post(() -> cascadeRows(list));
        } else {
            cascadeRows(list);
        }
    }

    private static void cascadeRows(@NonNull ListView list) {
        float from = Motion.dp(list, 36);
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            row.animate().cancel();
            row.setAlpha(0f);
            row.setTranslationX(from);
            row.animate().alpha(1f).translationX(0f)
                    .setStartDelay(90 + i * 35L).setDuration(Motion.MEDIUM)
                    .setInterpolator(Motion.ENTER)
                    .withEndAction(() -> rest(row))
                    .start();
        }
    }

    /** Tiny press feedback on the tapped row's icon tile */
    static void tap(@Nullable View row) {
        if (row == null || !Motion.buttons(row)) return;
        View tile = row instanceof ViewGroup ? ((ViewGroup) row).getChildAt(0) : row;
        if (tile == null) return;
        tile.animate().cancel();
        tile.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).setStartDelay(0)
                .withEndAction(() -> tile.animate().scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM)
                        .setInterpolator(Motion.SPRING).start())
                .start();
    }

    private static void rest(@Nullable View view) {
        if (view == null) return;
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setRotation(0f);
    }
}
