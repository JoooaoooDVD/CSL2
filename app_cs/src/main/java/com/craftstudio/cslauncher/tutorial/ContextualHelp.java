package com.craftstudio.cslauncher.tutorial;

import android.widget.Toast;

import androidx.annotation.NonNull;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.SettingsPopup;

/**
 * The "?" button in the top bar: offers the guide that explains the current screen, or the
 * Tutorials screen. Nothing shows on its own — no automatic tips.
 */
public final class ContextualHelp {
    private ContextualHelp() {}

    public static void onHelpPressed(@NonNull LauncherActivity activity) {
        TutorialManager manager = TutorialManager.getInstance();
        if (manager.isRunning()) {
            Toast.makeText(activity, R.string.tut_help_already_running, Toast.LENGTH_SHORT).show();
            return;
        }
        if (FirstLaunchSetup.isPending(activity)) return;
        TutorialDefinition def = TutorialRegistry.get(TutorialRegistry.tutorialForScreen(activity));
        if (def == null || !def.isAvailable(activity)) {
            TutorialCenterFragment.open(activity);
            return;
        }
        boolean done = TutorialState.isCompleted(activity, def.id);
        new SettingsPopup(activity)
                .title(def.title)
                .subtitle(activity.getString(done ? R.string.tut_center_status_done : R.string.tut_help_subtitle, def.steps().size()))
                .message(def.description)
                .positive(done ? R.string.tut_center_replay : R.string.tut_help_start, () -> {
                    TutorialState.clearTutorial(activity, def.id);
                    if (!manager.start(def.id)) {
                        Toast.makeText(activity, R.string.tut_fallback_cannot_start, Toast.LENGTH_SHORT).show();
                    }
                })
                .negative(R.string.tut_help_all, () -> TutorialCenterFragment.open(activity))
                .show();
    }
}
