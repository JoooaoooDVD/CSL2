package com.craftstudio.cslauncher.tutorial;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistence of the onboarding / tutorial progress.
 * <p>
 * Lives in its own SharedPreferences file ({@code cs_tutorial}) on purpose: resetting the tutorial
 * progress must never touch launcher settings, and launcher settings resets must never bring the
 * first-time setup back. Every read is a cheap in-memory SharedPreferences lookup, writes use
 * {@code apply()} so nothing blocks the UI thread. Corrupted / outdated values fall back to
 * defaults (the getters never throw).
 */
public final class TutorialState {
    private static final String FILE = "cs_tutorial";
    /** Bumped when the stored format changes; older data is dropped instead of misread */
    private static final int SCHEMA = 2;

    private static final String KEY_SCHEMA = "schema";
    private static final String KEY_ONBOARDING_STARTED = "onboarding_started";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    private static final String KEY_ONBOARDING_SKIPPED = "onboarding_skipped";
    private static final String KEY_SETUP_PAGE = "setup_page";
    private static final String KEY_SETUP_MODE = "setup_mode";
    private static final String KEY_INTERACTION = "interaction_mode";
    private static final String KEY_SHOW_TIPS = "show_tips";
    private static final String KEY_ACTIVE_ID = "active_id";
    private static final String KEY_ACTIVE_STEP = "active_step";
    private static final String KEY_ACTIVE_SEQUENCE = "active_sequence";
    private static final String KEY_ACTIVE_SEQUENCE_INDEX = "active_sequence_index";
    private static final String KEY_ACTIVE_IS_ONBOARDING = "active_is_onboarding";
    private static final String KEY_ACTIVE_PAUSED = "active_paused";
    private static final String KEY_SETUP_THEME = "setup_theme";
    private static final String PREFIX_DONE = "done:";
    private static final String PREFIX_PROGRESS = "progress:";
    private static final String PREFIX_TIP = "tip:";

    public static final String MODE_QUICK = "quick";
    public static final String MODE_COMPLETE = "complete";
    public static final String INTERACTION_INTERACTIVE = "interactive";
    public static final String INTERACTION_GUIDED = "guided";

    private static SharedPreferences sPrefs;

