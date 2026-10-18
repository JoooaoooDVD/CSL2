package com.craftstudio.cslauncher.prefs;

import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.preference.Preference;

import com.craftstudio.cslauncher.R;

/** Non-interactive head of a settings category page (icon tile, big title, description). */
public class SettingsHeaderPreference extends Preference {
    public SettingsHeaderPreference(Context context, @DrawableRes int icon, @StringRes int title, @StringRes int summary) {
        super(context);
        setLayoutResource(R.layout.item_setting_header);
        setKey("cs_page_header");
        setPersistent(false);
        setSelectable(false);
        setIcon(icon);
        setTitle(title);
        if (summary != 0) setSummary(summary);
    }
}
