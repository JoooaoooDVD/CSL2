package com.craftstudio.cslauncher.discord;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.prefs.LauncherPreferences;

/**
 * The Discord session used for the Rich Presence (stored privately on this device only).
 * Written by {@link DiscordLoginActivity} in the launcher, read by the game process.
 */
public final class DiscordAccount {
    private static final String FILE = "cs_discord";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_DISPLAY_NAME = "display_name";

    /**
     * Discord application of the presence ("Playing ..." + the art assets "cs_logo", "fabric",
     * "forge", "quilt", "neoforge", "vanilla"). Can be overridden in Settings → Discord.
     */
    public static final String DEFAULT_APPLICATION_ID = "";
    /** Settings key of the optional Application ID override */
    public static final String PREF_APPLICATION_ID = "discordAppId";

    private DiscordAccount() {}

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isLoggedIn(@NonNull Context context) {
        return !TextUtils.isEmpty(getToken(context));
    }

    @Nullable
    public static String getToken(@NonNull Context context) {
        return prefs(context).getString(KEY_TOKEN, null);
    }

    /** Name shown in the settings ("Connected as ...") */
    @Nullable
    public static String getDisplayName(@NonNull Context context) {
        SharedPreferences prefs = prefs(context);
        String display = prefs.getString(KEY_DISPLAY_NAME, null);
        return TextUtils.isEmpty(display) ? prefs.getString(KEY_USER_NAME, null) : display;
    }

    public static void save(@NonNull Context context, @NonNull String token, @Nullable String userId,
                            @Nullable String userName, @Nullable String displayName) {
        // commit(): the game process reads this file when it starts
        prefs(context).edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_USER_NAME, userName)
                .putString(KEY_DISPLAY_NAME, displayName)
                .commit();
    }

    public static void logout(@NonNull Context context) {
        prefs(context).edit().clear().commit();
    }

    @NonNull
    public static String getApplicationId() {
        String custom = LauncherPreferences.DEFAULT_PREF == null ? null
                : LauncherPreferences.DEFAULT_PREF.getString(PREF_APPLICATION_ID, null);
        if (custom != null) {
            custom = custom.trim();
            if (custom.matches("\\d{15,22}")) return custom;
        }
        return DEFAULT_APPLICATION_ID;
    }
}
