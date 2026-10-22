package com.craftstudio.cslauncher.discord;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.fragments.InstanceVersionResolver;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CS Launcher Plus — Discord Rich Presence Manager.
 *
 * Coordinates live game activity updates with Discord Gateway and external Android RPC apps.
 * Extracts profile name, Minecraft version, mod loader, installed mod count, and modpack titles.
 */
public class DiscordRpcManager {
    private static final String TAG = "DiscordRpcManager";
    private static final Pattern MC_VERSION_PATTERN = Pattern.compile("(1\\.\\d+(\\.\\d+)?)");

    public static final String CS_LAUNCHER_LOGO_URL =
            DiscordPresenceManager.LAUNCHER_ICON_URL;
    public static final String ICON_FORGE =
            "https://raw.githubusercontent.com/MultiMC/Launcher/develop/application/resources/multimc/scalable/forge.svg";
    public static final String ICON_FABRIC =
            "https://raw.githubusercontent.com/MultiMC/Launcher/develop/application/resources/multimc/scalable/fabric.svg";
    public static final String ICON_QUILT =
            "https://raw.githubusercontent.com/MultiMC/Launcher/develop/application/resources/multimc/scalable/quilt.svg";
    public static final String ICON_NEOFORGE =
            "https://raw.githubusercontent.com/neoforged/NeoForge/main/neoforge_icon.png";
    public static final String ICON_VANILLA =
            "https://raw.githubusercontent.com/MultiMC/Launcher/develop/application/resources/multimc/scalable/grass.svg";

    public static class ActivityInfo {
        public String appName = "CS Launcher Plus";
        public String profileName = "Default";
        public String mcVersion = "1.20.1";
        public String loader = "Vanilla";
        public String loaderIcon = ICON_VANILLA;
        public String modpackName = null;
        public int modCount = 0;
        public long startTimestamp = 0;
        public String details = "";
        public String state = "";
        public String largeImage = CS_LAUNCHER_LOGO_URL;
        public String largeText = "CS Launcher Plus";
        public String smallImage = ICON_VANILLA;
        public String smallText = "Minecraft";
    }

    private static DiscordRpcManager sInstance;

    public static synchronized DiscordRpcManager getInstance() {
        if (sInstance == null) {
            sInstance = new DiscordRpcManager();
        }
        return sInstance;
    }

    private final DiscordGatewayClient mClient = new DiscordGatewayClient();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private ActivityInfo mCurrentActivity;
    private boolean mIsGameRunning = false;

    private DiscordRpcManager() {
    }

    public DiscordGatewayClient getClient() {
        return mClient;
    }

    @Nullable
    public ActivityInfo getCurrentActivity() {
        return mCurrentActivity;
    }

    public boolean isGameRunning() {
        return mIsGameRunning;
    }

    /**
     * Called when a Minecraft game session begins.
     */
    public synchronized void onGameStarted(@NonNull Context context,
                                          @Nullable Instance instance,
                                          @Nullable String versionId) {
        mIsGameRunning = true;
        mCurrentActivity = buildActivityInfo(context, instance, versionId);
        mCurrentActivity.startTimestamp = System.currentTimeMillis();

        Log.i(TAG, "Game session started: " + mCurrentActivity.details + " | " + mCurrentActivity.state);

        // 1. Broadcast to external apps (like Kizzy or CustomRPC)
        if (LauncherPreferences.PREF_DISCORD_BROADCAST_EXTERNAL) {
            broadcastToExternalApps(context, mCurrentActivity);
        }

        // 2. Direct Discord Gateway connection if enabled and token present
        if (LauncherPreferences.PREF_DISCORD_RPC_ENABLED &&
                LauncherPreferences.PREF_DISCORD_TOKEN != null &&
                !LauncherPreferences.PREF_DISCORD_TOKEN.trim().isEmpty()) {

            JSONObject presence = createPresenceJson(mCurrentActivity);
            mClient.connect(LauncherPreferences.PREF_DISCORD_TOKEN,
                    LauncherPreferences.PREF_DISCORD_APP_ID,
                    presence);
        }
    }

    /**
     * Called when the Minecraft session ends or crashes.
     */
    public synchronized void onGameStopped() {
        onGameStopped(null);
    }

    public synchronized void onGameStopped(@Nullable Context context) {
        mIsGameRunning = false;
        Log.i(TAG, "Game session stopped. Clearing presence.");
        mCurrentActivity = null;

        if (context != null && LauncherPreferences.PREF_DISCORD_BROADCAST_EXTERNAL) {
            try {
                Intent kizzyClear = new Intent("com.kizzy.rpc.STOP");
                kizzyClear.putExtra("package_name", context.getPackageName());
                context.sendBroadcast(kizzyClear);

                Intent csIntent = new Intent("com.craftstudio.cslauncher.DISCORD_RPC_STOP");
                context.sendBroadcast(csIntent);
            } catch (Throwable ignored) {}
        }

        if (mClient.isConnected()) {
            mClient.updatePresence(null);
            mClient.disconnect();
        } else {
            mClient.disconnect();
        }
    }

