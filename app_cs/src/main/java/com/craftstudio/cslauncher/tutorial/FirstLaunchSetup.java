package com.craftstudio.cslauncher.tutorial;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * The first-launch welcome: one card, two buttons. "Take the tour" runs the home-screen guide,
 * "Skip" closes it. Either choice marks onboarding as done, so the card never comes back on its
 * own (only "Replay the launcher tour" in the Tutorials screen starts the tour again, without
 * this card). If the card is dismissed by a crash / force-stop three times it also gives up.
 */
public final class FirstLaunchSetup implements TutorialManager.SequenceListener {
    @Nullable private static FirstLaunchSetup sCurrent;

    private final LauncherActivity mActivity;
    @Nullable private View mRoot;

    private FirstLaunchSetup(@NonNull LauncherActivity activity) {
        mActivity = activity;
    }

    /** Whether the welcome card still has to be shown */
    public static boolean isPending(@NonNull Context context) {
        return !TutorialState.isOnboardingDone(context);
    }

    /** Called by the launcher once the policy / storage setup is out of the way */
    public static void showIfNeeded(@NonNull LauncherActivity activity) {
        if (!isPending(activity)) return;
        if (TutorialManager.getInstance().isRunning()) return;
        if (TutorialState.bumpWelcomeShown(activity) > 3) {
            // Shown three times without an answer: stop insisting
            TutorialState.setOnboardingDone(activity, true);
            return;
        }
        current(activity).show();
    }

    /** Activity recreated while the tour is running: nothing to restore, the manager re-attaches itself */
    static void onActivityAttached(@NonNull LauncherActivity activity) {
        // intentionally empty
    }

    /** "Replay the launcher tour" from the Tutorials screen: straight into the guide, no welcome card */
    public static void restart(@NonNull LauncherActivity activity) {
        TutorialManager manager = TutorialManager.getInstance();
        manager.cancel();
        TutorialState.setOnboardingDone(activity, false);
        manager.setSequenceListener(null);
        manager.startSequence(TutorialRegistry.onboardingSequence(), false);
    }

    @NonNull
    private static FirstLaunchSetup current(@NonNull LauncherActivity activity) {
        FirstLaunchSetup setup = sCurrent;
        if (setup == null || setup.mActivity != activity) {
            if (setup != null) setup.removeRoot(false);
            setup = new FirstLaunchSetup(activity);
            sCurrent = setup;
        }
        return setup;
    }

    // ───────────────────────────── the card ─────────────────────────────

    private void show() {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
        ViewGroup content = mActivity.findViewById(android.R.id.content);
        if (content == null) return;
        if (mRoot != null && mRoot.getParent() == content) {
            mRoot.bringToFront();
            return;
        }
        TutorialState.setOnboardingStarted(mActivity);
        LayoutInflater inflater = LayoutInflater.from(mActivity);
        View root = inflater.inflate(R.layout.tutorial_setup, content, false);
        mRoot = root;
        content.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Back does nothing while the card is up: the two buttons are the only way out
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        root.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
        root.setOnTouchListener((v, e) -> true);

        ((TextView) root.findViewById(R.id.tut_setup_title)).setText(mActivity.getString(R.string.tut_welcome_title, mActivity.getString(R.string.app_short_name)));
        ((TextView) root.findViewById(R.id.tut_setup_subtitle)).setText(R.string.tut_welcome_subtitle);
        root.findViewById(R.id.tut_setup_dots).setVisibility(View.GONE);
        root.findViewById(R.id.tut_setup_back).setVisibility(View.GONE);
        root.findViewById(R.id.tut_setup_tertiary).setVisibility(View.GONE);
        root.findViewById(R.id.tut_setup_skip).setVisibility(View.GONE);

        ViewGroup pageHost = root.findViewById(R.id.tut_setup_page);
        pageHost.removeAllViews();
        View page = inflater.inflate(R.layout.tutorial_page_welcome, pageHost, true);
        bindRow(page.findViewById(R.id.tut_welcome_row1), R.drawable.ic_px_layers_buffer, R.string.tut_welcome_f1_t, R.string.tut_welcome_f1_b);
        bindRow(page.findViewById(R.id.tut_welcome_row2), R.drawable.ic_px_book, R.string.tut_welcome_f2_t, R.string.tut_welcome_f2_b);
        bindRow(page.findViewById(R.id.tut_welcome_row3), R.drawable.ic_px_palette, R.string.tut_welcome_f3_t, R.string.tut_welcome_f3_b);
        bindRow(page.findViewById(R.id.tut_welcome_row4), R.drawable.ic_px_zap, R.string.tut_welcome_f4_t, R.string.tut_welcome_f4_b);

        TextView primary = root.findViewById(R.id.tut_setup_primary);
        TextView secondary = root.findViewById(R.id.tut_setup_secondary);
        primary.setText(R.string.tut_welcome_start);
        secondary.setText(R.string.tut_welcome_explore);
        secondary.setVisibility(View.VISIBLE);
        primary.setOnClickListener(v -> startTour());
        secondary.setOnClickListener(v -> finish());

        if (Motion.content(root)) {
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
        }
    }

    private void bindRow(@NonNull View row, int icon, int title, int text) {
        ((ImageView) row.findViewById(R.id.tut_row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.tut_row_title)).setText(title);
        ((TextView) row.findViewById(R.id.tut_row_text)).setText(text);
    }

    private void startTour() {
        TutorialState.setOnboardingDone(mActivity, false);
        removeRoot(true);
        if (sCurrent == this) sCurrent = null;
        TutorialManager manager = TutorialManager.getInstance();
        manager.setSequenceListener(this);
        // Let the card fade before the spotlight appears
        mActivity.findViewById(android.R.id.content).postDelayed(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
            if (!manager.startSequence(TutorialRegistry.onboardingSequence(), true)) manager.setSequenceListener(null);
        }, Motion.MEDIUM + 40L);
    }

    @Override
    public void onSequenceFinished(boolean onboarding) {
        TutorialManager.getInstance().setSequenceListener(null);
    }

    @Override
    public void onSequencePaused(boolean onboarding) {
        TutorialManager.getInstance().setSequenceListener(null);
    }

    // ───────────────────────────── closing ─────────────────────────────

    private void finish() {
        TutorialState.setOnboardingDone(mActivity, true);
        removeRoot(true);
        if (sCurrent == this) sCurrent = null;
    }

    private void removeRoot(boolean animate) {
        final View root = mRoot;
        mRoot = null;
        if (root == null) return;
        final ViewGroup parent = root.getParent() instanceof ViewGroup ? (ViewGroup) root.getParent() : null;
        if (parent == null) return;
        root.setOnKeyListener(null);
        if (animate && Motion.content(root)) {
            root.animate().alpha(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.EXIT)
                    .withEndAction(() -> parent.removeView(root)).start();
        } else {
            parent.removeView(root);
        }
    }
}
