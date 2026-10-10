package com.craftstudio.cslauncher.fragments;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceFragment;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.SettingsMotion;

/**
 * Two-pane landscape settings, redesigned:
 * <ul>
 *   <li>LEFT: an icon rail ({@link SettingsRailFragment}) with every category - the same
 *       buttons the hub shows as cards, so navigation never requires going back first;</li>
 *   <li>RIGHT: the settings hub by default (the category cards), replaced in place by the
 *       tapped category with a shared engine transition;</li>
 *   <li>a header banner (art + section icon/title/subtitle) above the right pane that
 *       follows the visible screen.</li>
 * </ul>
 * Category opening still flows through {@link OnPreferenceStartFragmentCallback}, so
 * preference-driven sub-screens (renderer settings, …) work exactly as before.
 */
public class SettingsHostFragment extends Fragment
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    public static final String TAG = "SettingsHostFragment";
    private static final String LEFT_PANE_TAG = "SETTINGS_LEFT_PANE";
    private static final String DEFAULT_RIGHT_PANE_TAG = "SETTINGS_RIGHT_PANE_DEFAULT";
    private static final String RAIL_TAG = "SettingsRailFragment";
    private static final String SCREEN_TAG_PREFIX = "SETTINGS_SCREEN:";

    private OnBackPressedCallback mBackCallback;
    private TextView mHeaderTitle;
    private TextView mHeaderSubtitle;
    private ImageView mHeaderIcon;
    private ImageView mHeaderArt;
    private View mHeader, mRailPane, mRightColumn, mRightPane;
    @Nullable private Integer mHeaderArtRes;

    /** The screen currently shown in the right pane, null = the settings hub */
    @Nullable
    private String mCurrentScreenClass;

    public SettingsHostFragment() {
        super(R.layout.fragment_settings_host);
    }

    private boolean isTwoPane() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    @Nullable
    public String getCurrentScreenClass() {
        return mCurrentScreenClass;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                FragmentManager manager = getChildFragmentManager();
                if (manager.getBackStackEntryCount() > 0) manager.popBackStackImmediate();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, mBackCallback);
        // The rail active tile and the header follow the child back stack: top screen,
        // or the hub when the stack is empty
        getChildFragmentManager().addOnBackStackChangedListener(() -> {
            mBackCallback.setEnabled(getChildFragmentManager().getBackStackEntryCount() > 0);
            syncHeaderAndRail(true);
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentManager manager = getChildFragmentManager();
        mHeaderIcon = view.findViewById(R.id.settings_header_icon);
        mHeaderTitle = view.findViewById(R.id.settings_header_title);
        mHeaderSubtitle = view.findViewById(R.id.settings_header_subtitle);
        mHeaderArt = view.findViewById(R.id.settings_header_art);
        mHeader = view.findViewById(R.id.settings_header);
        mRightColumn = view.findViewById(R.id.settings_right_column);
        mRightPane = view.findViewById(R.id.settings_right_pane_container);
        mRailPane = isTwoPane() ? view.findViewById(R.id.settings_left_pane_container) : null;
        mHeaderArtRes = null;

        if (manager.findFragmentByTag(LEFT_PANE_TAG) != null
                || manager.findFragmentByTag(RAIL_TAG) != null
                || manager.findFragmentByTag(DEFAULT_RIGHT_PANE_TAG) != null) {
            // Restored: bring the chrome in line with the restored back stack
            syncHeaderAndRail(false);
            return;
        }

        if (isTwoPane()) {
            // Landscape: the hub fills the screen; the rail appears once a category opens
            Fragment hub = new SettingsHubFragment();
            applyTransitions(hub);
            manager.beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.settings_left_pane_container,
                            new SettingsRailFragment(), RAIL_TAG)
                    .replace(R.id.settings_right_pane_container, hub, DEFAULT_RIGHT_PANE_TAG)
                    .commit();
        } else {
            // Portrait fallback: the hub takes the single pane
            Fragment hub = manager.getFragmentFactory().instantiate(
                    requireContext().getClassLoader(), LauncherPreferenceFragment.class.getName());
            applyTransitions(hub);
            manager.beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.settings_left_pane_container, hub, LEFT_PANE_TAG)
                    .commit();
        }
        mCurrentScreenClass = null;
        updateHeader(null);
        applyChrome(false, false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHeaderIcon = mHeaderArt = null;
        mHeaderTitle = mHeaderSubtitle = null;
        mHeader = mRailPane = mRightColumn = mRightPane = null;
    }

    /**
     * Hub: the hub uses the whole screen (no rail, no header, no pane frame).
     * Category: rail on the left and the header banner above the framed screen.
     */
    private void applyChrome(boolean category, boolean animate) {
        if (mRailPane == null || mRightColumn == null) return;
        View root = getView();
        if (root == null) return;
        View guideline = root.findViewById(R.id.settings_pane_guideline);
        if (guideline != null && guideline.getLayoutParams()
                instanceof androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams params =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) guideline.getLayoutParams();
            int begin = category ? Math.round(64 * getResources().getDisplayMetrics().density) : 0;
            if (params.guideBegin != begin) {
                params.guideBegin = begin;
                guideline.setLayoutParams(params);
            }
        }
        if (mRightColumn.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mRightColumn.getLayoutParams();
            int margin = category ? Math.round(8 * getResources().getDisplayMetrics().density) : 0;
            if (params.topMargin != margin) {
                params.setMargins(margin, margin, margin, margin);
                params.setMarginStart(margin);
                params.setMarginEnd(margin);
                mRightColumn.setLayoutParams(params);
            }
        }
        if (mRightPane != null) {
            if (category) mRightPane.setBackgroundResource(R.drawable.bg_right_pane);
            else mRightPane.setBackground(null);
            if (mRightPane.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mRightPane.getLayoutParams();
                int top = category ? Math.round(6 * getResources().getDisplayMetrics().density) : 0;
                if (params.topMargin != top) {
                    params.topMargin = top;
                    mRightPane.setLayoutParams(params);
                }
            }
        }
        SettingsMotion.rail(mRailPane, category, animate);
        SettingsMotion.header(mHeader, category, animate);
    }

    /** Shared enter/exit spec - the engine is the single source of transition motion */
    private static void applyTransitions(@NonNull Fragment fragment) {
        fragment.setEnterTransition(CSAnimationEngine.makeFragmentForwardEnter());
        fragment.setExitTransition(CSAnimationEngine.makeFragmentForwardExit());
        fragment.setReenterTransition(CSAnimationEngine.makeFragmentBackEnter());
        fragment.setReturnTransition(CSAnimationEngine.makeFragmentBackExit());
    }

    /**
     * Opens a category screen in the right pane (portrait: the single pane).
     * Passing {@code null} returns to the settings hub by clearing the stack.
     *
     * @return true when the navigation was handled
     */
    public boolean openCategory(@Nullable String fragmentClassName, @Nullable Bundle args) {
        FragmentManager manager = getChildFragmentManager();
        if (fragmentClassName == null) {
            if (manager.getBackStackEntryCount() > 0) {
                manager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            } else {
                syncHeaderAndRail(true);
            }
            return true;
        }

        int container = isTwoPane() ? R.id.settings_right_pane_container : R.id.settings_left_pane_container;
        String tag = SCREEN_TAG_PREFIX + fragmentClassName;
        int count = manager.getBackStackEntryCount();
        if (count > 0 && tag.equals(manager.getBackStackEntryAt(count - 1).getName())) {
            syncHeaderAndRail(true);
            return true;
        }

        Fragment fragment;
        try {
            fragment = manager.getFragmentFactory().instantiate(
                    requireContext().getClassLoader(), fragmentClassName);
        } catch (Exception e) {
            return false;
        }
        fragment.setArguments(args);
        applyTransitions(fragment);
        manager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(container, fragment, tag)
                .addToBackStack(tag)
                .commit();
        // Header and rail update once the transaction lands (back-stack listener)
        return true;
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller,
                                             @NonNull Preference pref) {
        return openCategory(pref.getFragment(), pref.getExtras());
    }

    /** Brings header + rail in line with the top of the child back stack */
    private void syncHeaderAndRail(boolean animate) {
        FragmentManager manager = getChildFragmentManager();
        String top = null;
        int count = manager.getBackStackEntryCount();
        if (count > 0) {
            String name = manager.getBackStackEntryAt(count - 1).getName();
            if (name != null && name.startsWith(SCREEN_TAG_PREFIX)) {
                top = name.substring(SCREEN_TAG_PREFIX.length());
            }
        }
        mCurrentScreenClass = top;
        updateHeader(top);
        updateRail(top);
        applyChrome(top != null, animate);
    }

    private void updateRail(@Nullable String fragmentClassName) {
        // The renderer sub-screen belongs to the video category for the rail highlight
        if (fragmentClassName != null
                && fragmentClassName.endsWith("LauncherPreferenceRendererSettingsFragment")) {
            art = R.drawable.settings_art_video;
            fragmentClassName = "com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceVideoFragment";
        }
        Fragment rail = getChildFragmentManager().findFragmentByTag(RAIL_TAG);
        if (rail instanceof SettingsRailFragment) {
            ((SettingsRailFragment) rail).setActive(fragmentClassName);
        }
    }

    // ─── Header banner ───────────────────────────────────────────────────────

    private void updateHeader(@Nullable String fragmentClassName) {
        if (mHeaderTitle == null) return; // portrait layout has no header
        int icon;
        int title;
        int subtitle;
        int art = R.drawable.settings_banner;
        if (fragmentClassName == null) {
            icon = R.drawable.ic_sharp_settings_24;
            title = R.string.settings_hub_title;
            subtitle = R.string.settings_hub_subtitle;
        } else if (fragmentClassName.endsWith("LauncherPreferenceAppearanceFragment")) {
            art = R.drawable.settings_art_appearance;
            icon = R.drawable.ic_px_image;
            title = R.string.preference_appearance_title;
            subtitle = R.string.preference_appearance_description;
        } else if (fragmentClassName.endsWith("LauncherPreferenceControlFragment")) {
            art = R.drawable.settings_art_control;
            icon = R.drawable.ic_px_gamepad;
            title = R.string.preference_control_title;
            subtitle = R.string.preference_control_description;
        } else if (fragmentClassName.endsWith("LauncherPreferenceJavaFragment")) {
            art = R.drawable.settings_art_java;
            icon = R.drawable.ic_px_java;
            title = R.string.preference_java_title;
            subtitle = R.string.preference_java_description;
        } else if (fragmentClassName.endsWith("LauncherPreferenceMiscellaneousFragment")) {
            art = R.drawable.settings_art_misc;
            icon = R.drawable.ic_px_alt_sliders;
            title = R.string.preference_misc_title;
            subtitle = R.string.preference_misc_description;
        } else if (fragmentClassName.endsWith("LauncherPreferenceExperimentalFragment")) {
            art = R.drawable.settings_art_experimental;
            icon = R.drawable.ic_px_experiment;
            title = R.string.preference_experimental_title;
            subtitle = R.string.preference_experimental_description;
        } else if (fragmentClassName.endsWith("LauncherPreferenceRendererSettingsFragment")) {
            art = R.drawable.settings_art_video;
            icon = R.drawable.ic_mg_renderer;
            title = R.string.mcl_setting_title_renderer_settings;
            subtitle = R.string.mcl_setting_title_renderer_subtitle;
        } else {
            art = R.drawable.settings_art_video;
            icon = R.drawable.ic_qs_monitor;
            title = R.string.preference_video_title;
            subtitle = R.string.preference_video_description;
        }

        CharSequence newTitle = getString(title);
        CharSequence newSubtitle = getString(subtitle);
        if (mHeaderTitle.getText() == null
                || !newTitle.toString().contentEquals(mHeaderTitle.getText().toString())) {
            CSAnimationEngine.animateValueRefresh(mHeaderTitle, newTitle);
            CSAnimationEngine.animateValueRefresh(mHeaderSubtitle, newSubtitle);
        }
        mHeaderIcon.setImageResource(icon);
        CSAnimationEngine.animateImageSwap(mHeaderIcon);
        if (mHeaderArt != null && (mHeaderArtRes == null || mHeaderArtRes != art)) {
            boolean first = mHeaderArtRes == null;
            mHeaderArtRes = art;
            mHeaderArt.setImageResource(art);
            if (!first) SettingsMotion.headerArtChanged(mHeaderArt);
        }
    }
}
