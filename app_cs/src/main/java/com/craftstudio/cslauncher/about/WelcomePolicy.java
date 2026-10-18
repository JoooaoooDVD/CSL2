package com.craftstudio.cslauncher.about;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * First-launch privacy & terms screen. Shown once as a full-screen overlay on top of the
 * launcher UI until the user taps "I agree"; back is swallowed while it is up.
 */
public final class WelcomePolicy {
    private static final String PREF_KEY = "welcomePolicyAcceptedV2";

    private WelcomePolicy() {}

    public static boolean isAccepted() {
        return LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_KEY, false);
    }

    /** Adds the overlay if it has not been accepted yet. Safe to call on every launch. */
    public static void showIfNeeded(@NonNull Activity activity) {
        showIfNeeded(activity, null);
    }

    /** Same, with a callback once the user accepted (used to chain the storage setup). */
    public static void showIfNeeded(@NonNull Activity activity, @Nullable Runnable onAccepted) {
        if (isAccepted()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.findViewById(R.id.policy_root) != null) return;

        View root = LayoutInflater.from(activity).inflate(R.layout.fragment_policy, content, false);
        content.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Full-bleed page: the sheet fills the whole window. (With the card inset by a margin on a
        // translucent scrim, the launcher showed through as dark bands at the top and bottom.)
        root.setBackgroundColor(0xFF0F1218);
        View card = root.findViewById(R.id.policy_card);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
        lp.setMargins(0, 0, 0, 0);
        card.setLayoutParams(lp);
        card.setBackground(null);
        card.setElevation(0f);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
        View header = root.findViewById(R.id.policy_header);
        View scroll = root.findViewById(R.id.policy_scroll);
        View footer = root.findViewById(R.id.policy_footer);
        float d = activity.getResources().getDisplayMetrics().density;
        for (View v : new View[]{header, scroll, footer}) {
            if (v == null) continue;
            v.setAlpha(0f);
            v.setTranslationY(18f * d);
        }
        long delay = 80L;
        for (View v : new View[]{header, scroll, footer}) {
            if (v == null) continue;
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(Motion.LONG).setInterpolator(Motion.ENTER).start();
            delay += 70L;
        }

        PolicyBinder.bind(root, true, () -> {
            LauncherPreferences.DEFAULT_PREF.edit().putBoolean(PREF_KEY, true).apply();
            root.animate().alpha(0f)
                    .setDuration(Motion.MEDIUM).setInterpolator(Motion.EXIT)
                    .withEndAction(() -> {
                        content.removeView(root);
                        if (onAccepted != null) onAccepted.run();
                    }).start();
        });

        // Swallow back while the policy is up
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        root.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
    }
}
