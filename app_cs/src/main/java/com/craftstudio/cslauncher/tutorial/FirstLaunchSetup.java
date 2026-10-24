package com.craftstudio.cslauncher.tutorial;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.authenticator.accounts.Accounts;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.ThemeManager;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

/**
 * First-time setup wizard: Welcome → tour preferences → launcher theme → interactive tour
 * (modules run by {@link TutorialManager}) → completion summary.
 * <p>
 * The pages are a full-bleed view on {@code android.R.id.content} (same technique as the
 * policy page). The current page is persisted, so a theme change (which recreates the activity)
 * or a process death simply re-shows the page. Nothing here is mandatory: Skip / Explore close
 * the wizard immediately and the launcher is fully usable underneath at any time.
 */
public final class FirstLaunchSetup implements TutorialManager.SequenceListener {
    static final int PAGE_WELCOME = 0;
    static final int PAGE_OPTIONS = 1;
    static final int PAGE_THEME = 2;
    static final int PAGE_TOUR = 3;
    static final int PAGE_DONE = 4;

    private static FirstLaunchSetup sCurrent;

    private final LauncherActivity mActivity;
    private View mRoot;
    private int mPage;
    private String mTheme;

    private FirstLaunchSetup(@NonNull LauncherActivity activity) {
        mActivity = activity;
    }

    /** True when the wizard should open on this start (never after it finished or was skipped) */
    public static boolean isPending(@NonNull Context context) {
        return !TutorialState.isOnboardingDone(context);
    }

    /**
     * Shows the wizard page the user was on, or resumes an interrupted tour. Safe to call on every
     * start: it returns immediately once onboarding is done.
     */
    public static void showIfNeeded(@NonNull LauncherActivity activity) {
        if (!isPending(activity) || activity.isFinishing() || activity.isDestroyed()) return;
        int page = TutorialState.getSetupPage(activity);
        TutorialManager manager = TutorialManager.getInstance();
        if (page == PAGE_TOUR) {
            if (manager.isRunning()) {
                manager.setSequenceListener(current(activity));
                return;
            }
            if (manager.hasResumableRun(activity)) {
                // Interrupted tour: a small resume hint instead of blocking the launcher again
                manager.setSequenceListener(current(activity));
                ContextualHelp.showResumeHint(activity);
                return;
            }
            page = PAGE_DONE;
        }
        current(activity).show(page);
    }

    /** Called when the tutorial manager binds to a (re)created activity during the tour */
    static void onActivityAttached(@NonNull LauncherActivity activity) {
        if (!isPending(activity)) return;
        if (TutorialState.getSetupPage(activity) == PAGE_TOUR) {
            TutorialManager.getInstance().setSequenceListener(current(activity));
        }
    }

    /** Restart from the Tutorial Center */
    public static void restart(@NonNull LauncherActivity activity) {
        TutorialManager.getInstance().cancel();
        TutorialState.restartOnboarding(activity);
        current(activity).show(PAGE_WELCOME);
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

    // ───────────────────────────── pages ─────────────────────────────

    private void show(int page) {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
        ViewGroup content = mActivity.findViewById(android.R.id.content);
        if (content == null) return;
        mPage = page;
        TutorialState.setSetupPage(mActivity, page);
        if (page == PAGE_WELCOME) TutorialState.setOnboardingStarted(mActivity);
        if (mRoot == null || mRoot.getParent() != content) {
            if (mRoot != null && mRoot.getParent() instanceof ViewGroup) ((ViewGroup) mRoot.getParent()).removeView(mRoot);
            mRoot = LayoutInflater.from(mActivity).inflate(R.layout.tutorial_setup, content, false);
            content.addView(mRoot, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // Back closes nothing underneath while the wizard is up; it goes one page back instead
            mRoot.setFocusableInTouchMode(true);
            mRoot.requestFocus();
            mRoot.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBackPressed();
                return true;
            });
            if (Motion.content(mRoot)) {
                mRoot.setAlpha(0f);
                mRoot.animate().alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
            }
        } else {
            mRoot.bringToFront();
        }
        bindPage();
    }

    private void onBackPressed() {
        if (mPage == PAGE_OPTIONS || mPage == PAGE_THEME) show(mPage - 1);
        // Welcome / done: Back does nothing, the buttons are explicit
    }