    /**
     * Called when user is idle in the launcher / home screen.
     */
    public synchronized void onLauncherIdle(@NonNull Context context, @Nullable Instance instance) {
        mIsGameRunning = false;
        ActivityInfo info = new ActivityInfo();
        info.appName = "CS Launcher Plus";
        String profileName = (instance != null && instance.name != null && !instance.name.trim().isEmpty())
                ? instance.name.trim() : "Default Profile";
        info.profileName = profileName;
        info.mcVersion = "";
        info.loader = "";
        info.modCount = 0;
        info.details = "Home Screen · Android launcher";
        info.state = "Profile: " + profileName;
        info.largeImage = CS_LAUNCHER_LOGO_URL;
        info.largeText = "CS Launcher Plus";
        info.smallImage = null;
        info.smallText = null;
        info.startTimestamp = 0L;
        mCurrentActivity = info;

        if (LauncherPreferences.PREF_DISCORD_BROADCAST_EXTERNAL) {
            broadcastToExternalApps(context, info);
        }

        if (LauncherPreferences.PREF_DISCORD_RPC_ENABLED &&
                LauncherPreferences.PREF_DISCORD_TOKEN != null &&
                !LauncherPreferences.PREF_DISCORD_TOKEN.trim().isEmpty() &&
                mClient.isConnected()) {
            JSONObject presence = createPresenceJson(info);
            mClient.updatePresence(presence);
        }
    }

    /**
     * Generate comprehensive Activity info from instance and version metadata.
     */
    @NonNull
    public ActivityInfo buildActivityInfo(@NonNull Context context,
                                          @Nullable Instance instance,
                                          @Nullable String versionId) {
        ActivityInfo info = new ActivityInfo();
        info.appName = "CS Launcher Plus";

        // 1. Profile Name
        if (instance != null && instance.name != null && !instance.name.trim().isEmpty()) {
            info.profileName = instance.name.trim();
        } else {
            info.profileName = "Default Profile";
        }

        // 2. Minecraft Version
        String mc = null;
        if (instance != null && instance.getKey() != null) {
            try {
                mc = InstanceVersionResolver.resolve(instance.getKey()).mcVersion;
            } catch (Throwable ignored) {}
        }
        if (mc == null && versionId != null) {
            Matcher matcher = MC_VERSION_PATTERN.matcher(versionId);
            if (matcher.find()) {
                mc = matcher.group(1);
            } else {
                mc = versionId;
            }
        }
        info.mcVersion = mc != null ? mc : "1.20.1";

        // 3. Mod Loader Detection
        String vLower = (versionId != null ? versionId : "").toLowerCase();
        if (vLower.contains("forge") && !vLower.contains("neoforge")) {
            info.loader = "Forge";
            info.loaderIcon = ICON_FORGE;
        } else if (vLower.contains("neoforge")) {
            info.loader = "NeoForge";
            info.loaderIcon = ICON_NEOFORGE;
        } else if (vLower.contains("fabric")) {
            info.loader = "Fabric";
            info.loaderIcon = ICON_FABRIC;
        } else if (vLower.contains("quilt")) {
            info.loader = "Quilt";
            info.loaderIcon = ICON_QUILT;
        } else {
            info.loader = "Vanilla";
            info.loaderIcon = ICON_VANILLA;
        }

        // 4. Modpack Title Detection
        File rootDir = instance != null ? instance.getInstanceRoot() : null;
        File gameDir = instance != null ? instance.getGameDirectory() : null;

        String packName = null;
        if (rootDir != null) {
            packName = extractModpackName(new File(rootDir, "modrinth.index.json"));
            if (packName == null) packName = extractModpackName(new File(rootDir, "manifest.json"));
        }
        if (packName == null && gameDir != null) {
            packName = extractModpackName(new File(gameDir, "modrinth.index.json"));
            if (packName == null) packName = extractModpackName(new File(gameDir, "manifest.json"));
        }
        info.modpackName = packName;

        // 5. Mod Count Calculation
        int count = 0;
        if (gameDir != null) {
            File modsDir = new File(gameDir, "mods");
            if (modsDir.exists() && modsDir.isDirectory()) {
                File[] jars = modsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
                if (jars != null) count = jars.length;
            }
        }
        info.modCount = count;

        // 6. Build User-Visible Details & State
        if (LauncherPreferences.PREF_DISCORD_SHOW_MODS && info.modpackName != null && !info.modpackName.isEmpty()) {
            info.details = "Modpack: " + info.modpackName;
        } else {
            info.details = "Playing Minecraft " + info.mcVersion + " (" + info.loader + ")";
        }

        if (LauncherPreferences.PREF_DISCORD_SHOW_MODS && info.modCount > 0) {
            info.state = "Profile: " + info.profileName + " • " + info.modCount + " Mods";
        } else {
            info.state = "Profile: " + info.profileName;
        }

        info.largeImage = CS_LAUNCHER_LOGO_URL;
        info.largeText = "CS Launcher Plus";
        info.smallImage = DiscordPresenceManager.getInstance(context).getProfileIconUrl(instance);
        info.smallText = info.profileName + " · " + info.loader;

        return info;
    }

