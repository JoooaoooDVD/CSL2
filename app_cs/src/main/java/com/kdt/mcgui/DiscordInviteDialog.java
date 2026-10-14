package com.kdt.mcgui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

/** One-time community invite: close in the corner, Join Discord at the bottom. */
public class DiscordInviteDialog extends CSPopupDialog {
    private static final String PREF_SHOWN = "discordInviteShownV1";

    public static boolean wasShown(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_SHOWN, false);
    }

    /** Shows the invite once per install. */
    public static void showIfNeeded(@NonNull Context context) {
        if (wasShown(context)) return;
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PREF_SHOWN, true).apply();
        new DiscordInviteDialog(context).show();
    }

    public DiscordInviteDialog(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_discord_invite);
        View card = findViewById(R.id.di_card);
        setupPopup(findViewById(R.id.di_scrim), card, true);
        sizeCard(card);

        View close = findViewById(R.id.di_close);
        View later = findViewById(R.id.di_later);
        View join = findViewById(R.id.di_join);
        View logo = findViewById(R.id.di_logo);
        close.setOnClickListener(v -> onDismissRequested());
        later.setOnClickListener(v -> onDismissRequested());
        join.setOnClickListener(v -> {
            Motion.bounce(logo);
            CreatePopupMotion.optionChosen(join, () -> close(() -> {
                android.app.Activity activity = findActivity(getContext());
                if (activity != null) Tools.openURL(activity, getContext().getString(R.string.di_invite_url));
            }));
        });
        for (View v : new View[]{close, later, join}) CSAnimationEngine.attachPressFeedback(v);

        Motion.popIn(logo, 160L, 0.3f);
        Motion.cascadeChildren((ViewGroup) findViewById(R.id.di_body), 220L, 60L, 14f);
        // The logo floats a little while the popup is up
        logo.postDelayed(() -> {
            if (!isShowing()) return;
            android.animation.ObjectAnimator floatAnim = android.animation.ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, 0f, -5f * dp(), 0f);
            floatAnim.setDuration(2600L);
            floatAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            floatAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            floatAnim.start();
        }, 700L);
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (400 * d), metrics.widthPixels - (int) (40 * d));
        card.setLayoutParams(params);
    }
}
