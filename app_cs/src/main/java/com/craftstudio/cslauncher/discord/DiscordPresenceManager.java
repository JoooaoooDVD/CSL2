package com.craftstudio.cslauncher.discord;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.fragments.InstanceVersionResolver;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import java.io.File;

/**
 * Production-ready Discord Rich Presence Manager using official Discord Social SDK
 * unauthenticated Android RPC transport.
 */
public final class DiscordPresenceManager {

    private static final String TAG = "DiscordPresence";

    public static final String APPLICATION_ID = "1551939107549225000";
    public static final String CLIENT_ID = APPLICATION_ID;
    public static final String APP_NAME = "CS Launcher Plus";
    public static final String LARGE_IMAGE_KEY = "app_icon";

    public static final String PREF_DISCORD_RPC_ENABLED = "discord_rpc_enabled";
    public static final String PREF_DISCORD_SHOW_VERSION = "discord_show_version";
    public static final String PREF_DISCORD_SHOW_SERVER = "discord_show_server";
    public static final String PREF_DISCORD_ONLY_IN_GAME = "discord_only_in_game";
    public static final String PREF_DISCORD_SHOW_ELAPSED = "discord_show_elapsed";
    public static final String PREF_DISCORD_SHOW_MODS = "discord_show_mods";

    public enum PresenceState {
        IDLE_LAUNCHER,
        LAUNCHING_MINECRAFT,
        PLAYING_MENU,
        PLAYING_SINGLEPLAYER,
        PLAYING_MULTIPLAYER
    }

    private static volatile DiscordPresenceManager sInstance;

    private final Context mAppContext;
    private final Handler mMainHandler;
    private final DiscordAuthManager mAuthManager;