    private TutorialState() {}

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        SharedPreferences prefs = sPrefs;
        if (prefs == null) {
            prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
            int schema = prefs.getInt(KEY_SCHEMA, 0);
            if (schema != SCHEMA) {
                // Outdated or never written: start clean, but never lose "onboarding done"
                boolean done = schema != 0 && prefs.getBoolean(KEY_ONBOARDING_DONE, false);
                prefs.edit().clear().putInt(KEY_SCHEMA, SCHEMA).putBoolean(KEY_ONBOARDING_DONE, done).apply();
            }
            sPrefs = prefs;
        }
        return prefs;
    }

    // ───────────────────────────── onboarding ─────────────────────────────

    public static boolean isOnboardingDone(@NonNull Context c) {
        return prefs(c).getBoolean(KEY_ONBOARDING_DONE, false);
    }

    public static boolean isOnboardingStarted(@NonNull Context c) {
        return prefs(c).getBoolean(KEY_ONBOARDING_STARTED, false);
    }

    /** True when the user left the first-time setup through Skip / Explore instead of finishing it */
    public static boolean wasOnboardingSkipped(@NonNull Context c) {
        return prefs(c).getBoolean(KEY_ONBOARDING_SKIPPED, false);
    }

    public static void setOnboardingStarted(@NonNull Context c) {
        prefs(c).edit().putBoolean(KEY_ONBOARDING_STARTED, true).apply();
    }

    /** Counts how often the welcome card was shown without a choice; returns the new count */
    public static int bumpWelcomeShown(@NonNull Context c) {
        int n = prefs(c).getInt("welcome_shown", 0) + 1;
        prefs(c).edit().putInt("welcome_shown", n).apply();
        return n;
    }

    public static void setOnboardingDone(@NonNull Context c, boolean skipped) {
        prefs(c).edit()
                .putBoolean(KEY_ONBOARDING_DONE, true)
                .putBoolean(KEY_ONBOARDING_SKIPPED, skipped)
                .putInt(KEY_SETUP_PAGE, 0)
                .apply();
    }

    /** Page of the first-time setup wizard to show again after the activity was recreated */
    public static int getSetupPage(@NonNull Context c) {
        return prefs(c).getInt(KEY_SETUP_PAGE, 0);
    }

    public static void setSetupPage(@NonNull Context c, int page) {
        prefs(c).edit().putInt(KEY_SETUP_PAGE, page).apply();
    }

    @NonNull
    public static String getSetupMode(@NonNull Context c) {
        String mode = prefs(c).getString(KEY_SETUP_MODE, MODE_QUICK);
        return MODE_COMPLETE.equals(mode) ? MODE_COMPLETE : MODE_QUICK;
    }

    public static void setSetupMode(@NonNull Context c, @NonNull String mode) {
        prefs(c).edit().putString(KEY_SETUP_MODE, mode).apply();
    }

    /** Interactive = the user taps the real controls; guided = Next only */
    public static boolean isInteractive(@NonNull Context c) {
        return !INTERACTION_GUIDED.equals(prefs(c).getString(KEY_INTERACTION, INTERACTION_INTERACTIVE));
    }

    public static void setInteractive(@NonNull Context c, boolean interactive) {
        prefs(c).edit().putString(KEY_INTERACTION, interactive ? INTERACTION_INTERACTIVE : INTERACTION_GUIDED).apply();
    }

    public static boolean showTips(@NonNull Context c) {
        return prefs(c).getBoolean(KEY_SHOW_TIPS, true);
    }

    public static void setShowTips(@NonNull Context c, boolean show) {
        prefs(c).edit().putBoolean(KEY_SHOW_TIPS, show).apply();
    }

    /** Theme chosen on the setup appearance page, only used by the completion summary */
    @Nullable
    public static String getSetupTheme(@NonNull Context c) {
        return prefs(c).getString(KEY_SETUP_THEME, null);
    }

    public static void setSetupTheme(@NonNull Context c, @Nullable String theme) {
        prefs(c).edit().putString(KEY_SETUP_THEME, theme).apply();
    }

    // ───────────────────────────── per tutorial ─────────────────────────────

    public static boolean isCompleted(@NonNull Context c, @NonNull String tutorialId) {
        return prefs(c).getBoolean(PREFIX_DONE + tutorialId, false);
    }

    public static void setCompleted(@NonNull Context c, @NonNull String tutorialId) {
        prefs(c).edit().putBoolean(PREFIX_DONE + tutorialId, true).remove(PREFIX_PROGRESS + tutorialId).apply();
    }

    /** Highest step index reached in an unfinished run, -1 when never started */
    public static int getProgress(@NonNull Context c, @NonNull String tutorialId) {
        return prefs(c).getInt(PREFIX_PROGRESS + tutorialId, -1);
    }

    public static void setProgress(@NonNull Context c, @NonNull String tutorialId, int step) {
        if (isCompleted(c, tutorialId)) return;
        int previous = getProgress(c, tutorialId);
        if (step > previous) prefs(c).edit().putInt(PREFIX_PROGRESS + tutorialId, step).apply();
    }

    public static void clearTutorial(@NonNull Context c, @NonNull String tutorialId) {
        prefs(c).edit().remove(PREFIX_DONE + tutorialId).remove(PREFIX_PROGRESS + tutorialId).apply();
    }

    public static boolean wasTipShown(@NonNull Context c, @NonNull String tutorialId) {
        return prefs(c).getBoolean(PREFIX_TIP + tutorialId, false);
    }

    public static void setTipShown(@NonNull Context c, @NonNull String tutorialId) {
        prefs(c).edit().putBoolean(PREFIX_TIP + tutorialId, true).apply();
    }

    // ───────────────────────────── active run ─────────────────────────────

    /** Saved position of an interrupted run so it can be resumed after a restart */
    public static final class ActiveRun {
        @NonNull public final String tutorialId;
        public final int step;
        @NonNull public final List<String> sequence;
        public final int sequenceIndex;
        public final boolean onboarding;

        ActiveRun(@NonNull String tutorialId, int step, @NonNull List<String> sequence, int sequenceIndex, boolean onboarding) {
            this.tutorialId = tutorialId;
            this.step = step;
            this.sequence = sequence;
            this.sequenceIndex = sequenceIndex;
            this.onboarding = onboarding;
        }
    }

    @Nullable
    public static ActiveRun getActiveRun(@NonNull Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString(KEY_ACTIVE_ID, null);
        if (id == null || id.isEmpty()) return null;
        String seq = p.getString(KEY_ACTIVE_SEQUENCE, "");
        List<String> sequence = new ArrayList<>();
        if (seq != null && !seq.isEmpty()) {
            for (String s : seq.split(",")) if (!s.isEmpty()) sequence.add(s);
        }
        return new ActiveRun(id, Math.max(0, p.getInt(KEY_ACTIVE_STEP, 0)), sequence,
                Math.max(0, p.getInt(KEY_ACTIVE_SEQUENCE_INDEX, 0)), p.getBoolean(KEY_ACTIVE_IS_ONBOARDING, false));
    }

    public static void setActiveRun(@NonNull Context c, @NonNull String tutorialId, int step,
                                    @Nullable List<String> sequence, int sequenceIndex, boolean onboarding) {
        StringBuilder seq = new StringBuilder();
        if (sequence != null) {
            for (String s : sequence) {
                if (seq.length() > 0) seq.append(',');
                seq.append(s);
            }
        }
        prefs(c).edit()
                .putString(KEY_ACTIVE_ID, tutorialId)
                .putInt(KEY_ACTIVE_STEP, step)
                .putString(KEY_ACTIVE_SEQUENCE, seq.toString())
                .putInt(KEY_ACTIVE_SEQUENCE_INDEX, sequenceIndex)
                .putBoolean(KEY_ACTIVE_IS_ONBOARDING, onboarding)
                .apply();
    }

    public static void clearActiveRun(@NonNull Context c) {
        prefs(c).edit()
                .remove(KEY_ACTIVE_ID).remove(KEY_ACTIVE_STEP).remove(KEY_ACTIVE_SEQUENCE)
                .remove(KEY_ACTIVE_SEQUENCE_INDEX).remove(KEY_ACTIVE_IS_ONBOARDING).remove(KEY_ACTIVE_PAUSED)
                .apply();
    }

    /** Paused = the user left on purpose (Exit / Back); not paused = interrupted (recreation, process death) */
    public static boolean isActivePaused(@NonNull Context c) {
        return prefs(c).getBoolean(KEY_ACTIVE_PAUSED, false);
    }

    public static void setActivePaused(@NonNull Context c, boolean paused) {
        prefs(c).edit().putBoolean(KEY_ACTIVE_PAUSED, paused).apply();
    }

    // ───────────────────────────── reset ─────────────────────────────

    /**
     * Forgets every tutorial completion, progress, tip and interrupted run. Launcher settings,
     * profiles, accounts and game files are untouched (they are not even in this file). Keeps
     * the user's tutorial preferences (interaction mode, tips) and the "onboarding done" flag so
     * the first-time setup does not pop up by itself again; the Tutorial Center can restart it.
     */
    public static void resetProgress(@NonNull Context c, @NonNull List<String> tutorialIds) {
        SharedPreferences.Editor editor = prefs(c).edit();
        for (String id : tutorialIds) {
            editor.remove(PREFIX_DONE + id).remove(PREFIX_PROGRESS + id).remove(PREFIX_TIP + id);
        }
        editor.remove(KEY_ACTIVE_ID).remove(KEY_ACTIVE_STEP).remove(KEY_ACTIVE_SEQUENCE)
                .remove(KEY_ACTIVE_SEQUENCE_INDEX).remove(KEY_ACTIVE_IS_ONBOARDING).remove(KEY_ACTIVE_PAUSED).remove(KEY_SETUP_PAGE);
        editor.apply();
    }

    /** Used by "Restart first-time setup": the wizard shows again from its first page */
    public static void restartOnboarding(@NonNull Context c) {
        prefs(c).edit()
                .putBoolean(KEY_ONBOARDING_DONE, false)
                .putBoolean(KEY_ONBOARDING_SKIPPED, false)
                .putBoolean(KEY_ONBOARDING_STARTED, false)
                .putInt(KEY_SETUP_PAGE, 0)
                .putInt("welcome_shown", 0)
                .apply();
    }
}
