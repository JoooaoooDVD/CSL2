package com.craftstudio.cslauncher.utils;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.motion.MainMenuMotion;

/**
 * The navigation dock of the launcher top bar (Home, Browse, Manage, Profiles, Versions, Logs).
 * It replaced the old left sidebar, so every screen gets the full width of the window.
 * <p>
 * A highlight slides behind the active button; inactive icons are dimmed. The dock itself only
 * shows state, clicks are routed by the activity to {@code MainMenuFragment.navigate}.
 */
public final class TopNavDock {

    /** The dock buttons, in order */
    public static final int[] ITEM_IDS = {
            R.id.nav_home, R.id.mod_store_button, R.id.open_files_button,
            R.id.nav_profiles, R.id.nav_versions, R.id.nav_logs};

    private static final float INACTIVE_ALPHA = 0.55f;

    private TopNavDock() {}

    public interface Navigator {
        void onDockItemClicked(int id);
    }

    /** Binds the clicks + press feedback, and tidies the bar on narrow screens */
    public static void bind(@NonNull Activity activity, @NonNull Navigator navigator) {
        for (int id : ITEM_IDS) {
            View button = activity.findViewById(id);
            if (button == null) continue;
            button.setAlpha(INACTIVE_ALPHA);
            button.setOnClickListener(v -> {
                MainMenuMotion.dockTap(v);
                navigator.onDockItemClicked(v.getId());
            });
        }
        // Narrow phones: the brand name makes room for the dock, the logo stays
        View brandText = activity.findViewById(R.id.brand_text);
        if (brandText != null && activity.getResources().getConfiguration().screenWidthDp < 720) {
            brandText.setVisibility(View.GONE);
        }
    }

    /** Marks a button as active (slides the highlight to it); {@link View#NO_ID} clears it */
    public static void setActive(@Nullable Activity activity, int activeId) {
        if (activity == null) return;
        View indicator = activity.findViewById(R.id.top_nav_indicator);
        View items = activity.findViewById(R.id.top_nav_items);
        if (indicator == null || !(items instanceof ViewGroup)) return;

        View active = null;
        for (int id : ITEM_IDS) {
            View button = activity.findViewById(id);
            if (button == null) continue;
            boolean isActive = id == activeId;
            if (isActive) active = button;
            if (button.isSelected() != isActive) {
                button.setSelected(isActive);
                MainMenuMotion.dockIcon(button, isActive ? 1f : INACTIVE_ALPHA);
            } else {
                button.setAlpha(isActive ? 1f : INACTIVE_ALPHA);
            }
        }

        if (active == null) {
            MainMenuMotion.dockIndicatorHide(indicator);
            return;
        }
        final View target = active;
        if (items.getWidth() == 0) {
            // First layout pass not done yet: place it once the dock is measured
            items.post(() -> MainMenuMotion.dockIndicatorTo(indicator, items.getLeft() + target.getLeft(), false));
            return;
        }
        MainMenuMotion.dockIndicatorTo(indicator, items.getLeft() + target.getLeft(), true);
    }
}
