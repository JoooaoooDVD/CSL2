package com.craftstudio.cslauncher.tutorial;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.fragments.InstanceEditorFragment;
import com.craftstudio.cslauncher.fragments.LogsFragment;
import com.craftstudio.cslauncher.fragments.MainMenuFragment;
import com.craftstudio.cslauncher.fragments.ManageWorldsFragment;
import com.craftstudio.cslauncher.fragments.ModsSearchFragment;
import com.craftstudio.cslauncher.fragments.SettingsHostFragment;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.multiplayer.ui.VirtualLanFragment;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceAppearanceFragment;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceControlFragment;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceExperimentalFragment;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceJavaFragment;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceMiscellaneousFragment;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceVideoFragment;

/**
 * The launcher screens a tutorial step can require, with the real navigation used to reach them.
 * Every destination is reached through the launcher's own navigation calls (the same ones the
 * buttons use), nothing is faked. {@link #isOn} inspects the actual fragment stack.
 */
public enum TutorialScreen {
    /** No requirement: the step shows wherever the user is */
    ANY,
    HOME,
    PROFILE_EDITOR,
    BROWSE,
    WORLDS,
    MULTIPLAYER,
    LOGS,
    SETTINGS_HUB,
    SETTINGS_VIDEO,
    SETTINGS_APPEARANCE,
    SETTINGS_CONTROL,
    SETTINGS_JAVA,
    SETTINGS_MISC,
    SETTINGS_EXPERIMENTAL;

    /** Fragment class of the settings category, null for non-settings screens / the hub */
    @Nullable
    Class<? extends Fragment> settingsCategory() {
        switch (this) {
            case SETTINGS_VIDEO: return LauncherPreferenceVideoFragment.class;
            case SETTINGS_APPEARANCE: return LauncherPreferenceAppearanceFragment.class;
            case SETTINGS_CONTROL: return LauncherPreferenceControlFragment.class;
            case SETTINGS_JAVA: return LauncherPreferenceJavaFragment.class;
            case SETTINGS_MISC: return LauncherPreferenceMiscellaneousFragment.class;
            case SETTINGS_EXPERIMENTAL: return LauncherPreferenceExperimentalFragment.class;
            default: return null;
        }
    }

    boolean isSettings() {
        return this == SETTINGS_HUB || settingsCategory() != null;
    }

