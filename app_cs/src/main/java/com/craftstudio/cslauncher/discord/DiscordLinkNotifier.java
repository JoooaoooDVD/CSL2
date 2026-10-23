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
    static final String SUPABASE_URL = "https://ygteavpkursyeyfthlxm.supabase.co";
    private static final String ENDPOINT = SUPABASE_URL + "/rest/v1/discord_link_events";
    private static final String PLAY_ENDPOINT = SUPABASE_URL + "/rest/v1/launcher_events";
    static final String ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
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
            body.put("device", deviceLabel());
            postJson(ENDPOINT, body, kind + " event");
        } catch (Throwable t) {
            Log.w(TAG, "Could not build " + kind + " event", t);
        }
    }

    // ------------------------------------------------------------------ play sessions

    /**
     * Linked players only: tells the community bot that a game session started, so the server can
     * show "playing now", favourite versions/servers and personal playtime. Nothing is sent for
     * players who did not link their Discord account.
     */
    static void playStarted(@NonNull Context context, @NonNull String sessionId,
                            @NonNull DiscordPresenceManager.Snapshot snapshot) {
        DiscordAccountStorage.DiscordProfile account = DiscordAccountStorage.loadAccount(context);
        if (!account.isLinked || account.userId == null) return;
        try {
            JSONObject body = new JSONObject();
            body.put("kind", "play_start");
            body.put("session_id", sessionId);
            body.put("discord_user_id", account.userId);
            body.put("username", account.username == null ? JSONObject.NULL : account.username);
            body.put("profile", snapshot.profileName);
            body.put("version", snapshot.version);
            body.put("loader", snapshot.loader.isEmpty() ? "vanilla" : snapshot.loader);
            body.put("mods", snapshot.modCount);
            body.put("app_version", appVersion(context));
            body.put("device", deviceLabel());
            postJson(PLAY_ENDPOINT, body, "play_start");
        } catch (Throwable t) {
            Log.w(TAG, "play_start", t);
        }
    }

    static void playEnded(@NonNull Context context, @NonNull String sessionId,
                          @NonNull DiscordPresenceManager.Snapshot snapshot, long durationMs) {
        DiscordAccountStorage.DiscordProfile account = DiscordAccountStorage.loadAccount(context);
        if (!account.isLinked || account.userId == null) return;
        try {
            JSONObject body = new JSONObject();
            body.put("kind", "play_end");
            body.put("session_id", sessionId);
            body.put("discord_user_id", account.userId);
            body.put("username", account.username == null ? JSONObject.NULL : account.username);
            body.put("profile", snapshot.profileName);
            body.put("version", snapshot.version);
            body.put("loader", snapshot.loader.isEmpty() ? "vanilla" : snapshot.loader);
            body.put("mods", snapshot.modCount);
            body.put("duration_s", Math.max(0L, durationMs / 1000L));
            body.put("last_scene", snapshot.scene.name().toLowerCase(java.util.Locale.ROOT));
            body.put("world", snapshot.worldName.isEmpty() ? JSONObject.NULL : snapshot.worldName);
            boolean privateHost = snapshot.serverHost.isEmpty() || isPrivateHost(snapshot.serverHost);
            body.put("server_name", snapshot.serverHost.isEmpty() ? JSONObject.NULL
                    : (privateHost ? "Private server" : snapshot.serverName));
            body.put("server_host", privateHost ? JSONObject.NULL : snapshot.serverHost);
            body.put("app_version", appVersion(context));
            // The process is about to die: give the request a moment to leave the device
            Thread t = postJson(PLAY_ENDPOINT, body, "play_end");
            try { t.join(2000L); } catch (InterruptedException ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "play_end", t);
        }
    }

    private static boolean isPrivateHost(@NonNull String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")
                || h.matches("172\\.(1[6-9]|2\\d|3[01])\\..*") || h.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                || h.startsWith("[") || h.endsWith(".local");
    }

    // ------------------------------------------------------------------ transport

    /** Fire-and-forget INSERT through the anon key (table policies allow INSERT only) */
    @NonNull
    private static Thread postJson(@NonNull String endpoint, @NonNull JSONObject body, @NonNull String what) {
        Thread thread = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                byte[] bytes = body.toString().getBytes("UTF-8");
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
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
                Log.i(TAG, what + " queued: HTTP " + code);
            } catch (Throwable t) {
                Log.w(TAG, "Could not queue " + what, t);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "CSL-Community");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @NonNull
    private static String deviceLabel() {
        return (Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE).trim();
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
