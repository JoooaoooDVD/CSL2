package com.craftstudio.cslauncher.tutorial;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.SettingsPopup;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Contextual help: the top bar "?" button opens a light explanation of the visible screen
 * (read-only, no commitment) with a button to start its interactive tutorial, plus small
 * non-blocking hint chips ("Learn this screen", "Resume tutorial") that disappear by themselves.
 */
public final class ContextualHelp {
    private static final long HINT_DURATION_MS = 6000L;

    private ContextualHelp() {}

    /** The "?" button of the top bar */
    public static void onHelpPressed(@NonNull LauncherActivity activity) {
        TutorialManager manager = TutorialManager.getInstance();
        if (manager.isRunning()) {
            Toast.makeText(activity, R.string.tut_help_already_running, Toast.LENGTH_SHORT).show();
            return;
        }
        String id = manager.tutorialForCurrentScreen();
        TutorialDefinition def = TutorialRegistry.get(id);
        if (def == null || !def.isAvailable(activity)) {
            TutorialCenterFragment.open(activity);
            return;
        }
        showExplanation(activity, def);
    }

    /** Lightweight explanation mode: the module's description, then optionally the real tutorial */
    public static void showExplanation(@NonNull LauncherActivity activity, @NonNull TutorialDefinition def) {
        boolean done = TutorialState.isCompleted(activity, def.id);
        SettingsPopup popup = new SettingsPopup(activity)
                .title(activity.getString(def.title))
                .subtitle(activity.getString(done ? R.string.tut_center_status_done : R.string.tut_help_subtitle,
                        def.steps().size()))
                .message(activity.getText(def.description))
                .positive(done ? R.string.tut_center_replay : R.string.tut_help_start, () -> start(activity, def.id))
                .negative(R.string.tut_help_all, () -> TutorialCenterFragment.open(activity));
        popup.show();
    }

    private static void start(@NonNull LauncherActivity activity, @NonNull String id) {
        TutorialState.clearTutorial(activity, id);
        if (!TutorialManager.getInstance().start(id)) {
            Toast.makeText(activity, R.string.tut_fallback_cannot_start, Toast.LENGTH_SHORT).show();
        }
    }

    // ───────────────────────────── hints ─────────────────────────────

    /** Screen resumed: once per screen, offer its tutorial without interrupting anything */
    static void onScreenShown(@NonNull LauncherActivity activity, @NonNull String fragmentSimpleName) {
        if (!TutorialState.isOnboardingDone(activity) || !TutorialState.showTips(activity)) return;
        if (TutorialManager.getInstance().isRunning()) return;
        String id = tipFor(fragmentSimpleName);
        if (id == null || TutorialState.isCompleted(activity, id) || TutorialState.wasTipShown(activity, id)) return;
        TutorialDefinition def = TutorialRegistry.get(id);
        if (def == null || !def.isAvailable(activity)) return;
        TutorialState.setTipShown(activity, id);
        showHint(activity, activity.getString(R.string.tut_tip_learn, activity.getString(def.title)),
                activity.getString(R.string.tut_tip_start), () -> start(activity, id));
    }

    @Nullable
    private static String tipFor(@NonNull String fragment) {
        switch (fragment) {
            case "ModsSearchFragment": return TutorialRegistry.BROWSER;
            case "InstanceEditorFragment": return TutorialRegistry.PROFILES;
            case "SettingsHostFragment": return TutorialRegistry.SETTINGS;
            case "VirtualLanFragment": return TutorialRegistry.MULTIPLAYER;
            case "LogsFragment": return TutorialRegistry.LOGS;
            case "ManageWorldsFragment": return TutorialRegistry.WORLDS;
            default: return null;
        }
    }

    /** An interrupted tutorial can be picked up where it stopped */
    static void showResumeHint(@NonNull LauncherActivity activity) {
        TutorialManager manager = TutorialManager.getInstance();
        TutorialState.ActiveRun run = TutorialState.getActiveRun(activity);
        TutorialDefinition def = run == null ? null : TutorialRegistry.get(run.tutorialId);
        if (def == null) return;
        showHint(activity, activity.getString(R.string.tut_tip_resume, activity.getString(def.title)),
                activity.getString(R.string.tut_center_resume), manager::resumeSaved);
    }

    private static void showHint(@NonNull LauncherActivity activity, @NonNull CharSequence text,
                                 @NonNull CharSequence action, @NonNull Runnable onAction) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.findViewById(R.id.tut_hint_root) != null) return;
        View hint = LayoutInflater.from(activity).inflate(R.layout.tutorial_hint, content, false);
        ((TextView) hint.findViewById(R.id.tut_hint_text)).setText(text);
        TextView button = hint.findViewById(R.id.tut_hint_action);
        button.setText(action);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Math.round(18 * activity.getResources().getDisplayMetrics().density);
        content.addView(hint, lp);
        Runnable dismiss = new Runnable() {
            boolean done;
            @Override
            public void run() {
                if (done) return;
                done = true;
                hint.removeCallbacks(this);
                if (Motion.content(hint)) {
                    hint.animate().alpha(0f).translationY(hint.getHeight() / 3f).setDuration(Motion.SHORT)
                            .withEndAction(() -> content.removeView(hint)).start();
                } else {
                    content.removeView(hint);
                }
            }
        };
        button.setOnClickListener(v -> {
            dismiss.run();
            onAction.run();
        });
        hint.findViewById(R.id.tut_hint_close).setOnClickListener(v -> dismiss.run());
        if (Motion.content(hint)) {
            hint.setAlpha(0f);
            hint.setTranslationY(16 * activity.getResources().getDisplayMetrics().density);
            hint.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
        }
        hint.postDelayed(dismiss, HINT_DURATION_MS);
    }
}
