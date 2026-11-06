package com.craftstudio.cslauncher.friends;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import org.json.JSONObject;

/**
 * The launcher's CS account (username + password, Supabase). Separate from the engine's own
 * friend identity: this one unlocks the launcher Friends page, notifications and e4mc sharing for
 * Java worlds. Only the stable friend token is kept on the device, never the password.
 */
public final class CsAccount {
    private static final String K_TOKEN = "cs.friends.token";
    private static final String K_USER = "cs.friends.username";
    private static final String K_NAME = "cs.friends.name";
    private static final String K_CODE = "cs.friends.code";
    private static final String K_ID = "cs.friends.id";

    private CsAccount() {}

    private static SharedPreferences prefs() { return LauncherPreferences.DEFAULT_PREF; }

    public static boolean isSignedIn() {
        String t = prefs().getString(K_TOKEN, null);
        return t != null && t.length() >= 16;
    }

    @Nullable public static String token() { return prefs().getString(K_TOKEN, null); }
    @NonNull public static String username() { return prefs().getString(K_USER, ""); }
    @NonNull public static String displayName() { return prefs().getString(K_NAME, ""); }
    @NonNull public static String code() { return prefs().getString(K_CODE, ""); }
    @NonNull public static String id() { return prefs().getString(K_ID, ""); }

    /** Stores the session returned by cs_account_login / cs_account_register. */
    public static void store(@NonNull JSONObject session) {
        prefs().edit()
                .putString(K_TOKEN, session.optString("token"))
                .putString(K_USER, session.optString("username"))
                .putString(K_NAME, session.optString("name"))
                .putString(K_CODE, session.optString("code"))
                .putString(K_ID, session.optString("id"))
                .apply();
    }

    public static void updateMe(@Nullable JSONObject me) {
        if (me == null) return;
        prefs().edit()
                .putString(K_NAME, me.optString("name", displayName()))
                .putString(K_CODE, me.optString("code", code()))
                .putString(K_ID, me.optString("id", id()))
                .apply();
    }

    public static void signOut() {
        prefs().edit().remove(K_TOKEN).remove(K_USER).remove(K_NAME).remove(K_CODE).remove(K_ID).apply();
    }

    /** Username rule shared with the backend. */
    public static boolean validUsername(@Nullable String u) {
        return u != null && u.matches("^[A-Za-z0-9_]{3,16}$");
    }

    public static boolean validPassword(@Nullable String p) {
        return p != null && p.length() >= 6 && p.length() <= 72;
    }
}
