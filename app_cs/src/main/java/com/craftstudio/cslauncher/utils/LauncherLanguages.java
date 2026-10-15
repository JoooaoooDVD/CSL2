package com.craftstudio.cslauncher.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.craftstudio.cslauncher.R;

import java.util.Locale;

/**
 * The languages offered by the "Launcher language" setting. Values are BCP-47 tags stored in
 * the {@link #PREF_KEY} preference; {@link #SYSTEM} follows the device language.
 * "hi-Latn" is Hinglish (Hindi written with Latin letters, resources in values-b+hi+Latn).
 */
public final class LauncherLanguages {
    public static final String PREF_KEY = "launcher_language";
    public static final String SYSTEM = "system";
    public static final String HINGLISH = "hi-Latn";

    /** tag, native display name */
    public static final String[][] LANGUAGES = {
            {SYSTEM, ""},
            {"en", "English"},
            {HINGLISH, "Hinglish"},
            {"hi", "\u0939\u093f\u0928\u094d\u0926\u0940"},
            {"ru", "\u0420\u0443\u0441\u0441\u043a\u0438\u0439"},
            {"es", "Espa\u00f1ol"},
            {"pt-BR", "Portugu\u00eas (Brasil)"},
            {"id", "Bahasa Indonesia"},
            {"vi", "Ti\u1ebfng Vi\u1ec7t"},
            {"tr", "T\u00fcrk\u00e7e"},
            {"ar", "\u0627\u0644\u0639\u0631\u0628\u064a\u0629"},
            {"zh-CN", "\u4e2d\u6587\uff08\u7b80\u4f53\uff09"},
            {"de", "Deutsch"},
            {"fr", "Fran\u00e7ais"},
    };

    private LauncherLanguages() {}

    /** The stored choice, migrating the old "force English" switch */
    @NonNull
    public static String current(@NonNull SharedPreferences prefs) {
        String value = prefs.getString(PREF_KEY, null);
        if (value == null || value.isEmpty()) {
            return prefs.getBoolean("force_english", false) ? "en" : SYSTEM;
        }
        return value;
    }

    @NonNull
    public static String current(@NonNull Context context) {
        return current(PreferenceManager.getDefaultSharedPreferences(context));
    }

    public static void save(@NonNull Context context, @NonNull String tag) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_KEY, tag)
                .putBoolean("force_english", "en".equals(tag))
                .apply();
    }

    /** @return the Locale for a stored value, null for "follow system" */
    @Nullable
    public static Locale localeOf(@NonNull String tag) {
        if (SYSTEM.equals(tag) || tag.isEmpty()) return null;
        return Locale.forLanguageTag(tag);
    }

    public static int indexOf(@NonNull String tag) {
        for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i][0].equals(tag)) return i;
        return 0;
    }

    /** Display names in the same order as {@link #LANGUAGES} */
    @NonNull
    public static CharSequence[] labels(@NonNull Context context) {
        CharSequence[] labels = new CharSequence[LANGUAGES.length];
        for (int i = 0; i < LANGUAGES.length; i++) labels[i] = labelOf(context, LANGUAGES[i][0]);
        return labels;
    }

    @NonNull
    public static String labelOf(@NonNull Context context, @NonNull String tag) {
        if (SYSTEM.equals(tag)) return context.getString(R.string.preference_language_system);
        for (String[] language : LANGUAGES) if (language[0].equals(tag)) return language[1];
        Locale locale = localeOf(tag);
        return locale == null ? tag : locale.getDisplayName(locale);
    }

    /** Same order as {@link #LANGUAGES}, for ListPreference entry values */
    @NonNull
    public static CharSequence[] values() {
        CharSequence[] values = new CharSequence[LANGUAGES.length];
        for (int i = 0; i < LANGUAGES.length; i++) values[i] = LANGUAGES[i][0];
        return values;
    }
}