    private void bindPage() {
        View root = mRoot;
        if (root == null) return;
        TextView title = root.findViewById(R.id.tut_setup_title);
        TextView subtitle = root.findViewById(R.id.tut_setup_subtitle);
        TextView back = root.findViewById(R.id.tut_setup_back);
        TextView primary = root.findViewById(R.id.tut_setup_primary);
        TextView secondary = root.findViewById(R.id.tut_setup_secondary);
        TextView tertiary = root.findViewById(R.id.tut_setup_tertiary);
        TextView skip = root.findViewById(R.id.tut_setup_skip);
        ViewGroup dots = root.findViewById(R.id.tut_setup_dots);
        ViewGroup pageHost = root.findViewById(R.id.tut_setup_page);
        ScrollView scroll = root.findViewById(R.id.tut_setup_scroll);
        pageHost.removeAllViews();
        scroll.scrollTo(0, 0);
        tertiary.setVisibility(View.GONE);
        tertiary.setOnClickListener(null);
        int dotIndex = Math.min(mPage, 3);
        for (int i = 0; i < dots.getChildCount(); i++) dots.getChildAt(i).setAlpha(i <= dotIndex ? 1f : 0.25f);
        skip.setVisibility(mPage == PAGE_DONE ? View.INVISIBLE : View.VISIBLE);
        skip.setOnClickListener(v -> finish(true, false));

        LayoutInflater inflater = LayoutInflater.from(mActivity);
        switch (mPage) {
            case PAGE_WELCOME: {
                title.setText(mActivity.getString(R.string.tut_welcome_title, mActivity.getString(R.string.app_short_name)));
                subtitle.setText(R.string.tut_welcome_subtitle);
                View page = inflater.inflate(R.layout.tutorial_page_welcome, pageHost, true);
                bindRow(page.findViewById(R.id.tut_welcome_row1), R.drawable.ic_px_layers_buffer, R.string.tut_welcome_f1_t, R.string.tut_welcome_f1_b);
                bindRow(page.findViewById(R.id.tut_welcome_row2), R.drawable.ic_px_book, R.string.tut_welcome_f2_t, R.string.tut_welcome_f2_b);
                bindRow(page.findViewById(R.id.tut_welcome_row3), R.drawable.ic_px_palette, R.string.tut_welcome_f3_t, R.string.tut_welcome_f3_b);
                bindRow(page.findViewById(R.id.tut_welcome_row4), R.drawable.ic_px_zap, R.string.tut_welcome_f4_t, R.string.tut_welcome_f4_b);
                back.setVisibility(View.INVISIBLE);
                primary.setText(R.string.tut_welcome_start);
                primary.setOnClickListener(v -> show(PAGE_OPTIONS));
                secondary.setVisibility(View.VISIBLE);
                secondary.setText(R.string.tut_welcome_explore);
                secondary.setOnClickListener(v -> finish(true, false));
                break;
            }
            case PAGE_OPTIONS: {
                title.setText(R.string.tut_options_title);
                subtitle.setText(R.string.tut_options_subtitle);
                View page = inflater.inflate(R.layout.tutorial_page_options, pageHost, true);
                View quick = page.findViewById(R.id.tut_opt_quick);
                View complete = page.findViewById(R.id.tut_opt_complete);
                View interactive = page.findViewById(R.id.tut_opt_interactive);
                View guided = page.findViewById(R.id.tut_opt_guided);
                bindOption(quick, R.string.tut_opt_quick_t, R.string.tut_opt_quick_b);
                bindOption(complete, R.string.tut_opt_complete_t, R.string.tut_opt_complete_b);
                bindOption(interactive, R.string.tut_opt_interactive_t, R.string.tut_opt_interactive_b);
                bindOption(guided, R.string.tut_opt_guided_t, R.string.tut_opt_guided_b);
                boolean isComplete = TutorialState.MODE_COMPLETE.equals(TutorialState.getSetupMode(mActivity));
                select(quick, complete, isComplete);
                quick.setOnClickListener(v -> { TutorialState.setSetupMode(mActivity, TutorialState.MODE_QUICK); select(quick, complete, false); });
                complete.setOnClickListener(v -> { TutorialState.setSetupMode(mActivity, TutorialState.MODE_COMPLETE); select(quick, complete, true); });
                boolean isInteractive = TutorialState.isInteractive(mActivity);
                select(interactive, guided, !isInteractive);
                interactive.setOnClickListener(v -> { TutorialState.setInteractive(mActivity, true); select(interactive, guided, false); });
                guided.setOnClickListener(v -> { TutorialState.setInteractive(mActivity, false); select(interactive, guided, true); });
                MaterialSwitch tips = page.findViewById(R.id.tut_opt_tips);
                tips.setChecked(TutorialState.showTips(mActivity));
                tips.setOnCheckedChangeListener((b, checked) -> TutorialState.setShowTips(mActivity, checked));
                page.findViewById(R.id.tut_opt_tips_row).setOnClickListener(v -> tips.toggle());
                back.setVisibility(View.VISIBLE);
                back.setOnClickListener(v -> show(PAGE_WELCOME));
                primary.setText(R.string.tut_continue);
                primary.setOnClickListener(v -> show(PAGE_THEME));
                secondary.setVisibility(View.GONE);
                break;
            }
            case PAGE_THEME: {
                title.setText(R.string.tut_theme_title);
                subtitle.setText(R.string.tut_theme_subtitle);
                View page = inflater.inflate(R.layout.tutorial_page_theme, pageHost, true);
                View dark = page.findViewById(R.id.tut_theme_dark);
                View light = page.findViewById(R.id.tut_theme_light);
                View system = page.findViewById(R.id.tut_theme_system);
                bindOption(dark, R.string.tut_theme_dark_t, R.string.tut_theme_dark_b);
                bindOption(light, R.string.tut_theme_light_t, R.string.tut_theme_light_b);
                bindOption(system, R.string.tut_theme_system_t, R.string.tut_theme_system_b);
                mTheme = LauncherPreferences.DEFAULT_PREF.getString(ThemeManager.PREF_THEME_MODE, "dark");
                selectTheme(dark, light, system);
                dark.setOnClickListener(v -> applyTheme("dark", dark, light, system));
                light.setOnClickListener(v -> applyTheme("light", dark, light, system));
                system.setOnClickListener(v -> applyTheme("system", dark, light, system));
                back.setVisibility(View.VISIBLE);
                back.setOnClickListener(v -> show(PAGE_OPTIONS));
                primary.setText(R.string.tut_theme_start_tour);
                primary.setOnClickListener(v -> startTour());
                secondary.setVisibility(View.GONE);
                break;
            }
            case PAGE_DONE:
            default: {
                title.setText(R.string.tut_done_title);
                subtitle.setText(R.string.tut_done_subtitle);
                View page = inflater.inflate(R.layout.tutorial_page_done, pageHost, true);
                bindDone(page);
                back.setVisibility(View.INVISIBLE);
                primary.setText(R.string.tut_done_open);
                primary.setOnClickListener(v -> finish(false, false));
                secondary.setVisibility(View.VISIBLE);
                secondary.setText(R.string.tut_done_explore);
                secondary.setOnClickListener(v -> finish(false, true));
                if (isReadyToPlay()) {
                    tertiary.setVisibility(View.VISIBLE);
                    tertiary.setText(R.string.tut_done_launch);
                    tertiary.setOnClickListener(v -> {
                        finish(false, false);
                        // Explicit user action: the normal PLAY pipeline (validations, downloads) takes over
                        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                    });
                }
                break;
            }
        }
    }

