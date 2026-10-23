package com.craftstudio.cslauncher.discord;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tells the Craft Studio Discord bot (server/discord-bot) that a Discord account was linked or
 * unlinked in this launcher. The bot then welcomes the user by DM, adds them to the community
 * server (needs the {@code guilds.join} scope granted during linking) and gives the "Linked" role.
 * <p>
 * The event is queued in a write-only table: this app can only INSERT, nothing can read it back
 * except the bot (service key). The access token is deleted by the bot right after use.
 */
final class DiscordLinkNotifier {
    private static final String TAG = "DiscordLink";
    private static final String ENDPOINT = "https://ygteavpkursyeyfthlxm.supabase.co/rest/v1/discord_link_events";
    private static final String ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlndGVhdnBrdXJzeWV5ZnRobHhtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI2ODk5NjksImV4cCI6MjEwODI2NTk2OX0."
            + "wqCioElyk_GlIkeaiRHlls7hk-WDABhigwb1EGy8iJU";
    /** OAuth scopes requested when linking: profile + "add me to the community server" */
    static final String LINK_SCOPES = "identify guilds.join";

    private DiscordLinkNotifier() {}

    static void linked(@NonNull Context context, @NonNull String userId, @Nullable String username,
                       @Nullable String displayName, @Nullable String avatarUrl, @Nullable String accessToken) {
        send(context, "link", userId, username, displayName, avatarUrl, accessToken);
    }

    static void unlinked(@NonNull Context context, @NonNull String userId, @Nullable String username) {
        send(context, "unlink", userId, username, null, null, null);
    }

    private static void send(@NonNull Context context, @NonNull String kind, @NonNull String userId,
                             @Nullable String username, @Nullable String displayName,
                             @Nullable String avatarUrl, @Nullable String accessToken) {
        if (userId.isEmpty() || "0".equals(userId)) return;
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject body = new JSONObject();
                body.put("kind", kind);
                body.put("discord_user_id", userId);
                body.put("username", username == null ? JSONObject.NULL : username);
                body.put("display_name", displayName == null ? JSONObject.NULL : displayName);
                body.put("avatar_url", avatarUrl == null ? JSONObject.NULL : avatarUrl);
                body.put("access_token", accessToken == null ? JSONObject.NULL : accessToken);
                body.put("scopes", LINK_SCOPES);
                body.put("app_version", appVersion(app));
                body.put("device", (Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE).trim());
                byte[] bytes = body.toString().getBytes("UTF-8");

                connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("apikey", ANON_KEY);
                connection.setRequestProperty("Authorization", "Bearer " + ANON_KEY);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Prefer", "return=minimal");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
                int code = connection.getResponseCode();
                Log.i(TAG, kind + " event queued: HTTP " + code);
            } catch (Throwable t) {
                Log.w(TAG, "Could not queue " + kind + " event", t);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "CSL-DiscordLink");
        thread.setDaemon(true);
        thread.start();
    }

    @NonNull
    private static String appVersion(@NonNull Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }
}
