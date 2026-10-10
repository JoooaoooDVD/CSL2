package com.craftstudio.cslauncher.utils.motion;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Motion engine of the settings screens (SettingsHubFragment + SettingsHostFragment).
 * <ul>
 *   <li>hub entrance: gear tile spins in, title and subtitle slide, the panel rises and the
 *       six cards sweep in diagonally (row by row, column by column); every artwork settles
 *       from a soft zoom while its chevron glides in from the right;</li>
 *   <li>card tap: the artwork pushes in, the chevron nudges forward, then the category opens;</li>
 *   <li>host: the header banner drops in when a category opens and its category tabs pop in
 *       from the right; both leave again when going back to the hub.</li>
 * </ul>
 * Only alpha / translation / scale / rotation are animated, so it stays smooth on phones.
 */
public final class SettingsMotion {

    private SettingsMotion() {}

    public static void playHubEntrance(@Nullable View tile, @Nullable View title, @Nullable View subtitle,
                                       @Nullable View panel, @NonNull View[][] cardsByColumn,
                                       @Nullable ViewGroup general) {
        if (tile != null) {
            if (Motion.content(tile)) {
                tile.animate().cancel();
                tile.setAlpha(0f);
                tile.setScaleX(0.6f);
                tile.setScaleY(0.6f);
                tile.setRotation(-90f);
                tile.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(0)
                        .setDuration(Motion.LONG + 80).setInterpolator(Motion.SPRING).setListener(null).start();
            } else {
                Motion.settle(tile);
            }
        }
        Motion.slideInX(title, 60, -30);
        Motion.slideInX(subtitle, 120, -22);
        Motion.riseIn(panel, 120, 18, Motion.LONG);

        for (int column = 0; column < cardsByColumn.length; column++) {
            View[] cards = cardsByColumn[column];
            for (int row = 0; row < cards.length; row++) {
                cardIn(cards[row], 200 + row * 90L + column * 60L);
            }
        }
        Motion.cascadeChildren(general, 520, 70, 14);
    }

    /** One card: rise + scale, its artwork settles and the chevron slides in */
    private static void cardIn(@Nullable View card, long delay) {
        if (card == null) return;
        if (!Motion.content(card)) {
            Motion.settle(card);
            return;
        }
        card.animate().cancel();
        card.setAlpha(0f);
        card.setTranslationY(Motion.dp(card, 26));
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delay).setDuration(Motion.LONG + 40)
                .setInterpolator(Motion.ENTER).setListener(null).withLayer().start();

        View art = card.findViewById(com.craftstudio.cslauncher.R.id.st_card_art);
        if (art != null) {
            art.animate().cancel();
            art.setScaleX(1.14f);
            art.setScaleY(1.14f);
            art.setTranslationX(Motion.dp(art, 18));
            art.animate().scaleX(1f).scaleY(1f).translationX(0f).setStartDelay(delay)
                    .setDuration(1100).setInterpolator(Motion.ENTER).setListener(null).start();
        }
        View chevron = card.findViewById(com.craftstudio.cslauncher.R.id.st_card_chevron);
        if (chevron != null) {
            chevron.animate().cancel();
            chevron.setAlpha(0f);
            chevron.setTranslationX(Motion.dp(chevron, 24));
            chevron.animate().alpha(1f).translationX(0f).setStartDelay(delay + 180)
                    .setDuration(Motion.MEDIUM + 60).setInterpolator(Motion.SPRING).setListener(null).start();
        }
    }

    /** Tap feedback, then {@code open} runs (immediately when motion is off) */
    public static void cardTapped(@NonNull View card, @NonNull Runnable open) {
        if (!Motion.buttons(card)) {
            open.run();
            return;
        }
        View art = card.findViewById(com.craftstudio.cslauncher.R.id.st_card_art);
        if (art != null) {
            art.animate().cancel();
            art.animate().scaleX(1.08f).scaleY(1.08f).setStartDelay(0).setDuration(160)
                    .setInterpolator(Motion.STANDARD).setListener(null)
                    .withEndAction(() -> art.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(Motion.ENTER).start())
                    .start();
        }
        View chevron = card.findViewById(com.craftstudio.cslauncher.R.id.st_card_chevron);
        if (chevron != null) {
            chevron.animate().cancel();
            chevron.animate().translationX(Motion.dp(chevron, 8)).setStartDelay(0).setDuration(130)
                    .setInterpolator(Motion.STANDARD).setListener(null)
                    .withEndAction(() -> chevron.animate().translationX(0f).setDuration(260)
                            .setInterpolator(Motion.SPRING).start())
                    .start();
        }
        card.postDelayed(open, 120);
    }

    /**
     * Shows / hides the category tabs (on the header banner). When they appear the tabs pop in
     * one after another from the right.
     */
    public static void rail(@Nullable View rail, boolean show, boolean animate) {
        if (rail == null) return;
        boolean wasVisible = rail.getVisibility() == View.VISIBLE;
        rail.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show || !animate || wasVisible || !Motion.content(rail)) return;
        View items = rail.findViewById(com.craftstudio.cslauncher.R.id.settings_rail_items);
        if (!(items instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) items;
        for (int i = 0; i < group.getChildCount(); i++) {
            View tab = group.getChildAt(i);
            tab.animate().cancel();
            tab.setAlpha(0f);
            tab.setTranslationX(Motion.dp(tab, 18));
            tab.setScaleX(0.8f);
            tab.setScaleY(0.8f);
            tab.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(120 + i * 35L).setDuration(Motion.MEDIUM + 40)
                    .setInterpolator(Motion.SPRING).setListener(null).start();
        }
    }

    /** Shows / hides the category header banner with a short drop */
    public static void header(@Nullable View header, boolean show, boolean animate) {
        if (header == null) return;
        header.animate().cancel();
        if (!show) {
            header.setVisibility(View.GONE);
            header.setAlpha(1f);
            header.setTranslationY(0f);
            return;
        }
        boolean wasVisible = header.getVisibility() == View.VISIBLE;
        header.setVisibility(View.VISIBLE);
        if (!animate || wasVisible || !Motion.content(header)) {
            header.setAlpha(1f);
            header.setTranslationY(0f);
            return;
        }
        header.setAlpha(0f);
        header.setTranslationY(-Motion.dp(header, 14));
        header.animate().alpha(1f).translationY(0f).setStartDelay(40).setDuration(Motion.MEDIUM + 60)
                .setInterpolator(Motion.ENTER).setListener(null).start();
    }

    /** Header artwork changes with a soft zoom settle */
    public static void headerArtChanged(@Nullable View art) {
        Motion.settleZoom(art, 0);
    }
}
