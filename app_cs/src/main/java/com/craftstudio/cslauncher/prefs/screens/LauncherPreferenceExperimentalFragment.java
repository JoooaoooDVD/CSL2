package com.craftstudio.cslauncher.prefs.screens;

import android.os.Bundle;

import androidx.preference.SwitchPreferenceCompat;

import com.craftstudio.cslauncher.utils.GpuUtils;

import com.craftstudio.cslauncher.R;

public class LauncherPreferenceExperimentalFragment extends LauncherPreferenceFragment {
    @Override
    protected int[] pageHeader() {
        return new int[]{R.drawable.ic_st_flask, R.string.preference_experimental_title, R.string.preference_experimental_description};
    }


    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_experimental);
        SwitchPreferenceCompat sysmem = requirePreference("freedrenoSysmem", SwitchPreferenceCompat.class);
        SwitchPreferenceCompat ubwc = requirePreference("ubwcWorkaround", SwitchPreferenceCompat.class);
        boolean hasFreedreno = GpuUtils.getGlInfo().isAdreno();
        sysmem.setVisible(hasFreedreno);
        ubwc.setVisible(hasFreedreno);
    }
}
