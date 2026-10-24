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
import com.craftstudio.cslauncher.fragments.MainMenuFragment;
import com.craftstudio.cslauncher.fragments.ModsSearchFragment;
import com.craftstudio.cslauncher.fragments.SettingsHostFragment;
import com.craftstudio.cslauncher.instances.Instances;

/**
 * The few launcher screens a guide can stand on, with the launcher's own navigation to reach
 * them. Guides never go deeper than this on purpose: every destination here is one stable
 * transaction away from the home screen, which keeps the tour predictable.
 */
public enum TutorialScreen {
    /** Wherever the user is */
    ANY,
    HOME,
    /** Profile editor (right pane of the two-pane home) */
    PROFILE_EDITOR,
    /** Content browser (right pane) */
    BROWSE,
    /** Settings hub (full screen, no category open) */
    SETTINGS_HUB;

    /** Whether the screen can be opened at all right now */
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

    // ───────────────────────────── state ─────────────────────────────

    @Nullable
    static Fragment top(@NonNull FragmentActivity activity) {
        return activity.getSupportFragmentManager().findFragmentById(R.id.container_fragment);
    }

    @Nullable
    static MainMenuFragment mainMenu(@NonNull FragmentActivity activity) {
        Fragment f = top(activity);
        return f instanceof MainMenuFragment && f.isAdded() && f.getView() != null ? (MainMenuFragment) f : null;
    }

    @Nullable
    static Fragment rightPane(@NonNull FragmentActivity activity) {
        MainMenuFragment menu = mainMenu(activity);
        if (menu == null) return null;
        return menu.getChildFragmentManager().findFragmentById(R.id.right_pane_container);
    }

    @Nullable
    private static SettingsHostFragment settingsHost(@NonNull FragmentActivity activity) {
        Fragment f = top(activity);
        return f instanceof SettingsHostFragment ? (SettingsHostFragment) f : null;
    }

    /** True when the user is on this screen and it has finished its first layout */
    public boolean isOn(@NonNull FragmentActivity activity) {
        if (this == ANY) return true;
        FragmentManager fm = activity.getSupportFragmentManager();
        switch (this) {
            case HOME: {
                MainMenuFragment menu = mainMenu(activity);
                return menu != null && menu.isResumed() && !menu.isRightPaneActive() && !fm.isStateSaved();
            }
            case PROFILE_EDITOR: {
                Fragment top = top(activity);
                if (top instanceof InstanceEditorFragment) return top.isResumed() && top.getView() != null;
                Fragment pane = rightPane(activity);
                return pane instanceof InstanceEditorFragment && pane.isResumed() && pane.getView() != null && pane.getView().getWidth() > 0;
            }
            case BROWSE: {
                Fragment top = top(activity);
                if (top instanceof ModsSearchFragment) return top.isResumed() && top.getView() != null;
                Fragment pane = rightPane(activity);
                return pane instanceof ModsSearchFragment && pane.isResumed() && pane.getView() != null && pane.getView().getWidth() > 0;
            }
            case SETTINGS_HUB: {
                SettingsHostFragment host = settingsHost(activity);
                return host != null && host.isResumed() && host.getView() != null && host.getCurrentScreenClass() == null;
            }
            default:
                return false;
        }
    }

    // ───────────────────────────── navigation ─────────────────────────────

    /**
     * Issues (at most) one navigation action towards this screen using the launcher's own calls.
     * Readiness is polled by the manager afterwards; calling this again while a transaction is
     * pending is harmless because every branch checks the current state first.
     */
    public boolean navigate(@NonNull LauncherActivity activity) {
        FragmentManager fm = activity.getSupportFragmentManager();
        if (fm.isStateSaved() || activity.isFinishing() || activity.isDestroyed()) return false;
        if (this == ANY || isOn(activity)) return true;
        try {
            switch (this) {
                case HOME:
                    goHome(activity);
                    return true;
                case SETTINGS_HUB: {
                    SettingsHostFragment host = settingsHost(activity);
                    if (host == null) {
                        if (!(top(activity) instanceof MainMenuFragment)) { goHome(activity); return true; }
                        Tools.swapFragmentFullScreen(activity, SettingsHostFragment.class, LauncherActivity.SETTING_FRAGMENT_TAG, null);
                        return true;
                    }
                    if (host.isResumed() && host.getView() != null && host.getCurrentScreenClass() != null) {
                        host.openCategory(null, null); // back to the hub
                    }
                    return true;
                }
                case PROFILE_EDITOR: {
                    if (!isAvailable(activity)) return false;
                    MainMenuFragment menu = mainMenu(activity);
                    if (menu == null) { goHome(activity); return true; }
                    if (rightPane(activity) instanceof InstanceEditorFragment) return true; // opening
                    if (menu.isRightPaneActive()) { menu.clearRightPane(); return true; }   // another pane: close it first
                    Tools.swapFragment(activity, InstanceEditorFragment.class, InstanceEditorFragment.TAG, null); // same call as the pencil
                    return true;
                }
                case BROWSE: {
                    MainMenuFragment menu = mainMenu(activity);
                    if (menu == null) { goHome(activity); return true; }
                    if (rightPane(activity) instanceof ModsSearchFragment) return true;
                    if (menu.isRightPaneActive()) { menu.clearRightPane(); return true; }
                    menu.showContentPicker(false); // same call as the Browse card
                    return true;
                }
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Pops every full screen above the main menu and clears its right pane */
    static void goHome(@NonNull FragmentActivity activity) {
        FragmentManager fm = activity.getSupportFragmentManager();
        if (fm.isStateSaved()) return;
        MainMenuFragment menu = mainMenu(activity);
        if (menu == null) {
            if (fm.getBackStackEntryCount() > 0) fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            return; // the pane is cleared on the next attempt, once the menu is back
        }
        if (menu.isRightPaneActive()) menu.clearRightPane();
    }
}
