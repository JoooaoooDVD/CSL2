package com.craftstudio.cslauncher.fragments;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;

/**
 * The category tabs on the header banner of the two-pane settings: one tab per category plus
 * a hub button - the same set of buttons the hub shows as cards, so every section stays one
 * tap away while inside a category (they replaced the old left icon rail).
 * <p>
 * Selection follows {@link SettingsHostFragment}'s back stack (active = bright tile).
 */
public class SettingsRailFragment extends Fragment {
    public static final String TAG = "SettingsRailFragment";

    /** Rail buttons in declaration order; the matching screen classes (null = hub) below */
    private static final int[] RAIL_BUTTON_IDS = {
            R.id.rail_home,
            R.id.rail_video,
            R.id.rail_appearance,
            R.id.rail_control,
            R.id.rail_java,
            R.id.rail_misc,
            R.id.rail_experimental
    };

    @Nullable
    private static String railClassForIndex(int index) {
        switch (index) {
            case 0: return null; // hub
            case 1: return "com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceVideoFragment";
            case 2: return "com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceAppearanceFragment";
            case 3: return "com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceControlFragment";
            case 4: return "com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceJavaFragment";
            case 5: return "com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceMiscellaneousFragment";
            case 6: return "com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceExperimentalFragment";
            default: return null;
        }
    }

    public SettingsRailFragment() {
        super(R.layout.fragment_settings_rail);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        for (int i = 0; i < RAIL_BUTTON_IDS.length; i++) {
            final String target = railClassForIndex(i);
            View button = view.findViewById(RAIL_BUTTON_IDS[i]);
            if (button == null) continue;
            button.setOnClickListener(v -> {
                SettingsHostFragment host = host();
                if (host != null) host.openCategory(target, null);
            });
            CSAnimationEngine.attachPressFeedback(button);
        }
        // The entrance is played by SettingsMotion when the category header appears
        // Reflect the current screen (restored instances, default = hub)
        SettingsHostFragment host = host();
        if (host != null) setActive(host.getCurrentScreenClass());
    }

    @Nullable
    private SettingsHostFragment host() {
        Fragment parent = getParentFragment();
        return parent instanceof SettingsHostFragment ? (SettingsHostFragment) parent : null;
    }

    /**
     * Highlights the button of the given screen; {@code null} selects the hub.
     * Same tile logic as the main sidebar: outlined bg + selected state.
     */
    public void setActive(@Nullable String fragmentClassName) {
        View view = getView();
        if (view == null) return;
        int activeIndex = 0;
        for (int i = 1; i < RAIL_BUTTON_IDS.length; i++) {
            String cls = railClassForIndex(i);
            if (cls != null && cls.equals(fragmentClassName)) {
                activeIndex = i;
                break;
            }
        }
        for (int i = 0; i < RAIL_BUTTON_IDS.length; i++) {
            View button = view.findViewById(RAIL_BUTTON_IDS[i]);
            if (button == null) continue;
            boolean active = i == activeIndex;
            if (button.isSelected() != active) {
                button.setSelected(active);
                if (active) com.craftstudio.cslauncher.utils.motion.Motion.bounce(button);
            }
        }
    }
}
