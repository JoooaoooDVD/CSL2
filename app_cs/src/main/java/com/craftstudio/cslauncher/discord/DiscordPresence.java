package com.craftstudio.cslauncher.discord;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.fragments.InstanceVersionResolver;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Discord Rich Presence while the game runs: "Playing Minecraft", the profile (modpack) name,
 * "1.21.4 • Fabric", the elapsed time, the CS Launcher Plus logo as the big image and the
 * modpack icon as the small one.
 * <p>
 * Android has no local Discord RPC socket, so the game process keeps its own Discord gateway
 * session (logged in once in Settings → Discord) and publishes the activity there. Runs on a
 * background thread, reconnects with back-off, never blocks or crashes the game. The process
 * ends with the game, which also ends the session and clears the status.
 */
public final class DiscordPresence {
    private static final String TAG = "DiscordPresence";
    private static final String GATEWAY_HOST = "gateway.discord.gg";
    private static final String GATEWAY_PATH = "/?v=10&encoding=json";
    private static final String API = "https://discord.com/api/v10";
    /** Art asset keys expected in the Discord application (Developer Portal → Rich Presence → Art Assets) */
    private static final String ASSET_LOGO = "cs_logo";

    @Nullable private static DiscordPresence sRunning;

    private final String mToken;
    private final String mApplicationId;
    private final long mStartedAt = System.currentTimeMillis();
    @Nullable private final String mDetails;
    @Nullable private final String mState;
    @Nullable private final String mSmallText;
    @Nullable private final String mIconUrl;
    @NonNull private final String mLoaderKey;

    private final ScheduledExecutorService mTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "DiscordPresence-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean mStopped;
    private volatile MiniWebSocket mSocket;
    private volatile ScheduledFuture<?> mHeartbeat;
    private volatile Integer mSequence;
    private volatile boolean mAcked = true;
    private volatile boolean mFatal;
    private volatile String mStatus = "online";
    @Nullable private volatile JsonObject mAssets;
    private volatile boolean mAssetsResolved;

    /** Starts the presence for this game session (no-op when disabled or not logged in) */
    public static synchronized void startForGame(@NonNull Context context, @Nullable Instance instance) {
        if (sRunning != null) return;
        if (!LauncherPreferences.PREF_DISCORD_RPC) return;
        String token = DiscordAccount.getToken(context);
        if (TextUtils.isEmpty(token)) return;
        try {
            sRunning = new DiscordPresence(token, instance);
            sRunning.start();
        } catch (RuntimeException e) {
            Log.w(TAG, "Discord presence not started", e);
            sRunning = null;
        }
    }

    private DiscordPresence(@NonNull String token, @Nullable Instance instance) {
        mToken = token;
        mApplicationId = DiscordAccount.getApplicationId();
        String profileName = instance == null ? null : instance.name;
        String mcVersion = null;
        String loader = "";
        if (instance != null && instance.getKey() != null) {
            InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(instance.getKey());
            mcVersion = info.mcVersion;
            loader = info.loader == null ? "" : info.loader;
        }
        String loaderLabel = loaderLabel(loader);
        mLoaderKey = loader.isEmpty() ? "vanilla" : loader.toLowerCase(Locale.ROOT);
        if (LauncherPreferences.PREF_DISCORD_RPC_DETAILS) {
            mDetails = TextUtils.isEmpty(profileName) ? null : clamp(profileName);
            mState = clamp(TextUtils.isEmpty(mcVersion) ? loaderLabel : mcVersion + " • " + loaderLabel);
            mSmallText = mDetails != null ? mDetails : loaderLabel;
            mIconUrl = instance != null && instance.iconUrl != null && instance.iconUrl.startsWith("https://")
                    ? instance.iconUrl : null;
        } else {
            mDetails = null;
            mState = null;
            mSmallText = null;
            mIconUrl = null;
        }
    }

    @NonNull
    private static String loaderLabel(@NonNull String loader) {
        switch (loader.toLowerCase(Locale.ROOT)) {
            case "fabric": return "Fabric";
            case "forge": return "Forge";
            case "neoforge": return "NeoForge";
            case "quilt": return "Quilt";
            case "optifine": return "OptiFine";
            case "": return "Vanilla";
            default: return Character.toUpperCase(loader.charAt(0)) + loader.substring(1);
        }
    }

