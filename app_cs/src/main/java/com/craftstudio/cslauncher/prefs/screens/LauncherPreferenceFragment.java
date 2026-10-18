package com.craftstudio.cslauncher.prefs.screens;


import com.craftstudio.cslauncher.prefs.BackButtonPreference;
import com.craftstudio.cslauncher.fragments.SettingsHostFragment;
import androidx.preference.PreferenceScreen;
import android.content.res.Configuration;
import com.craftstudio.cslauncher.utils.AnimationManager;
import com.craftstudio.cslauncher.utils.ThemeColors;
import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.ThemeManager;

/**
 * Preference for the main screen, any sub-screen should inherit this class for consistent behavior,
 * overriding only onCreatePreferences
 */
public class LauncherPreferenceFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
    protected Runnable mVisibilityUpdater = () -> {};
    private boolean mRecreatePending;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // The list of categories in the left pane of the two-pane settings draws on the host background
        if(!isSettingsListPane()) view.setBackgroundColor(ThemeColors.surface(view.getContext()));
        super.onViewCreated(view, savedInstanceState);
        // Card rows: no stock dividers, breathing room around the list, cards may overscroll into the padding
        androidx.recyclerview.widget.RecyclerView list = getListView();
        float density = getResources().getDisplayMetrics().density;
        int side = Math.round(10 * density);
        list.setClipToPadding(false);
        list.setPadding(side, Math.round(8 * density), side, Math.round(28 * density));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setDivider(null);
        setDividerHeight(0);
        if (getClass() != LauncherPreferenceFragment.class) {
            installPageHeader();
            upgradeListRows();
        }
        AnimationManager.applyListAnimation(list);
    }

    /** Category pages use the grouped-card adapter (see SettingsPageAdapter) */
    @NonNull
    @Override
    protected androidx.recyclerview.widget.RecyclerView.Adapter onCreateAdapter(@NonNull PreferenceScreen preferenceScreen) {
        return new com.craftstudio.cslauncher.prefs.SettingsPageAdapter(preferenceScreen);
    }

    /** Icon / title / description of this category page, shown in the page head. {@code null} for none. */
    @Nullable
    protected int[] pageHeader() {
        return null;
    }

    private void installPageHeader() {
        int[] header = pageHeader();
        PreferenceScreen screen = getPreferenceScreen();
        if (header == null || screen == null || screen.findPreference("cs_page_header") != null) return;
        // Keep the back row first, then the head, then everything from the XML
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference p = screen.getPreference(i);
            if (p instanceof BackButtonPreference) p.setOrder(-2);
        }
        com.craftstudio.cslauncher.prefs.SettingsHeaderPreference head =
                new com.craftstudio.cslauncher.prefs.SettingsHeaderPreference(requireContext(), header[0], header[1], header[2]);
        head.setOrder(-1);
        screen.addPreference(head);
    }

    /** Every list row shows its value as a chip (widget_setting_value), nested groups included */
    private void upgradeListRows() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) upgradeListRows(screen);
    }

    private void upgradeListRows(androidx.preference.PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (p instanceof androidx.preference.PreferenceGroup) upgradeListRows((androidx.preference.PreferenceGroup) p);
            else if (p instanceof androidx.preference.ListPreference) p.setWidgetLayoutResource(R.layout.widget_setting_value);
        }
    }

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        mVisibilityUpdater = this::updateVisibility;
        addPreferencesFromResource(R.xml.pref_main);
        // The right pane of the two-pane settings has its own back button
        if(isSettingsListPane()) removeBackButton();
        setupNotificationRequestPreference();
        setupLanguagePreference();
    }

    /** The language list is built in code (native names), picking one restarts the launcher */
    private void setupLanguagePreference() {
        Preference preference = findPreference(com.craftstudio.cslauncher.utils.LauncherLanguages.PREF_KEY);
        if (!(preference instanceof androidx.preference.ListPreference)) return;
        androidx.preference.ListPreference list = (androidx.preference.ListPreference) preference;
        android.content.Context context = requireContext();
        list.setEntries(com.craftstudio.cslauncher.utils.LauncherLanguages.labels(context));
        list.setEntryValues(com.craftstudio.cslauncher.utils.LauncherLanguages.values());
        String current = com.craftstudio.cslauncher.utils.LauncherLanguages.current(context);
        list.setValue(current);
        list.setSummary(com.craftstudio.cslauncher.utils.LauncherLanguages.labelOf(context, current));
        list.setOnPreferenceChangeListener((p, value) -> {
            String tag = String.valueOf(value);
            if (tag.equals(com.craftstudio.cslauncher.utils.LauncherLanguages.current(context))) return false;
            Activity activity = getActivity();
            if (activity != null) com.craftstudio.cslauncher.utils.LocaleUtils.changeLanguage(activity, tag);
            return false;
        });
    }

    /** @return whether this is the list of categories inside the left pane of the two-pane settings */
    private boolean isSettingsListPane() {
        // Only the category list itself, not the categories that extend this fragment
        return getClass() == LauncherPreferenceFragment.class
                && getParentFragment() instanceof SettingsHostFragment
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void removeBackButton() {
        PreferenceScreen screen = getPreferenceScreen();
        for(int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            if(preference instanceof BackButtonPreference) {
                screen.removePreference(preference);
                return;
            }
        }
    }

    private void updateVisibility(){
        requirePreference("notification_permission_request").setVisible(!getLauncherActivity().checkForPermission(33, Manifest.permission.POST_NOTIFICATIONS));
    }

    private void setupNotificationRequestPreference() {
        Preference mRequestNotificationPermissionPreference = requirePreference("notification_permission_request");
        Activity activity = getActivity();
        if(activity instanceof LauncherActivity) {
            mRequestNotificationPermissionPreference.setOnPreferenceClickListener(preference -> {
                ((LauncherActivity) activity).askForPermission(33, Manifest.permission.POST_NOTIFICATIONS);
                return true;
            });
        }else{
            mRequestNotificationPermissionPreference.setVisible(false);
        }
        updateVisibility();
    }

    @Override
    public void onResume() {
        super.onResume();
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if(sharedPreferences != null) sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        mVisibilityUpdater.run();
    }

    @Override
    public void onPause() {
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if(sharedPreferences != null) sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        LauncherPreferences.loadPreferences(getContext());
        if(ThemeManager.PREF_THEME_MODE.equals(s)) {
            ThemeManager.applyThemeMode(p.getString(s, "dark"));
        }else if(ThemeManager.PREF_COLOR_SOURCE.equals(s) || ThemeManager.PREF_CUSTOM_COLOR.equals(s)
                || AnimationManager.PREF_TYPES.equals(s)) {
            scheduleRecreate();
        }
        // Landscape is always on in CS Launcher Plus - there is no orientation preference anymore
    }

    /** Recreates the activity to apply a new theme, only once even if multiple keys changed together */
    private void scheduleRecreate() {
        Activity activity = getActivity();
        if(activity == null || mRecreatePending) return;
        mRecreatePending = true;
        activity.getWindow().getDecorView().post(activity::recreate);
    }

    /** Lists, multi choices and text fields open in the launcher's own XML popup */
    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        if (getContext() != null && com.craftstudio.cslauncher.prefs.SettingsPopups.show(requireContext(), preference)) return;
        super.onDisplayPreferenceDialog(preference);
    }

    protected Preference requirePreference(CharSequence key) {
        Preference preference = findPreference(key);
        if(preference != null) return preference;
        throw new IllegalStateException("Preference "+key+" is null");
    }
    @SuppressWarnings("unchecked")
    protected <T extends Preference> T requirePreference(CharSequence key, Class<T> preferenceClass) {
        Preference preference = requirePreference(key);
        if(preferenceClass.isInstance(preference)) return (T)preference;
        throw new IllegalStateException("Preference "+key+" is not an instance of "+preferenceClass.getSimpleName());
    }
    protected LauncherActivity getLauncherActivity(){
        return ((LauncherActivity) getActivity());
    }
}
