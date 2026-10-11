package com.craftstudio.cslauncher.utils.motion;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Motion of the "create instance" popups (type chooser + create form).
 * <ul>
 *   <li>enter: scrim fades in, the card rises and scales up from 93%, then the content
 *       groups cascade in (header first, option cards pop one after another);</li>
 *   <li>exit: card shrinks a little and fades, scrim fades, then the dialog goes away;</li>
 *   <li>feedback: chosen option squish, "coming soon" shake, scrim tap nudge, loader chip
 *       bounce, busy overlay cross-fade and form sections sliding when the loader changes.</li>
 * </ul>
 * Only GPU friendly properties (alpha / scale / translation); everything is skipped when the
 * content animations are turned off in the launcher settings.
 */
public final class CreatePopupMotion {

    private CreatePopupMotion() {}

    public static void enter(@NonNull View scrim, @NonNull View card) {
        if (!Motion.content(card)) return;
        scrim.animate().cancel();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setStartDelay(0).setDuration(220).setInterpolator(Motion.STANDARD)
                .setListener(null).start();

        card.animate().cancel();
        card.setAlpha(0f);
        card.setScaleX(0.93f);
        card.setScaleY(0.93f);
        card.setTranslationY(Motion.dp(card, 18));
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(30)
                .setDuration(Motion.MEDIUM + 60).setInterpolator(Motion.ENTER).setListener(null)
                .withLayer().start();
    }

    /** Chooser: header rises, the option cards pop in one after another, footer last */
    public static void chooserContent(@Nullable View header, @NonNull View[] options, @Nullable View footer) {
        Motion.riseIn(header, 90, 10);
        for (int i = 0; i < options.length; i++) {
            View option = options[i];
            if (option == null) continue;
            Motion.riseIn(option, 150 + i * 70L, 22, Motion.MEDIUM + 120);
        }
        Motion.fadeIn(footer, 150 + options.length * 70L + 60, Motion.MEDIUM);
    }

    /** Option banners settle from a slight zoom (separate views from the cards themselves) */
    public static void optionArts(@NonNull View[] arts) {
        for (int i = 0; i < arts.length; i++) Motion.settleZoom(arts[i], 160 + i * 70L);
    }

    /** Form: header, then each column's sections cascade, footer buttons rise */
    public static void formContent(@Nullable View header, @Nullable ViewGroup left,
                                   @Nullable ViewGroup right, @Nullable View footer) {
        Motion.riseIn(header, 80, 10);
        Motion.cascadeChildren(left, 140, 45, 12);
        Motion.cascadeChildren(right, 200, 45, 12);
        Motion.riseIn(footer, 260, 12);
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
        card.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).translationY(Motion.dp(card, 10))
                .setStartDelay(0).setDuration(Motion.SHORT).setInterpolator(Motion.EXIT).setListener(null)
                .withLayer().start();
        scrim.animate().cancel();
        scrim.animate().alpha(0f).setStartDelay(40).setDuration(Motion.SHORT + 30)
                .setInterpolator(Motion.STANDARD).setListener(null)
                .withEndAction(end).start();
    }

    /** Tapped option: quick press-in, then {@code then} (the popup closes right after) */
    public static void optionChosen(@NonNull View option, @NonNull Runnable then) {
        if (!Motion.buttons(option)) {
            then.run();
            return;
        }
        option.animate().cancel();
        option.animate().scaleX(0.95f).scaleY(0.95f).setStartDelay(0).setDuration(90)
                .setInterpolator(Motion.STANDARD).setListener(null)
                .withEndAction(() -> {
                    option.animate().scaleX(1f).scaleY(1f).setDuration(160)
                            .setInterpolator(Motion.SPRING).start();
                    then.run();
                })
                .start();
    }

    /** "Not available yet": the card shakes and its badge pulses */
    public static void comingSoon(@NonNull View option, @Nullable View badge) {
        // A tap during the entrance must not freeze the card half faded
        option.animate().cancel();
        option.setAlpha(1f);
        option.setTranslationY(0f);
        option.setScaleX(1f);
        option.setScaleY(1f);
        Motion.shake(option);
        Motion.pulse(badge);
    }

    /** Scrim tapped while the popup refuses to close (busy): a tiny scale nudge */
    public static void nudge(@NonNull View card) {
        if (!Motion.buttons(card)) return;
        card.animate().cancel();
        card.animate().scaleX(1.015f).scaleY(1.015f).setStartDelay(0).setDuration(90)
                .setInterpolator(Motion.STANDARD).setListener(null)
                .withEndAction(() -> card.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(Motion.SPRING).start())
                .start();
    }

    /** The loader changed: chip bounce + the version rows re-enter from the side */
    public static void loaderChanged(@Nullable View chip, @Nullable View... rows) {
        Motion.bounce(chip);
        if (rows == null) return;
        for (int i = 0; i < rows.length; i++) {
            View row = rows[i];
            if (row == null || row.getVisibility() != View.VISIBLE) continue;
            Motion.slideInX(row, i * 50L, 14);
        }
    }

    /** Busy overlay inside the card */
    public static void showBusy(@NonNull View overlay) {
        overlay.setVisibility(View.VISIBLE);
        Motion.fadeIn(overlay, 0, Motion.SHORT + 40);
    }

    public static void hideBusy(@NonNull View overlay) {
        Motion.hide(overlay, true);
    }

    /** Dropdown values appearing after a load */
    public static void valueLoaded(@Nullable View value) {
        Motion.fadeIn(value, 0, Motion.SHORT + 60);
    }
}
