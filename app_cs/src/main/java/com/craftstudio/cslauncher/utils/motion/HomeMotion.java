package com.craftstudio.cslauncher.utils.motion;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Motion engine of the home screen (RightPaneHomeFragment).
 * <ul>
 *   <li>entrance: the banner rises while its artwork settles from a soft zoom, the MINECRAFT
 *       wordmark slides in, the version chip pops and the feature pill glides from the right;
 *       then the profile card, the EDIT card and PLAY rise one after another, a light streak
 *       sweeps across PLAY and the two cards come up with their icon tiles popping;</li>
 *   <li>return (closing a screen of the pane): a short fade-settle of the whole page + glint;</li>
 *   <li>card tap: the chevron nudges forward before the destination opens.</li>
 * </ul>
 * Only alpha / translation / scale are animated (GPU friendly), every step respects the
 * launcher animation settings through {@link Motion}.
 */
public final class HomeMotion {

    private HomeMotion() {}

    public static void playEntrance(@Nullable View banner, @Nullable View bannerArt, @Nullable View wordmark,
                                    @Nullable View versionChip, @Nullable View features,
                                    @Nullable View profileCard, @Nullable View editCard, @Nullable View play,
                                    @Nullable View playGlint, @NonNull View[] cards, @NonNull View[] tiles,
                                    @NonNull View[] cardArts) {
        Motion.riseIn(banner, 0, 16, Motion.LONG);
        Motion.settleZoom(bannerArt, 0);
        Motion.slideInX(wordmark, 120, -34);
        Motion.popIn(versionChip, 200, 0.7f);
        Motion.slideInX(features, 240, 40);

        Motion.riseIn(profileCard, 180, 20);
        Motion.popIn(editCard, 250, 0.85f);
        Motion.riseIn(play, 300, 20);
        glint(playGlint, play, 620);

        for (int i = 0; i < cards.length; i++) {
            Motion.riseIn(cards[i], 380 + i * 90L, 22);
            if (i < tiles.length) Motion.popIn(tiles[i], 480 + i * 90L, 0.6f);
            // The card artwork settles from a soft zoom while the card rises
            if (i < cardArts.length) Motion.settleZoom(cardArts[i], 420 + i * 90L);
        }
    }

    /**
     * Banner artwork replaced (profile / launcher background decoded): crossfade in. Also
     * finishes a running entrance zoom instead of freezing it half way.
     */
    public static void artSwap(@Nullable View art) {
        if (art == null) return;
        art.animate().cancel();
        if (!Motion.content(art)) {
            art.setAlpha(1f);
            art.setScaleX(1f);
            art.setScaleY(1f);
            return;
        }
        art.setAlpha(Math.min(art.getAlpha(), 0.3f));
        art.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(Motion.LONG)
                .setInterpolator(Motion.ENTER).setListener(null).start();
    }

    /** Light settle when the home screen comes back */
    public static void playReturn(@Nullable View root, @Nullable View playGlint) {
        if (root == null || !Motion.content(root)) return;
        root.animate().cancel();
        root.setAlpha(0.4f);
        root.setScaleX(0.985f);
        root.setScaleY(0.985f);
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0)
                .setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).setListener(null).start();
        glint(playGlint, (View) (playGlint != null ? playGlint.getParent() : null), 180);
    }

    /** Sweeps the light streak across the PLAY face once */
    public static void glint(@Nullable View glint, @Nullable View host, long delay) {
        if (glint == null || host == null || !Motion.content(glint)) return;
        glint.animate().cancel();
        glint.post(() -> {
            int width = host.getWidth();
            if (width == 0) return;
            float start = -glint.getWidth() - Motion.dp(glint, 10);
            glint.setTranslationX(start);
            glint.setAlpha(1f);
            glint.animate().translationX(width + Motion.dp(glint, 10)).setStartDelay(delay)
                    .setDuration(900).setInterpolator(Motion.STANDARD).setListener(null)
                    .withEndAction(() -> glint.setAlpha(0f))
                    .start();
        });
    }

    /** Selected profile card details: icon tile, active dot and chevron pop in after the card */
    public static void profileDetails(@Nullable View tile, @Nullable View chevron, @Nullable View dot) {
        Motion.popIn(tile, 300, 0.6f);
        Motion.popIn(dot, 460, 0.2f);
        Motion.popIn(chevron, 380, 0.5f);
    }

    /** Profile card tapped: the chevron dips down and springs back, then {@code open} */
    public static void profileTapped(@Nullable View chevron, @NonNull Runnable open) {
        if (chevron == null || !Motion.buttons(chevron)) {
            open.run();
            return;
        }
        chevron.animate().cancel();
        chevron.animate().translationY(Motion.dp(chevron, 4)).setStartDelay(0).setDuration(110)
                .setInterpolator(Motion.STANDARD).setListener(null)
                .withEndAction(() -> chevron.animate().translationY(0f).setDuration(260)
                        .setInterpolator(Motion.SPRING).start())
                .start();
        chevron.postDelayed(open, 100);
    }

    /** Another profile got selected: the icon tile bounces */
    public static void profileChanged(@Nullable View tile) {
        Motion.bounce(tile);
    }

    /** Chevron nudge, then {@code open} (immediately when animations are off) */
    public static void cardTapped(@NonNull View card, @Nullable View chevron, @NonNull Runnable open) {
        if (chevron == null || !Motion.buttons(card)) {
            open.run();
            return;
        }
        chevron.animate().cancel();
        chevron.animate().translationX(Motion.dp(chevron, 7)).setStartDelay(0).setDuration(120)
                .setInterpolator(Motion.STANDARD).setListener(null)
                .withEndAction(() -> chevron.animate().translationX(0f).setDuration(260)
                        .setInterpolator(Motion.SPRING).start())
                .start();
        card.postDelayed(open, 110);
    }
}