    /** Discord limits activity strings to 128 characters (and needs at least 2) */
    @NonNull
    private static String clamp(@NonNull String text) {
        String trimmed = text.trim();
        if (trimmed.length() < 2) trimmed = trimmed + "  ";
        return trimmed.length() > 128 ? trimmed.substring(0, 127) + "…" : trimmed;
    }

    private void start() {
        Thread thread = new Thread(this::loop, "DiscordPresence");
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------ connection loop

    private void loop() {
        int attempt = 0;
        while (!mStopped && !mFatal) {
            MiniWebSocket socket = new MiniWebSocket(GATEWAY_HOST, GATEWAY_PATH);
            mSocket = socket;
            mSequence = null;
            mAcked = true;
            long connectedAt = System.currentTimeMillis();
            try {
                socket.run(new MiniWebSocket.Listener() {
                    @Override public void onOpen() {}

                    @Override
                    public void onText(@NonNull String text) {
                        onMessage(socket, text);
                    }

                    @Override
                    public void onClosed(int code, @NonNull String reason) {
                        Log.i(TAG, "Gateway closed " + code + " " + reason);
                        // 4004: token rejected; 4010-4014: shard / intents / version problems
                        if (code == 4004 || (code >= 4010 && code <= 4014)) mFatal = true;
                    }
                });
            } catch (IOException | RuntimeException e) {
                Log.i(TAG, "Gateway connection ended: " + e.getMessage());
            }
            stopHeartbeat();
            if (mStopped || mFatal) break;
            // A session that lived a while starts over with a short wait
            if (System.currentTimeMillis() - connectedAt > 60_000) attempt = 0;
            long wait = Math.min(60_000L, 2_000L << Math.min(attempt, 5));
            attempt++;
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                break;
            }
        }
        mTimer.shutdownNow();
        if (mFatal) Log.w(TAG, "Discord rejected the session; log in again in Settings → Discord");
    }