    private void bindRow(@NonNull View row, @DrawableRes int icon, @StringRes int title, @StringRes int text) {
        ((ImageView) row.findViewById(R.id.tut_row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.tut_row_title)).setText(title);
        ((TextView) row.findViewById(R.id.tut_row_text)).setText(text);
    }

    private void bindRow(@NonNull View row, @DrawableRes int icon, @NonNull CharSequence title, @NonNull CharSequence text) {
        ((ImageView) row.findViewById(R.id.tut_row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.tut_row_title)).setText(title);
        ((TextView) row.findViewById(R.id.tut_row_text)).setText(text);
    }

    private void bindOption(@NonNull View card, @StringRes int title, @StringRes int text) {
        ((TextView) card.findViewById(R.id.tut_opt_title)).setText(title);
        ((TextView) card.findViewById(R.id.tut_opt_text)).setText(text);
    }

    private static void select(@NonNull View a, @NonNull View b, boolean second) {
        a.setSelected(!second);
        b.setSelected(second);
        a.findViewById(R.id.tut_opt_check).setVisibility(second ? View.INVISIBLE : View.VISIBLE);
        b.findViewById(R.id.tut_opt_check).setVisibility(second ? View.VISIBLE : View.INVISIBLE);
    }

    private void selectTheme(@NonNull View dark, @NonNull View light, @NonNull View system) {
        View[] cards = {dark, light, system};
        String[] values = {"dark", "light", "system"};
        for (int i = 0; i < cards.length; i++) {
            boolean on = values[i].equals(mTheme);
            cards[i].setSelected(on);
            cards[i].findViewById(R.id.tut_opt_check).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** Writes the real preference; AppCompat recreates the activity and this page is shown again */
    private void applyTheme(@NonNull String value, View dark, View light, View system) {
        if (value.equals(mTheme)) return;
        mTheme = value;
        TutorialState.setSetupTheme(mActivity, value);
        selectTheme(dark, light, system);
        LauncherPreferences.DEFAULT_PREF.edit().putString(ThemeManager.PREF_THEME_MODE, value).apply();
        mRoot.postDelayed(() -> ThemeManager.applyThemeMode(value), 180L);
    }

    // ───────────────────────────── tour ─────────────────────────────

    private void startTour() {
        TutorialManager manager = TutorialManager.getInstance();
        TutorialState.setSetupPage(mActivity, PAGE_TOUR);
        mPage = PAGE_TOUR;
        manager.setSequenceListener(this);
        removeRoot(true);
        boolean complete = TutorialState.MODE_COMPLETE.equals(TutorialState.getSetupMode(mActivity));
        List<String> sequence = TutorialRegistry.onboardingSequence(complete);
        mRoot = null;
        mActivity.findViewById(android.R.id.content).postDelayed(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
            if (!manager.startSequence(sequence, true)) show(PAGE_DONE);
        }, Motion.MEDIUM + 60L);
    }

    @Override
    public void onSequenceFinished(boolean onboarding) {
        if (!onboarding) return;
        TutorialManager.getInstance().setSequenceListener(null);
        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
        show(PAGE_DONE);
    }

    @Override
    public void onSequencePaused(boolean onboarding) {
        // Page stays PAGE_TOUR with a saved run: next start shows the resume hint, the
        // Tutorial Center offers Resume. The launcher is free meanwhile.
    }

    // ───────────────────────────── completion ─────────────────────────────

    private void bindDone(@NonNull View page) {
        String theme = LauncherPreferences.DEFAULT_PREF.getString(ThemeManager.PREF_THEME_MODE, "dark");
        int themeLabel = "light".equals(theme) ? R.string.tut_theme_light_t : "system".equals(theme) ? R.string.tut_theme_system_t : R.string.tut_theme_dark_t;
        bindRow(page.findViewById(R.id.tut_done_theme), R.drawable.ic_px_palette,
                mActivity.getString(R.string.tut_done_row_theme), mActivity.getString(themeLabel));

        Instance instance = selectedInstance();
        String profileText = instance == null ? mActivity.getString(R.string.tut_done_no_profile)
                : (instance.name == null ? "" : instance.name) + (Tools.isValidString(instance.versionId) ? "  ·  " + instance.versionId : "");
        bindRow(page.findViewById(R.id.tut_done_profile), R.drawable.ic_px_layers_buffer,
                mActivity.getString(R.string.tut_done_row_profile), profileText);

        Account account = currentAccount();
        bindRow(page.findViewById(R.id.tut_done_account), R.drawable.ic_px_account_edit,
                mActivity.getString(R.string.tut_done_row_account),
                account == null ? mActivity.getString(R.string.tut_done_no_account) : account.username);

        boolean complete = TutorialState.MODE_COMPLETE.equals(TutorialState.getSetupMode(mActivity));
        List<String> ids = TutorialRegistry.onboardingSequence(complete);
        int done = 0;
        StringBuilder names = new StringBuilder();
        for (String id : ids) {
            TutorialDefinition def = TutorialRegistry.get(id);
            if (def == null) continue;
            if (TutorialState.isCompleted(mActivity, id)) {
                done++;
                if (names.length() > 0) names.append(" · ");
                names.append(mActivity.getString(def.title));
            }
        }
        ((TextView) page.findViewById(R.id.tut_done_text)).setText(mActivity.getString(R.string.tut_done_count, done, ids.size()));
        bindRow(page.findViewById(R.id.tut_done_modules), R.drawable.ic_px_book,
                mActivity.getString(R.string.tut_done_row_modules),
                names.length() == 0 ? mActivity.getString(R.string.tut_done_no_modules) : names.toString());
        TextView hint = page.findViewById(R.id.tut_done_hint);
        hint.setText(isReadyToPlay() ? R.string.tut_done_hint_ready : R.string.tut_done_hint);
    }

    /** "Ready" = a selected profile with a version and a saved account; nothing more is claimed */
    private boolean isReadyToPlay() {
        Instance instance = selectedInstance();
        return instance != null && instance.installer == null && Tools.isValidString(instance.versionId) && currentAccount() != null;
    }

    @Nullable
    private static Instance selectedInstance() {
        try {
            return Instances.loadSelectedInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Account currentAccount() {
        try {
            return Accounts.getCurrent();
        } catch (Throwable t) {
            return null;
        }
    }

    // ───────────────────────────── closing ─────────────────────────────

    private void finish(boolean skipped, boolean openCenter) {
        TutorialState.setOnboardingDone(mActivity, skipped);
        if (skipped) TutorialManager.getInstance().cancel();
        TutorialManager.getInstance().setSequenceListener(null);
        removeRoot(true);
        if (sCurrent == this) sCurrent = null;
        if (openCenter) TutorialCenterFragment.open(mActivity);
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