    private final java.util.concurrent.ExecutorService mPresenceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CSL-Presence");
        thread.setDaemon(true);
        return thread;
    });

    private Instance mSessionInstance;
    private PresenceState mCurrentState = PresenceState.IDLE_LAUNCHER;
    private String mCurrentVersion = "";
    private String mCurrentServer = "";
    private long mSessionStartMs = 0L;

    public static DiscordPresenceManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (DiscordPresenceManager.class) {
                if (sInstance == null) {
                    sInstance = new DiscordPresenceManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private DiscordPresenceManager(Context context) {
        mAppContext = context;
        mMainHandler = new Handler(Looper.getMainLooper());
        mAuthManager = new DiscordAuthManager(context);
        mSessionStartMs = 0L;

        // Initialize official Discord Social SDK client
        initialize();
    }

    public synchronized void initialize() {
        mPresenceExecutor.execute(() -> {
            try {
                long appId = Long.parseLong(APPLICATION_ID);
                DiscordSdkBridge.initialize(mAppContext, appId);
                refreshPresence();
            } catch (Throwable t) {
                Log.w(TAG, "Non-fatal: Discord Social SDK init deferred", t);
            }
        });
    }

    public DiscordAuthManager getAuthManager() {
        return mAuthManager;
    }

    private SharedPreferences preferences() {
        if (LauncherPreferences.DEFAULT_PREF != null) return LauncherPreferences.DEFAULT_PREF;
        return mAppContext.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
    }

    public boolean isRpcEnabled() {
        try {
            return preferences().getBoolean(PREF_DISCORD_RPC_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public void setRpcEnabled(boolean enabled) {
        try {
            preferences().edit().putBoolean(PREF_DISCORD_RPC_ENABLED, enabled).apply();
            LauncherPreferences.PREF_DISCORD_RPC_ENABLED = enabled;
            if (!enabled) {
                clearPresence();
            } else {
                refreshPresence();
            }
        } catch (Throwable ignored) {}
    }

    public boolean isShowVersionEnabled() {
        try {
            return preferences().getBoolean(PREF_DISCORD_SHOW_VERSION, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public void setShowVersionEnabled(boolean enabled) {
        try {
            preferences().edit().putBoolean(PREF_DISCORD_SHOW_VERSION, enabled).apply();
            LauncherPreferences.PREF_DISCORD_SHOW_VERSION = enabled;
            refreshPresence();
        } catch (Throwable ignored) {}
    }

    public boolean isShowServerEnabled() {
        try {
            return preferences().getBoolean(PREF_DISCORD_SHOW_SERVER, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public void setShowServerEnabled(boolean enabled) {
        try {
            preferences().edit().putBoolean(PREF_DISCORD_SHOW_SERVER, enabled).apply();
            LauncherPreferences.PREF_DISCORD_SHOW_SERVER = enabled;
            refreshPresence();
        } catch (Throwable ignored) {}
    }

    public boolean isOnlyInGameEnabled() {
        try {
            return preferences().getBoolean(PREF_DISCORD_ONLY_IN_GAME, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public void setOnlyInGameEnabled(boolean enabled) {
        try {
            preferences().edit().putBoolean(PREF_DISCORD_ONLY_IN_GAME, enabled).apply();
            LauncherPreferences.PREF_DISCORD_ONLY_IN_GAME = enabled;
            refreshPresence();
        } catch (Throwable ignored) {}
    }

    public boolean isShowElapsedEnabled() {
        try {
            return preferences().getBoolean(PREF_DISCORD_SHOW_ELAPSED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public void setShowElapsedEnabled(boolean enabled) {
        try {
            preferences().edit().putBoolean(PREF_DISCORD_SHOW_ELAPSED, enabled).apply();
            LauncherPreferences.PREF_DISCORD_SHOW_ELAPSED = enabled;
            refreshPresence();
        } catch (Throwable ignored) {}
    }

    public boolean isShowModsEnabled() {
        try {
            return preferences().getBoolean(PREF_DISCORD_SHOW_MODS, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public void setShowModsEnabled(boolean enabled) {
        try {
            preferences().edit().putBoolean(PREF_DISCORD_SHOW_MODS, enabled).apply();
            LauncherPreferences.PREF_DISCORD_SHOW_MODS = enabled;
            refreshPresence();
        } catch (Throwable ignored) {}
    }

    public void startLinkingFlow(@NonNull Activity activity) {
        mAuthManager.startAuthorization(activity, null);
    }

    public void unlinkCurrentAccount() {
        mAuthManager.unlinkCurrentAccount();
    }

    public synchronized void setLauncherIdle() {
        mSessionInstance = null;
        mCurrentState = PresenceState.IDLE_LAUNCHER;
        mCurrentVersion = "";
        mCurrentServer = "";
        mSessionStartMs = 0L;
        DiscordRpcManager.getInstance().onLauncherIdle(mAppContext, getActiveInstance());
        refreshPresence();
    }

    public synchronized void setMinecraftLaunching(String version) {
        mSessionInstance = getActiveInstance();
        mCurrentState = PresenceState.LAUNCHING_MINECRAFT;
        mCurrentVersion = version != null ? version : "";
        mSessionStartMs = System.currentTimeMillis();
        refreshPresence();
    }

    public synchronized void setMinecraftPlaying(String version, boolean isMultiplayer, @Nullable String serverName) {
        if (mSessionInstance == null) mSessionInstance = getActiveInstance();
        mCurrentState = isMultiplayer ? PresenceState.PLAYING_MULTIPLAYER : PresenceState.PLAYING_SINGLEPLAYER;
        if (version != null && !version.isEmpty()) {
            mCurrentVersion = version;
        }
        mCurrentServer = serverName != null ? serverName : "";
        if (mSessionStartMs <= 0L) {
            mSessionStartMs = System.currentTimeMillis();
        }
        refreshPresence();
    }

    public synchronized void setMinecraftMainMenu(String version) {
        mCurrentState = PresenceState.PLAYING_MENU;
        if (version != null && !version.isEmpty()) {
            mCurrentVersion = version;
        }
        if (mSessionStartMs <= 0L) {
            mSessionStartMs = System.currentTimeMillis();
        }
        refreshPresence();
    }

    public synchronized void clearPresence() {
        mCurrentState = PresenceState.IDLE_LAUNCHER;
        mSessionStartMs = 0L;
        DiscordSdkBridge.clearRichPresence();
    }

    @Nullable
    private Instance getActiveInstance() {
        try {
            if (mCurrentState != PresenceState.IDLE_LAUNCHER && mSessionInstance != null) return mSessionInstance;
            return Instances.loadSelectedInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    @NonNull
    public String resolveMinecraftVersion(@Nullable Instance instance) {
        if (mCurrentVersion != null && !mCurrentVersion.trim().isEmpty()) {
            return mCurrentVersion.trim();
        }
        if (instance != null && instance.getKey() != null) {
            try {
                String mc = InstanceVersionResolver.resolve(instance.getKey()).mcVersion;
                if (mc != null && !mc.trim().isEmpty()) return mc.trim();
            } catch (Throwable ignored) {}
        }
        if (instance != null && instance.versionId != null && !instance.versionId.trim().isEmpty()) {
            return instance.versionId.trim();
        }
        return "";
    }

    public static final String ASSETS_BASE_URL =
            "https://raw.githubusercontent.com/JoooaoooDVD/cslauncher-assets/main/";
    public static final String LAUNCHER_ICON_URL =
            "https://raw.githubusercontent.com/JoooaoooDVD/cslauncher-assets/2d8c8413ea45b531227b987ceb7a98470dcfc9b6/cs_launcher_logo.png";

    private static final java.util.concurrent.ConcurrentHashMap<String, String> sModpackIconCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Map<String, String> POPULAR_MODPACK_ICONS = new java.util.HashMap<>();
    static {
        POPULAR_MODPACK_ICONS.put("fabulouslyoptimized", "https://cdn.modrinth.com/data/1KVo5zza/icon.png");
        POPULAR_MODPACK_ICONS.put("simplyoptimized", "https://cdn.modrinth.com/data/fALzjamp/icon.png");
        POPULAR_MODPACK_ICONS.put("allthemods9", "https://media.forgecdn.net/avatars/833/214/638219198305080816.png");
        POPULAR_MODPACK_ICONS.put("atm9", "https://media.forgecdn.net/avatars/833/214/638219198305080816.png");
        POPULAR_MODPACK_ICONS.put("allthemods", "https://media.forgecdn.net/avatars/833/214/638219198305080816.png");
        POPULAR_MODPACK_ICONS.put("allthemods8", "https://media.forgecdn.net/avatars/615/487/637996612702758252.png");
        POPULAR_MODPACK_ICONS.put("rlcraft", "https://media.forgecdn.net/avatars/223/866/637042578508499252.png");
        POPULAR_MODPACK_ICONS.put("bettermc", "https://media.forgecdn.net/avatars/506/200/637812543922119958.png");
        POPULAR_MODPACK_ICONS.put("betterminecraft", "https://media.forgecdn.net/avatars/506/200/637812543922119958.png");
        POPULAR_MODPACK_ICONS.put("medievalmc", "https://media.forgecdn.net/avatars/529/499/637841571216503043.png");
        POPULAR_MODPACK_ICONS.put("dawncraft", "https://media.forgecdn.net/avatars/714/235/638018780521360634.png");
        POPULAR_MODPACK_ICONS.put("pixelmon", "https://media.forgecdn.net/avatars/338/545/637476313988636775.png");
        POPULAR_MODPACK_ICONS.put("thepixelmonmodpack", "https://media.forgecdn.net/avatars/338/545/637476313988636775.png");
        POPULAR_MODPACK_ICONS.put("vaulthunters", "https://media.forgecdn.net/avatars/461/640/637739505481711204.png");
        POPULAR_MODPACK_ICONS.put("skyfactory4", "https://media.forgecdn.net/avatars/198/412/636906236940026615.png");
        POPULAR_MODPACK_ICONS.put("skyfactory", "https://media.forgecdn.net/avatars/198/412/636906236940026615.png");
        POPULAR_MODPACK_ICONS.put("stoneblock3", "https://media.forgecdn.net/avatars/589/659/637920786520379234.png");
        POPULAR_MODPACK_ICONS.put("stoneblock", "https://media.forgecdn.net/avatars/589/659/637920786520379234.png");
        POPULAR_MODPACK_ICONS.put("createaboveandbeyond", "https://media.forgecdn.net/avatars/452/782/637719602568600885.png");
        POPULAR_MODPACK_ICONS.put("prominence2", "https://cdn.modrinth.com/data/k5n2y480/icon.png");
        POPULAR_MODPACK_ICONS.put("prominence", "https://cdn.modrinth.com/data/k5n2y480/icon.png");
        POPULAR_MODPACK_ICONS.put("enigmatica", "https://media.forgecdn.net/avatars/241/817/637123985929606277.png");
        POPULAR_MODPACK_ICONS.put("sevtechages", "https://media.forgecdn.net/avatars/147/439/636574744318712151.png");
        POPULAR_MODPACK_ICONS.put("sevtech", "https://media.forgecdn.net/avatars/147/439/636574744318712151.png");
        POPULAR_MODPACK_ICONS.put("cobblemon", "https://cdn.modrinth.com/data/c6ubu7Du/icon.png");
    }

    @NonNull
    public String resolveProfileOrModpackName(@Nullable Instance instance) {
        if (instance != null && instance.name != null && !instance.name.trim().isEmpty()) {
            return instance.name.trim();
        }
        return "Vanilla";
    }

    @NonNull
    public static String detectLoader(@Nullable String versionId) {
        if (versionId == null) return "Vanilla";
        String vLower = versionId.toLowerCase();
        if (vLower.contains("forge") && !vLower.contains("neoforge")) return "Forge";
        if (vLower.contains("neoforge")) return "NeoForge";
        if (vLower.contains("fabric")) return "Fabric";
        if (vLower.contains("quilt")) return "Quilt";
        if (vLower.contains("optifine")) return "OptiFine";
        return "Vanilla";
    }

    @NonNull
    public String getProfileIconUrl(@Nullable Instance instance) {
        if (instance != null) {
            if (instance.iconUrl != null && instance.iconUrl.startsWith("https://")) {
                return instance.iconUrl;
            }
            String loader = detectLoader(instance.versionId).toLowerCase(java.util.Locale.ROOT);
            if (java.util.Arrays.asList("fabric", "forge", "neoforge", "quilt", "optifine").contains(loader)) {
                return ASSETS_BASE_URL + loader + ".png";
            }
        }
        return ASSETS_BASE_URL + "grass_block.png";
    }

    private static int countMods(@Nullable File gameDir) {
        if (gameDir == null) return 0;
        File modsDir = new File(gameDir, "mods");
        if (modsDir.exists() && modsDir.isDirectory()) {
            File[] jars = modsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
            if (jars != null) return jars.length;
        }
        return 0;
    }

    private static String bounded(String text) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        return count <= 128 ? text : text.substring(0, text.offsetByCodePoints(0, 127)) + "…";
    }

    public void refreshPresence() {
        mPresenceExecutor.execute(() -> {
            synchronized (DiscordPresenceManager.this) {
                if (!isRpcEnabled()) return;
                try {
                    if (!DiscordSdkBridge.isInitialized() && !DiscordSdkBridge.initialize(mAppContext, Long.parseLong(APPLICATION_ID))) return;
                    Instance instance = getActiveInstance();
                    String profile = resolveProfileOrModpackName(instance);
                    String version = resolveMinecraftVersion(instance);
                    String loader = detectLoader(instance == null ? mCurrentVersion : instance.versionId);
                    boolean home = mCurrentState == PresenceState.IDLE_LAUNCHER;
                    boolean launching = mCurrentState == PresenceState.LAUNCHING_MINECRAFT;

                    if (home && isOnlyInGameEnabled()) {
                        DiscordSdkBridge.clearRichPresence();
                        return;
                    }

                    String details = home ? "Home Screen · Android launcher"
                            : launching ? "Launching Minecraft on Android" : "Playing Minecraft on Android";
                    String state = "Profile: " + profile;
                    if (!home && isShowVersionEnabled() && !version.isEmpty()) state += " · " + version;
                    if (!home && !"Vanilla".equals(loader)) state += " · " + loader;
                    int mods = instance == null ? 0 : countMods(instance.getGameDirectory());
                    if (!home && isShowModsEnabled() && mods > 0) state += " · " + mods + " mods";
                    if (!home && isShowServerEnabled() && !mCurrentServer.isEmpty()) state += " · " + mCurrentServer;

                    DiscordSdkBridge.updateRichPresence(APP_NAME, bounded(details), bounded(state),
                            LAUNCHER_ICON_URL, "CS Launcher Plus · Minecraft Java on Android",
                            getProfileIconUrl(instance), bounded(profile + " · " + loader),
                            !home && isShowElapsedEnabled() ? mSessionStartMs : 0);
                } catch (Throwable error) {
                    Log.w(TAG, "Presence update failed", error);
                }
            }
        });
    }
}
