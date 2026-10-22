package com.craftstudio.cslauncher.discord;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Dedicated persistence store for linked Discord accounts in CS Launcher Plus.
 * Guarantees that linked accounts are preserved across launcher restarts and profile changes.
 */
public final class DiscordAccountStorage {

    private static final String PREF_NAME = "cslauncher_discord_account";
    private static final String KEY_IS_LINKED = "is_linked";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_AVATAR_URL = "avatar_url";
    private static final String KEY_ACCESS_TOKEN = "access_token";

    public static final class DiscordProfile {
        public final boolean isLinked;
        @Nullable public final String userId;
        @Nullable public final String username;
        @Nullable public final String displayName;
        @Nullable public final String avatarUrl;

        public DiscordProfile(boolean isLinked,
                              @Nullable String userId,
                              @Nullable String username,
                              @Nullable String displayName,
                              @Nullable String avatarUrl) {
            this.isLinked = isLinked;
            this.userId = userId;
            this.username = username;
            this.displayName = displayName;
            this.avatarUrl = avatarUrl;
        }

        @NonNull
        public String getEffectiveName() {
            if (displayName != null && !displayName.trim().isEmpty() && !"Discord User".equalsIgnoreCase(displayName.trim())) {
                return displayName.trim();
            }
            if (username != null && !username.trim().isEmpty() && !"Discord User".equalsIgnoreCase(username.trim())) {
                return username.trim();
            }
            if (displayName != null && !displayName.trim().isEmpty()) {
                return displayName.trim();
            }
            return "Connected User";
        }
    }

    private DiscordAccountStorage() {}

    /**
     * Saves the linked Discord profile to persistent storage.
     */
    public static synchronized void saveAccount(@NonNull Context context,
                                                @Nullable String userId,
                                                @Nullable String username,
                                                @Nullable String displayName,
                                                @Nullable String avatarUrl,
                                                @Nullable String accessToken) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        String prevUserId = prefs.getString(KEY_USER_ID, null);
        String prevUsername = prefs.getString(KEY_USERNAME, null);
        String prevDisplayName = prefs.getString(KEY_DISPLAY_NAME, null);
        String prevAvatarUrl = prefs.getString(KEY_AVATAR_URL, null);
        String prevAccessToken = prefs.getString(KEY_ACCESS_TOKEN, null);

        // Never overwrite a valid username / display name with null or generic "Discord User"
        if ((userId == null || userId.trim().isEmpty() || "0".equals(userId.trim())) && prevUserId != null) {
            userId = prevUserId;
        }
        if ((username == null || username.trim().isEmpty() || "Discord User".equalsIgnoreCase(username.trim())) && prevUsername != null && !"Discord User".equalsIgnoreCase(prevUsername.trim())) {
            username = prevUsername;
        }
        if ((displayName == null || displayName.trim().isEmpty() || "Discord User".equalsIgnoreCase(displayName.trim())) && prevDisplayName != null && !"Discord User".equalsIgnoreCase(prevDisplayName.trim())) {
            displayName = prevDisplayName;
        }
        if ((avatarUrl == null || avatarUrl.trim().isEmpty()) && prevAvatarUrl != null) {
            avatarUrl = prevAvatarUrl;
        }
        if ((accessToken == null || accessToken.trim().isEmpty()) && prevAccessToken != null) {
            accessToken = prevAccessToken;
        }

        prefs.edit()
                .putBoolean(KEY_IS_LINKED, true)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_USERNAME, username)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putString(KEY_AVATAR_URL, avatarUrl)
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .apply();
    }

    @Nullable
    public static synchronized String getAccessToken(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getString(KEY_ACCESS_TOKEN, null);
    }

    /**
     * Loads the linked Discord profile from SharedPreferences.
     */
    @NonNull
    public static synchronized DiscordProfile loadAccount(@NonNull Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean isLinked = prefs.getBoolean(KEY_IS_LINKED, false);

        if (isLinked) {
            String uName = prefs.getString(KEY_USERNAME, null);
            String dName = prefs.getString(KEY_DISPLAY_NAME, null);
            String uId = prefs.getString(KEY_USER_ID, null);
            String avUrl = prefs.getString(KEY_AVATAR_URL, null);

            if (dName != null && "Discord User".equalsIgnoreCase(dName.trim())) {
                dName = (uName != null && !uName.trim().isEmpty() && !"Discord User".equalsIgnoreCase(uName.trim()))
                        ? uName.trim() : null;
            }
            if (uName != null && "Discord User".equalsIgnoreCase(uName.trim())) {
                uName = dName;
            }

            return new DiscordProfile(true, uId, uName, dName, avUrl);
        }

        return new DiscordProfile(false, null, null, null, null);
    }

    /**
     * Clears all linked Discord account details from persistent storage.
     */
    public static synchronized void clearAccount(@NonNull Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }
}
