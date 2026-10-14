package com.kdt.mcgui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Community invite. Shown on a schedule rather than once: the first launch (after setup), then
 * launch 7, then every 8th launch after that (15, 23, ...), never twice within a day, and never
 * again once the user actually tapped Join.
 */
public class DiscordInviteDialog extends CSPopupDialog {
    private static final String PREF_LAUNCHES = "discordInviteLaunches";
    private static final String PREF_LAST_SHOWN = "discordInviteLastShown";
    private static final String PREF_JOINED = "discordInviteJoined";
    private static final String PREF_LEGACY_SHOWN = "discordInviteShownV1";
    private static final int SECOND_SHOW = 7, PERIOD = 8;
    private static final long MIN_GAP = 24L * 60 * 60 * 1000;
    private static boolean sCountedThisProcess;

    private static SharedPreferences prefs(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** Counts a launcher start (once per process). Call early, before {@link #isDue}. */
    public static void countLaunch(@NonNull Context context) {
        if (sCountedThisProcess) return;
        sCountedThisProcess = true;
        SharedPreferences p = prefs(context);
        int launches = p.getInt(PREF_LAUNCHES, 0);
        // Installs that already saw the v1 one-time popup continue as if that was launch 1
        if (launches == 0 && p.getBoolean(PREF_LEGACY_SHOWN, false)) launches = 1;
        p.edit().putInt(PREF_LAUNCHES, launches + 1).apply();
    }

    /** Whether this launch is one where the invite should appear. */
    public static boolean isDue(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(PREF_JOINED, false)) return false;
        int launch = p.getInt(PREF_LAUNCHES, 0);
        if (launch <= 0) return false;
        long last = p.getLong(PREF_LAST_SHOWN, 0L);
        boolean scheduled = launch == 1 || launch == SECOND_SHOW
                || (launch > SECOND_SHOW && (launch - SECOND_SHOW) % PERIOD == 0)
                // Never shown yet (e.g. the first launch restarted into a custom storage folder)
                || (last == 0L && launch <= 3);
        if (!scheduled) return false;
        return System.currentTimeMillis() - last >= MIN_GAP;
    }

    /** Shows the invite when it is due on this launch (at most once per launch). */
    public static void showIfDue(@NonNull Context context) {
        if (!isDue(context)) return;
        prefs(context).edit().putLong(PREF_LAST_SHOWN, System.currentTimeMillis()).putBoolean(PREF_LEGACY_SHOWN, true).apply();
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
            prefs(getContext()).edit().putBoolean(PREF_JOINED, true).apply();
            CreatePopupMotion.optionChosen(join, () -> close(() -> {
                android.app.Activity activity = findActivity(getContext());
                if (activity != null) Tools.openURL(activity, getContext().getString(R.string.di_invite_url));
            }));
        });
        for (View v : new View[]{close, later, join}) CSAnimationEngine.attachPressFeedback(v);

        Motion.popIn(logo, 160L, 0.3f);
        Motion.popIn(findViewById(R.id.di_ring_inner), 220L, 0.4f);
        Motion.popIn(findViewById(R.id.di_ring_outer), 280L, 0.4f);
        Motion.cascadeChildren((ViewGroup) findViewById(R.id.di_body), 220L, 55L, 14f);

        // Idle life: the logo floats, the rings breathe
        logo.postDelayed(() -> {
            if (!isShowing()) return;
            ObjectAnimator floatAnim = ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, 0f, -5f * dp(), 0f);
            floatAnim.setDuration(2600L);
            floatAnim.setRepeatCount(ValueAnimator.INFINITE);
            floatAnim.setInterpolator(new AccelerateDecelerateInterpolator());
            floatAnim.start();
            breathe(findViewById(R.id.di_ring_inner), 1.08f, 2200L);
            breathe(findViewById(R.id.di_ring_outer), 1.12f, 3100L);
        }, 700L);
    }

    private static void breathe(@NonNull View view, float to, long duration) {
        ObjectAnimator x = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, to, 1f);
        ObjectAnimator y = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, to, 1f);
        for (ObjectAnimator animator : new ObjectAnimator[]{x, y}) {
            animator.setDuration(duration);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.start();
        }
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (400 * d), metrics.widthPixels - (int) (40 * d));
        card.setLayoutParams(params);
    }
}
