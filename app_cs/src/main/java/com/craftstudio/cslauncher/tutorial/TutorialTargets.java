package com.craftstudio.cslauncher.tutorial;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Finds the real view of a tutorial target. Targets are addressed by stable ids: a view id
 * (fragments' views are part of the activity hierarchy, so {@code findViewById} on the decor
 * works for every screen) or a preference key (resolved through the visible
 * {@link PreferenceFragmentCompat}'s adapter, never by text or position).
 */
final class TutorialTargets {
    private TutorialTargets() {}

    /**
     * @return the target view when it exists, is attached, visible and laid out; null otherwise.
     * For preference rows the list is scrolled to the row first, the caller retries shortly after.
     */
    @Nullable
    static View resolve(@NonNull FragmentActivity activity, @NonNull TutorialStep step) {
        View view = null;
        if (step.targetViewId != 0) {
            view = activity.findViewById(step.targetViewId);
        } else if (step.targetPrefKey != null) {
            view = resolvePreference(activity, step.targetPrefKey);
        }
        if (view == null) return null;
        if (!view.isAttachedToWindow() || !view.isShown()) return null;
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        return view;
    }

    /** Brings the target into view inside any scrolling parent, true when a scroll was requested */
    static boolean scrollIntoView(@NonNull View view) {
        Rect r = new Rect(0, 0, view.getWidth(), view.getHeight());
        try {
            return view.requestRectangleOnScreen(r, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fraction (0..1) of the view that is inside the window; 0 when fully clipped */
    static float visibleFraction(@NonNull View view) {
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible)) return 0f;
        float full = (float) view.getWidth() * view.getHeight();
        if (full <= 0f) return 0f;
        return Math.min(1f, (visible.width() * (float) visible.height()) / full);
    }

    // ───────────────────────────── preferences ─────────────────────────────

    @Nullable
    private static View resolvePreference(@NonNull FragmentActivity activity, @NonNull String key) {
        PreferenceFragmentCompat fragment = findPreferenceFragment(activity.getSupportFragmentManager(), key, 0);
        if (fragment == null) return null;
        RecyclerView list = fragment.getListView();
        if (list == null) return null;
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (!(adapter instanceof PreferenceGroup.PreferencePositionCallback)) return null;
        int position = ((PreferenceGroup.PreferencePositionCallback) adapter).getPreferenceAdapterPosition(key);
        if (position == RecyclerView.NO_POSITION) return null;
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        if (holder == null || holder.itemView.getHeight() <= 0) {
            // Not laid out yet: scroll there, the manager retries on the next tick
            list.scrollToPosition(position);
            return null;
        }
        return holder.itemView;
    }

    @Nullable
    private static PreferenceFragmentCompat findPreferenceFragment(@NonNull FragmentManager manager, @NonNull String key, int depth) {
        if (depth > 6) return null;
        List<Fragment> fragments = manager.getFragments();
        for (Fragment f : fragments) {
            if (f == null || !f.isAdded() || f.getView() == null) continue;
            if (f instanceof PreferenceFragmentCompat && f.isResumed()) {
                PreferenceFragmentCompat pf = (PreferenceFragmentCompat) f;
                try {
                    if (pf.getPreferenceScreen() != null && pf.findPreference(key) != null) return pf;
                } catch (Throwable ignored) {
                }
            }
            PreferenceFragmentCompat nested = findPreferenceFragment(f.getChildFragmentManager(), key, depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }
}
