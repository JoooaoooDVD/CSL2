package com.craftstudio.cslauncher.about;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * First-launch privacy & terms screen. Shown once as a full-screen overlay on top of the
 * launcher UI until the user taps "I agree"; back is swallowed while it is up.
 */
public final class WelcomePolicy {
    private static final String PREF_KEY = "welcomePolicyAcceptedV1";

    private WelcomePolicy() {}

    public static boolean isAccepted() {
        return LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_KEY, false);
    }

    /** Adds the overlay if it has not been accepted yet. Safe to call on every launch. */
    public static void showIfNeeded(@NonNull Activity activity) {
        if (isAccepted()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.findViewById(R.id.policy_root) != null) return;

        View root = LayoutInflater.from(activity).inflate(R.layout.fragment_policy, content, false);
        content.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();

        PolicyBinder.bind(root, true, () -> {
            LauncherPreferences.DEFAULT_PREF.edit().putBoolean(PREF_KEY, true).apply();
            root.animate().alpha(0f).scaleX(1.03f).scaleY(1.03f)
                    .setDuration(Motion.MEDIUM).setInterpolator(Motion.EXIT)
                    .withEndAction(() -> content.removeView(root)).start();
        });

        // Swallow back while the policy is up
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        root.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
    }
}
