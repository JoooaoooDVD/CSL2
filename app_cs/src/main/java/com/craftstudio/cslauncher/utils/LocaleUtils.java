package com.craftstudio.cslauncher.utils;


import static com.craftstudio.cslauncher.prefs.LauncherPreferences.DEFAULT_PREF;
import static com.craftstudio.cslauncher.prefs.LauncherPreferences.PREF_FORCE_ENGLISH;

import android.content.*;
import android.content.res.*;
import android.os.Build;
import android.os.LocaleList;

import androidx.preference.*;
import java.util.*;

public class LocaleUtils extends ContextWrapper {

    public LocaleUtils(Context base) {
        super(base);
    }

    /** Applies the "Launcher language" setting (or the legacy force-English switch) to the context */
    public static ContextWrapper setLocale(Context context) {
        if (DEFAULT_PREF == null) {
            DEFAULT_PREF = PreferenceManager.getDefaultSharedPreferences(context);
            // Too early to initialize all prefs here, as this is called by PojavApplication
            // before storage checks are done and before the storage paths are initialized.
            // So only initialize PREF_FORCE_ENGLISH for the check below.
            PREF_FORCE_ENGLISH = DEFAULT_PREF.getBoolean("force_english", false);
        }

        Locale locale = LauncherLanguages.localeOf(LauncherLanguages.current(DEFAULT_PREF));
        if (locale != null) {
            context = apply(context, locale);
        }

        return new LocaleUtils(context);
    }

    private static Context apply(Context context, Locale locale) {
        Resources resources = context.getResources();
        Configuration configuration = resources.getConfiguration();

        configuration.setLocale(locale);
        Locale.setDefault(locale);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N){
            LocaleList localeList = new LocaleList(locale);
            LocaleList.setDefault(localeList);
            configuration.setLocales(localeList);
        }

        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1){
            context = context.createConfigurationContext(configuration);
        }
        return context;
    }

    /** Saves a new language and restarts the launcher so every screen picks it up */
    public static void changeLanguage(android.app.Activity activity, String tag) {
        LauncherLanguages.save(activity, tag);
        com.craftstudio.cslauncher.utils.storage.StorageRoot.restartLauncher(activity);
    }
}