    @Nullable
    private String extractModpackName(File jsonFile) {
        if (jsonFile == null || !jsonFile.exists() || !jsonFile.canRead()) return null;
        try {
            String content = Tools.read(jsonFile.getAbsolutePath());
            JSONObject obj = new JSONObject(content);
            if (obj.has("name") && !obj.isNull("name")) {
                return obj.getString("name");
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Converts ActivityInfo into Discord Gateway Presence JSON object (OP 3).
     */
    @NonNull
    public JSONObject createPresenceJson(@NonNull ActivityInfo info) {
        JSONObject pres = new JSONObject();
        try {
            pres.put("status", "online");
            pres.put("since", JSONObject.NULL);
            pres.put("afk", false);

            JSONArray activities = new JSONArray();
            JSONObject act = new JSONObject();
            act.put("name", info.appName);
            act.put("type", 0); // 0 = Playing

            if (LauncherPreferences.PREF_DISCORD_APP_ID != null &&
                    !LauncherPreferences.PREF_DISCORD_APP_ID.trim().isEmpty()) {
                act.put("application_id", LauncherPreferences.PREF_DISCORD_APP_ID.trim());
            }

            act.put("details", info.details);
            act.put("state", info.state);

            if (LauncherPreferences.PREF_DISCORD_SHOW_ELAPSED && info.startTimestamp > 0) {
                JSONObject timestamps = new JSONObject();
                timestamps.put("start", info.startTimestamp);
                act.put("timestamps", timestamps);
            }

            JSONObject assets = new JSONObject();
            assets.put("large_image", info.largeImage);
            assets.put("large_text", info.largeText);
            assets.put("small_image", info.smallImage);
            assets.put("small_text", info.smallText);
            act.put("assets", assets);

            activities.put(act);
            pres.put("activities", activities);
        } catch (Throwable t) {
            Log.e(TAG, "Error formatting presence json", t);
        }
        return pres;
    }

    /**
     * Sends broadcast intent for external Android RPC managers like Kizzy.
     */
    private void broadcastToExternalApps(Context context, ActivityInfo info) {
        try {
            Intent kizzyIntent = new Intent("com.kizzy.rpc.UPDATE");
            kizzyIntent.putExtra("package_name", context.getPackageName());
            kizzyIntent.putExtra("name", info.appName);
            kizzyIntent.putExtra("details", info.details);
            kizzyIntent.putExtra("state", info.state);
            kizzyIntent.putExtra("large_image", info.largeImage);
            kizzyIntent.putExtra("large_text", info.largeText);
            kizzyIntent.putExtra("small_image", info.smallImage);
            kizzyIntent.putExtra("small_text", info.smallText);
            kizzyIntent.putExtra("start_timestamp", info.startTimestamp);
            kizzyIntent.putExtra("type", 0);
            context.sendBroadcast(kizzyIntent);

            Intent csIntent = new Intent("com.craftstudio.cslauncher.DISCORD_RPC_UPDATE");
            csIntent.putExtra("app_name", info.appName);
            csIntent.putExtra("profile", info.profileName);
            csIntent.putExtra("version", info.mcVersion);
            csIntent.putExtra("loader", info.loader);
            csIntent.putExtra("mod_count", info.modCount);
            csIntent.putExtra("details", info.details);
            csIntent.putExtra("state", info.state);
            csIntent.putExtra("start_timestamp", info.startTimestamp);
            context.sendBroadcast(csIntent);
        } catch (Throwable ignored) {}
    }

    public interface TestCallback {
        void onResult(boolean success, String message);
    }

    /**
     * Tests a Discord token with Gateway without affecting live session.
     */
    public void testConnection(String token, String appId, @NonNull TestCallback callback) {
        new Thread(() -> {
            DiscordGatewayClient testClient = new DiscordGatewayClient();
            final boolean[] completed = {false};

            Runnable timeoutRunnable = () -> {
                synchronized (completed) {
                    if (!completed[0]) {
                        completed[0] = true;
                        testClient.disconnect();
                        mMainHandler.post(() -> callback.onResult(false, "Connection timed out"));
                    }
                }
            };
            mMainHandler.postDelayed(timeoutRunnable, 8000L);

            testClient.setListener(new DiscordGatewayClient.GatewayListener() {
                @Override
                public void onStatusChanged(int statusCode, String message) {
                    if (statusCode == DiscordGatewayClient.STATUS_AUTH_ERROR) {
                        synchronized (completed) {
                            if (!completed[0]) {
                                completed[0] = true;
                                mMainHandler.removeCallbacks(timeoutRunnable);
                                testClient.disconnect();
                                mMainHandler.post(() -> callback.onResult(false, message));
                            }
                        }
                    }
                }

                @Override
                public void onReady(String username, String userId) {
                    synchronized (completed) {
                        if (!completed[0]) {
                            completed[0] = true;
                            mMainHandler.removeCallbacks(timeoutRunnable);
                            testClient.disconnect();
                            mMainHandler.post(() -> callback.onResult(true, "Connected as " + username));
                        }
                    }
                }
            });

            testClient.connect(token, appId, null);
        }, "CS-Discord-Tester").start();
    }
}