    private void onMessage(@NonNull MiniWebSocket socket, @NonNull String text) {
        JsonObject message;
        try {
            message = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        if (message.has("s") && !message.get("s").isJsonNull()) mSequence = message.get("s").getAsInt();
        int op = message.has("op") ? message.get("op").getAsInt() : -1;
        switch (op) {
            case 10: { // HELLO
                long interval = 41_250;
                try {
                    interval = message.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                } catch (RuntimeException ignored) {}
                startHeartbeat(socket, interval);
                socket.sendText(identify().toString());
                break;
            }
            case 11: // HEARTBEAT ACK
                mAcked = true;
                break;
            case 1: // the gateway asks for a heartbeat now
                sendHeartbeat(socket);
                break;
            case 0: { // DISPATCH
                String type = message.has("t") && !message.get("t").isJsonNull() ? message.get("t").getAsString() : "";
                if ("READY".equals(type)) {
                    readStatus(message.getAsJsonObject("d"));
                    resolveAssetsOnce();
                    socket.sendText(presenceUpdate().toString());
                    Log.i(TAG, "Discord presence published");
                }
                break;
            }
            case 7: // RECONNECT
            case 9: // INVALID SESSION
                socket.close(4000);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ heartbeat

    private void startHeartbeat(@NonNull MiniWebSocket socket, long interval) {
        stopHeartbeat();
        long first = (long) (interval * Math.random());
        mHeartbeat = mTimer.scheduleAtFixedRate(() -> {
            if (!mAcked) {
                // No ACK since the last beat: zombie connection, reconnect
                socket.close(4000);
                return;
            }
            sendHeartbeat(socket);
        }, first, interval, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat(@NonNull MiniWebSocket socket) {
        JsonObject beat = new JsonObject();
        beat.addProperty("op", 1);
        Integer sequence = mSequence;
        if (sequence == null) beat.add("d", com.google.gson.JsonNull.INSTANCE);
        else beat.addProperty("d", sequence);
        mAcked = false;
        socket.sendText(beat.toString());
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> heartbeat = mHeartbeat;
        if (heartbeat != null) heartbeat.cancel(false);
        mHeartbeat = null;
    }

    // ------------------------------------------------------------------ payloads

    @NonNull
    private JsonObject identify() {
        JsonObject properties = new JsonObject();
        properties.addProperty("os", "Android");
        properties.addProperty("browser", "Discord Android");
        properties.addProperty("device", "CS Launcher Plus");
        JsonObject data = new JsonObject();
        data.addProperty("token", mToken);
        data.add("properties", properties);
        data.addProperty("compress", false);
        data.addProperty("large_threshold", 50);
        JsonObject identify = new JsonObject();
        identify.addProperty("op", 2);
        identify.add("d", data);
        return identify;
    }

    /** Keeps the user's own status (online / idle / dnd) instead of forcing "online" */
    private void readStatus(@Nullable JsonObject ready) {
        if (ready == null || !ready.has("sessions") || !ready.get("sessions").isJsonArray()) return;
        String fallback = null;
        for (JsonElement element : ready.getAsJsonArray("sessions")) {
            if (!element.isJsonObject()) continue;
            JsonObject session = element.getAsJsonObject();
            if (!session.has("status") || session.get("status").isJsonNull()) continue;
            String status = session.get("status").getAsString();
            if (session.has("active") && session.get("active").getAsBoolean()) {
                mStatus = status;
                return;
            }
            if (fallback == null) fallback = status;
        }
        if (fallback != null && !"invisible".equals(fallback)) mStatus = fallback;
    }

    @NonNull
    private JsonObject presenceUpdate() {
        JsonObject activity = new JsonObject();
        activity.addProperty("name", "Minecraft");
        activity.addProperty("type", 0); // Playing
        if (mDetails != null) activity.addProperty("details", mDetails);
        if (mState != null) activity.addProperty("state", mState);
        JsonObject timestamps = new JsonObject();
        timestamps.addProperty("start", mStartedAt);
        activity.add("timestamps", timestamps);
        if (!mApplicationId.isEmpty()) {
            activity.addProperty("application_id", mApplicationId);
            JsonObject assets = mAssets;
            if (assets != null && assets.size() > 0) activity.add("assets", assets);
        }
        JsonArray activities = new JsonArray();
        activities.add(activity);

        JsonObject data = new JsonObject();
        data.addProperty("since", 0);
        data.add("activities", activities);
        data.addProperty("status", mStatus);
        data.addProperty("afk", false);
        JsonObject update = new JsonObject();
        update.addProperty("op", 3);
        update.add("d", data);
        return update;
    }

    /** Big image: the launcher logo; small image: the modpack icon (or the loader icon) */
    private void resolveAssetsOnce() {
        if (mAssetsResolved || mApplicationId.isEmpty()) return;
        mAssetsResolved = true;
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", ASSET_LOGO);
        assets.addProperty("large_text", "CS Launcher Plus");
        String small = mIconUrl == null ? null : externalAsset(mIconUrl);
        if (small == null && LauncherPreferences.PREF_DISCORD_RPC_DETAILS) small = mLoaderKey;
        if (small != null) {
            assets.addProperty("small_image", small);
            if (mSmallText != null) assets.addProperty("small_text", mSmallText);
        }
        mAssets = assets;
    }

    /** Turns a public image URL into an "mp:external/..." asset (Discord media proxy) */
    @Nullable
    private String externalAsset(@NonNull String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API + "/applications/" + mApplicationId + "/external-assets").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", mToken);
            connection.setRequestProperty("Content-Type", "application/json");
            JsonObject body = new JsonObject();
            JsonArray urls = new JsonArray();
            urls.add(url);
            body.add("urls", urls);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() / 100 != 2) return null;
            String response;
            try (InputStream in = connection.getInputStream()) {
                response = IOUtils.toString(in, StandardCharsets.UTF_8);
            }
            JsonArray result = JsonParser.parseString(response).getAsJsonArray();
            if (result.size() == 0) return null;
            JsonObject first = result.get(0).getAsJsonObject();
            if (!first.has("external_asset_path")) return null;
            return "mp:" + first.get("external_asset_path").getAsString();
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "Modpack icon not available for Discord: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