    /** Whether the screen can be opened at all right now (e.g. the profile editor needs a profile) */
    public boolean isAvailable(@NonNull Context context) {
        if (this == PROFILE_EDITOR) {
            try {
                return Instances.loadSelectedInstance() != null;
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    // ───────────────────────────── state inspection ─────────────────────────────

    @Nullable
    private static Fragment top(@NonNull FragmentActivity activity) {
        return activity.getSupportFragmentManager().findFragmentById(R.id.container_fragment);
    }

    @Nullable
    private static MainMenuFragment mainMenu(@NonNull FragmentActivity activity) {
        Fragment f = top(activity);
        return f instanceof MainMenuFragment ? (MainMenuFragment) f : null;
    }

    @Nullable
    private static Fragment rightPane(@NonNull FragmentActivity activity) {
        MainMenuFragment menu = mainMenu(activity);
        if (menu == null || !menu.isAdded()) return null;
        return menu.getChildFragmentManager().findFragmentById(R.id.right_pane_container);
    }

    @Nullable
    private static SettingsHostFragment settingsHost(@NonNull FragmentActivity activity) {
        Fragment f = top(activity);
        return f instanceof SettingsHostFragment ? (SettingsHostFragment) f : null;
    }

    /** True when the user is currently on this screen (and it finished its first layout) */
    public boolean isOn(@NonNull FragmentActivity activity) {
        Fragment top = top(activity);
        if (top == null || top.getView() == null) return this == ANY;
        switch (this) {
            case ANY:
                return true;
            case HOME: {
                MainMenuFragment menu = mainMenu(activity);
                return menu != null && !menu.isRightPaneActive() && rightPaneIs(activity, null);
            }
            case PROFILE_EDITOR:
                // Opens in the right pane of the two-pane home (Tools.swapFragment), full screen otherwise
                return (top instanceof InstanceEditorFragment && top.isResumed()) || rightPaneIs(activity, InstanceEditorFragment.class);
            case BROWSE:
                return (top instanceof ModsSearchFragment && top.isResumed()) || rightPaneIs(activity, ModsSearchFragment.class);
            case WORLDS:
                return (top instanceof ManageWorldsFragment && top.isResumed()) || rightPaneIs(activity, ManageWorldsFragment.class);
            case MULTIPLAYER:
                return (top instanceof VirtualLanFragment && top.isResumed()) || rightPaneIs(activity, VirtualLanFragment.class);
            case LOGS:
                return (top instanceof LogsFragment && top.isResumed()) || rightPaneIs(activity, LogsFragment.class);
            case SETTINGS_HUB: {
                SettingsHostFragment host = settingsHost(activity);
                return host != null && host.isResumed() && host.getCurrentScreenClass() == null;
            }
            default: {
                SettingsHostFragment host = settingsHost(activity);
                Class<?> cat = settingsCategory();
                return host != null && host.isResumed() && cat != null && cat.getName().equals(host.getCurrentScreenClass());
            }
        }
    }

    /** The pane already holds (or is in the middle of opening) a screen of this class */
    private static boolean paneHas(@NonNull FragmentActivity activity, @NonNull Class<?> cls) {
        return cls.isInstance(rightPane(activity));
    }

    /** @param cls null = the right pane shows its home content (or nothing above it) */
    private static boolean rightPaneIs(@NonNull FragmentActivity activity, @Nullable Class<?> cls) {
        Fragment pane = rightPane(activity);
        if (cls == null) {
            MainMenuFragment menu = mainMenu(activity);
            return menu != null && !menu.isRightPaneActive();
        }
        return pane != null && cls.isInstance(pane) && pane.isResumed();
    }

    // ───────────────────────────── navigation ─────────────────────────────

    /**
     * Starts the navigation towards this screen using the launcher's own navigation. Returns false
     * when it cannot be started (state saved, missing prerequisites); readiness is polled by the
     * manager afterwards because fragment transactions are asynchronous.
     */
    public boolean navigate(@NonNull LauncherActivity activity) {
        FragmentManager manager = activity.getSupportFragmentManager();
        if (manager.isStateSaved() || activity.isFinishing()) return false;
        if (this == ANY) return true;
        try {
            if (isSettings()) {
                SettingsHostFragment host = settingsHost(activity);
                if (host == null) {
                    goHome(activity);
                    Tools.swapFragmentFullScreen(activity, SettingsHostFragment.class, LauncherActivity.SETTING_FRAGMENT_TAG, null);
                    return true; // the category is opened on the next attempt, once the host is resumed
                }
                if (!host.isResumed() || host.getView() == null) return true;
                Class<?> cat = settingsCategory();
                return host.openCategory(cat == null ? null : cat.getName(), null);
            }
            if (this == HOME) {
                goHome(activity);
                return true;
            }
            if (this == PROFILE_EDITOR) {
                if (!isAvailable(activity)) return false;
                if (top(activity) instanceof InstanceEditorFragment) return true;
                Fragment pane = rightPane(activity);
                if (pane instanceof InstanceEditorFragment) return true; // opening / already open in the pane
                MainMenuFragment menu = mainMenu(activity);
                if (menu == null || menu.getView() == null) {
                    goHome(activity);
                    return true; // retried once the main menu is back
                }
                // Same call the pencil button uses (right pane in the two-pane home)
                Tools.swapFragment(activity, InstanceEditorFragment.class, InstanceEditorFragment.TAG, null);
                return true;
            }
            // Main menu destinations: the home has to be the top screen first
            MainMenuFragment menu = mainMenu(activity);
            if (menu == null || menu.getView() == null) {
                goHome(activity);
                return true; // retried once the main menu is back
            }
            switch (this) {
                case BROWSE:
                    if (!paneHas(activity, ModsSearchFragment.class)) menu.showContentPicker(false);
                    return true;
                case WORLDS:
                    if (!paneHas(activity, ManageWorldsFragment.class)) menu.openPane(ManageWorldsFragment.class, ManageWorldsFragment.TAG, null);
                    return true;
                case MULTIPLAYER:
                    if (!paneHas(activity, VirtualLanFragment.class)) menu.navigate(R.id.nav_multiplayer);
                    return true;
                case LOGS:
                    if (!paneHas(activity, LogsFragment.class)) menu.navigate(R.id.nav_logs);
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Pops every full screen above the main menu and clears its right pane */
    static void goHome(@NonNull FragmentActivity activity) {
        FragmentManager manager = activity.getSupportFragmentManager();
        if (manager.isStateSaved()) return;
        MainMenuFragment menu = mainMenu(activity);
        if (menu == null || menu.getView() == null) {
            if (manager.getBackStackEntryCount() > 0) {
                manager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            }
            menu = mainMenu(activity);
        }
        if (menu != null && menu.getView() != null) menu.clearRightPane();
    }
}
