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
        layoutCard(card);

        View close = findViewById(R.id.di_close);
        View later = findViewById(R.id.di_later);
        View join = findViewById(R.id.di_join);
        View logo = findViewById(R.id.di_logo_wrap);
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

        // Entrance: logo pops, rings bloom, bubbles drop in, content cascades
        View ringInner = findViewById(R.id.di_ring_inner), ringOuter = findViewById(R.id.di_ring_outer);
        View bubble1 = findViewById(R.id.di_bubble_1), bubble2 = findViewById(R.id.di_bubble_2);
        Motion.popIn(logo, 140L, 0.3f);
        Motion.popIn(ringInner, 200L, 0.4f);
        Motion.popIn(ringOuter, 260L, 0.4f);
        Motion.popIn(bubble1, 420L, 0.6f);
        Motion.popIn(bubble2, 520L, 0.6f);
        Motion.riseIn(findViewById(R.id.di_hero_caption), 480L, 8f);
        Motion.cascadeChildren((ViewGroup) findViewById(R.id.di_body), 200L, 55L, 14f);
        Motion.cascadeChildren((ViewGroup) findViewById(R.id.di_perks), 360L, 70L, 10f);

        // Idle life: logo floats, rings breathe, bubbles bob out of phase
        logo.postDelayed(() -> {
            if (!isShowing()) return;
            floatY(logo, -5f, 2600L, 0L);
            floatY(bubble1, -4f, 3200L, 400L);
            floatY(bubble2, 4f, 2900L, 900L);
            breathe(ringInner, 1.08f, 2200L);
            breathe(ringOuter, 1.12f, 3100L);
            pulse(findViewById(R.id.di_online));
        }, 800L);
    }

    private void floatY(@NonNull View view, float dpAmount, long duration, long delay) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, dpAmount * dp(), 0f);
        anim.setDuration(duration);
        anim.setStartDelay(delay);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.start();
    }

    private static void pulse(@NonNull View dot) {
        ObjectAnimator x = ObjectAnimator.ofFloat(dot, View.SCALE_X, 1f, 1.25f, 1f);
        ObjectAnimator y = ObjectAnimator.ofFloat(dot, View.SCALE_Y, 1f, 1.25f, 1f);
        for (ObjectAnimator animator : new ObjectAnimator[]{x, y}) {
            animator.setDuration(1400L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.start();
        }
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

    /**
     * Wide (landscape, incl. phones): hero left + content right, capped to the screen height.
     * Narrow (portrait / small): hero on top (shorter), content below, scrollable.
     */
    private void layoutCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        float widthDp = metrics.widthPixels / d;
        float heightDp = metrics.heightPixels / d;
        boolean sideBySide = widthDp >= 560f;
        android.widget.LinearLayout root = (android.widget.LinearLayout) card;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        View hero = findViewById(R.id.di_hero);
        View body = findViewById(R.id.di_body);

        if (sideBySide) {
            params.width = Math.min((int) (600 * d), metrics.widthPixels - (int) (32 * d));
            // Phone landscape (~360-400dp tall): fill the height so nothing is cut, body scrolls if needed
            if (heightDp < 420f) params.height = metrics.heightPixels - (int) (20 * d);
        } else {
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            params.width = Math.min((int) (400 * d), metrics.widthPixels - (int) (28 * d));
            hero.setBackgroundResource(R.drawable.bg_di_hero_top);
            android.widget.LinearLayout.LayoutParams hp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (150 * d));
            hero.setLayoutParams(hp);
            hero.setMinimumHeight(0);
            // Rings would spill out of a 150dp strip
            findViewById(R.id.di_ring_outer).setVisibility(View.GONE);
            findViewById(R.id.di_hero_caption).setVisibility(View.GONE);
            android.widget.LinearLayout.LayoutParams bp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            body.setLayoutParams(bp);
            body.setPadding((int) (20 * d), (int) (16 * d), (int) (16 * d), (int) (14 * d));
        }
        card.setLayoutParams(params);

        // Body scrolls when the screen is really short; the rest of the card stays put
        if (heightDp < 420f) {
            ViewGroup parent = (ViewGroup) body.getParent();
            int index = parent.indexOfChild(body);
            ViewGroup.LayoutParams bodyParams = body.getLayoutParams();
            parent.removeView(body);
            android.widget.ScrollView scroll = new android.widget.ScrollView(getContext());
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            parent.addView(scroll, index, bodyParams);
        }
    }

}
