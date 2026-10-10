package com.craftstudio.cslauncher.utils.motion;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Motion engine of the profile editor (InstanceEditorFragment).
 * <ul>
 *   <li>entrance: back button pops, the title slides in, the cards rise one after another,
 *       the icon preview springs in, the icon strip cascades, the banner settles from a
 *       slight zoom and the action bar glides up from the bottom;</li>
 *   <li>icons: the picked chip squishes and springs, the big preview flips in with the
 *       new icon;</li>
 *   <li>banner: new artwork fades in from a soft zoom;</li>
 *   <li>fields: focused fields lift, changed values slide in, invalid input shakes.</li>
 * </ul>
 */
public final class ProfileEditorMotion {

    private ProfileEditorMotion() {}

    public static void playEntrance(@Nullable View back, @Nullable View title, @Nullable View subtitle,
                                    @Nullable View[] cards, @Nullable View iconPreview,
                                    @Nullable ViewGroup iconStrip, @Nullable View banner,
                                    @Nullable ViewGroup configRows, @Nullable View actionBar) {
        Motion.popIn(back, 0, 0.6f);
        Motion.slideInX(title, 40, -26);
        Motion.slideInX(subtitle, 100, -18);
        if (cards != null) {
            for (int i = 0; i < cards.length; i++) Motion.riseIn(cards[i], 110 + i * 75L, 28);
        }
        Motion.popIn(iconPreview, 260, 0.6f);
        Motion.popChildren(iconStrip, 280, 45, 0.55f);
        Motion.settleZoom(banner, 260);
        Motion.cascadeChildren(configRows, 380, 60, 14);
        if (actionBar != null) {
            if (Motion.content(actionBar)) {
                actionBar.animate().cancel();
                actionBar.setAlpha(0f);
                actionBar.setTranslationY(Motion.dp(actionBar, 48));
                actionBar.animate().alpha(1f).translationY(0f).setStartDelay(220)
                        .setDuration(Motion.LONG).setInterpolator(Motion.ENTER).setListener(null).start();
            } else {
                Motion.settle(actionBar);
            }
        }
    }

    /** The chosen icon chip springs */
    public static void chipSelected(@Nullable View chip) {
        Motion.bounce(chip);
    }

    /** The big preview flips in with its new image */
    public static void iconChanged(@Nullable ImageView preview) {
        if (preview == null) return;
        if (!Motion.content(preview)) {
            Motion.settle(preview);
            return;
        }
        preview.animate().cancel();
        preview.setCameraDistance(8000 * preview.getResources().getDisplayMetrics().density);
        preview.setRotationY(-75f);
        preview.setScaleX(0.85f);
        preview.setScaleY(0.85f);
        preview.setAlpha(0.4f);
        preview.animate().rotationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(Motion.LONG).setInterpolator(Motion.SPRING).setListener(null).start();
    }

    public static void bannerChanged(@Nullable ImageView banner) {
        Motion.settleZoom(banner, 0);
    }

    public static void fieldFocus(@Nullable View field, boolean focused) {
        Motion.lift(field, focused);
    }

    public static void valueChanged(@Nullable TextView value, CharSequence text) {
        Motion.swapText(value, text);
    }

    public static void invalid(@Nullable View field) {
        Motion.shake(field);
    }
}
