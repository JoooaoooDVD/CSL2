package com.craftstudio.cslauncher.discord;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.fragments.InstanceVersionResolver;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single source of truth for the Discord Rich Presence of CS Launcher Plus.
 * <p>
 * Transport: the official Discord Social SDK talks to the installed Discord Android app over its
 * local RPC service, so the presence works without linking an account. Linking (OAuth) is optional
 * and only personalises the Discord card in the launcher.
 * <p>
 * Two processes exist: the launcher (home screen, launching) and {@code :game}. Only one of them
 * holds the RPC connection at a time: the launcher releases it when it leaves the foreground and
 * the game process takes over a moment later; when the game exits, its process dies (the Discord
 * app drops that activity) and the launcher publishes the home screen again on resume.
 */
public final class DiscordPresenceManager {

    private static final String TAG = "DiscordPresence";

    public static final String APPLICATION_ID = "1563136590275153960";
    public static final String APP_NAME = "CS Launcher Plus";
    /** Settings key of the single on/off switch (Settings → Miscellaneous → Discord) */
    public static final String PREF_ENABLED = "discordRpc";

    public static final String ASSETS_BASE_URL = "https://raw.githubusercontent.com/JoooaoooDVD/cslauncher-assets/main/";
    public static final String LAUNCHER_ICON_URL = ASSETS_BASE_URL + "cs_launcher_logo.png";
    public static final String DISCORD_INVITE_URL = "https://discord.gg/dmteYqZZyY";
    public static final String YOUTUBE_URL = "https://youtube.com/@craft-studio-official";
    public static final String BUTTON_DISCORD = "Join our Discord";
    public static final String BUTTON_YOUTUBE = "YouTube channel";

    /** How long the game process waits before claiming the RPC connection from the launcher */
    private static final long GAME_TAKEOVER_DELAY_MS = 1500L;

    public enum Scene {
        /** Launcher in the foreground, nothing running */
        HOME,
        /** PLAY pressed: downloads / JVM start in progress */
        LAUNCHING,
        /** Game process alive, Minecraft still loading */
        LOADING,
        /** Title screen / menus */
        MENU,
        SINGLEPLAYER,
        /** Singleplayer world opened to LAN (incl. Virtual LAN) */
        LAN_HOST,
        MULTIPLAYER
    }

    /** What the Discord app currently does with our updates (for the status strip in the UI) */
    public enum Delivery {
        /** Presence switched off in the settings */
        DISABLED,
        /** No Discord app on this device, nothing can be shown */
        NO_DISCORD_APP,
        /** SDK up, waiting for the first acknowledgement */
        CONNECTING,
        /** Discord accepted the latest update */
        LIVE,
        /** Discord rejected the update (not signed in, activity status hidden, …) */
        ERROR
    }

    /** Immutable description of what is (or would be) shown on Discord right now */
    public static final class Snapshot {
        @NonNull public final Scene scene;
        @Nullable public final Instance instance;
        @NonNull public final String profileName;
        @NonNull public final String version;
        /** "" (vanilla), fabric, forge, neoforge, quilt, optifine */
        @NonNull public final String loader;
        public final int modCount;
        @NonNull public final String worldName;
        @NonNull public final String serverHost;
        /** Name the player saved for the server (falls back to the host) */
        @NonNull public final String serverName;
        public final long startMs;
        @NonNull public final String details;
        @NonNull public final String state;
        @NonNull public final String largeImageUrl;
        @NonNull public final String largeText;
        @Nullable public final String smallImageUrl;
        @Nullable public final String smallText;
        public final int statusDisplayType;

        Snapshot(@NonNull Scene scene, @Nullable Instance instance, @NonNull String profileName, @NonNull String version,
                 @NonNull String loader, int modCount, @NonNull String worldName, @NonNull String serverHost,
                 @NonNull String serverName, long startMs,
                 @NonNull String details, @NonNull String state, @NonNull String largeImageUrl, @NonNull String largeText,
                 @Nullable String smallImageUrl, @Nullable String smallText, int statusDisplayType) {
            this.scene = scene;
            this.instance = instance;
            this.profileName = profileName;
            this.version = version;
            this.loader = loader;
            this.modCount = modCount;
            this.worldName = worldName;
            this.serverHost = serverHost;
            this.serverName = serverName;
            this.startMs = startMs;
            this.details = details;
            this.state = state;
            this.largeImageUrl = largeImageUrl;
            this.largeText = largeText;
            this.smallImageUrl = smallImageUrl;
            this.smallText = smallText;
            this.statusDisplayType = statusDisplayType;
        }

        public boolean isInGame() {
            return scene != Scene.HOME && scene != Scene.LAUNCHING;
        }
    }

    public interface Listener {
        /** Main thread. Fired after every recomposition of the presence. */
        void onPresenceChanged(@NonNull Snapshot snapshot);
    }

    private static volatile DiscordPresenceManager sInstance;

    private final Context mAppContext;
    /** The Social SDK needs a host Activity (it derives its application context from it) */
    @Nullable private volatile java.lang.ref.WeakReference<android.app.Activity> mHostActivity;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final DiscordProfileIconPublisher mIconPublisher;
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CSL-Presence");
        thread.setDaemon(true);
        return thread;
    });

    // ---- mutable session state (guarded by this) ----
    private Scene mScene = Scene.HOME;
    @Nullable private Instance mSessionInstance;
    @NonNull private String mSessionVersion = "";
    @NonNull private String mWorldName = "";
    @NonNull private String mServerHost = "";
    private int mServerPort = 0;
    /** Saved-server entry (name + icon) for the current multiplayer session, resolved lazily */
    @Nullable private MinecraftServerList.Entry mServerEntry;
    @Nullable private String mServerEntryKey;
    @Nullable private String mServerIconUrl;
    @Nullable private String mServerIconAttemptedKey;
    private long mSessionStartMs = 0L;
    private final long mLauncherOpenedMs = System.currentTimeMillis();
    /** Public URL of the current session's profile icon once published (null = not yet) */
    @Nullable private String mProfileIconUrl;
    @Nullable private String mProfileIconKey;
    /** Profile key whose icon upload was already attempted this session (success or not) */
    @Nullable private String mProfileIconAttemptedKey;
    private boolean mReleased;
    private boolean mGameLogWatcherInstalled;
    @NonNull private volatile Snapshot mSnapshot;
    private int mRevision;

    @NonNull
    public static DiscordPresenceManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (DiscordPresenceManager.class) {
                if (sInstance == null) sInstance = new DiscordPresenceManager(context.getApplicationContext());
            }
        }
        return sInstance;
    }

    private DiscordPresenceManager(@NonNull Context context) {
        mAppContext = context;
        mIconPublisher = new DiscordProfileIconPublisher(context);
        mSnapshot = compose();
    }

    // ------------------------------------------------------------------ settings

    private SharedPreferences preferences() {
        if (LauncherPreferences.DEFAULT_PREF != null) return LauncherPreferences.DEFAULT_PREF;
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(mAppContext);
    }

    public boolean isEnabled() {
        try {
            return preferences().getBoolean(PREF_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public void setEnabled(boolean enabled) {
        try {
            preferences().edit().putBoolean(PREF_ENABLED, enabled).apply();
        } catch (Throwable ignored) {}
        LauncherPreferences.PREF_DISCORD_RPC = enabled;
        if (enabled) {
            synchronized (this) { mReleased = false; }
            refresh();
        } else {
            mExecutor.execute(() -> {
                DiscordSdkBridge.clearRichPresence();
                DiscordSdkBridge.shutdown();
            });
            notifyListeners();
        }
    }

    @NonNull
    public Delivery getDelivery() {
        if (!isEnabled()) return Delivery.DISABLED;
        if (!DiscordSdkBridge.isDiscordAppInstalled(mAppContext)) return Delivery.NO_DISCORD_APP;
        String result = DiscordSdkBridge.getLastPresenceResult();
        if ("ok".equals(result)) return Delivery.LIVE;
        if (result.startsWith("error")) return Delivery.ERROR;
        return Delivery.CONNECTING;
    }

    /** Human readable text of the last SDK error, or "" */
    @NonNull
    public String getDeliveryError() {
        String result = DiscordSdkBridge.getLastPresenceResult();
        return result.startsWith("error:") ? result.substring(6) : "";
    }

    // ------------------------------------------------------------------ listeners

    public void addListener(@NonNull Listener listener) {
        mListeners.addIfAbsent(listener);
        final Snapshot snapshot = mSnapshot;
        mMainHandler.post(() -> listener.onPresenceChanged(snapshot));
    }

    public void removeListener(@NonNull Listener listener) {
        mListeners.remove(listener);
    }

    @NonNull
    public Snapshot getSnapshot() {
        return mSnapshot;
    }

    private void notifyListeners() {
        if (mListeners.isEmpty()) return;
        final Snapshot snapshot = mSnapshot;
        mMainHandler.post(() -> {
            for (Listener listener : mListeners) {
                try { listener.onPresenceChanged(snapshot); } catch (Throwable ignored) {}
            }
        });
    }

    // ------------------------------------------------------------------ host activity

    /** Remember the foreground Activity of this process; the SDK is initialised through it */
    public void attach(@Nullable android.app.Activity activity) {
        if (activity == null) return;
        java.lang.ref.WeakReference<android.app.Activity> current = mHostActivity;
        if (current == null || current.get() != activity) mHostActivity = new java.lang.ref.WeakReference<>(activity);
    }

    @NonNull
    private Context hostContext() {
        java.lang.ref.WeakReference<android.app.Activity> ref = mHostActivity;
        android.app.Activity activity = ref == null ? null : ref.get();
        return activity != null && !activity.isFinishing() ? activity : mAppContext;
    }

    // ------------------------------------------------------------------ launcher process

    /** Launcher visible, nothing running: "On the home screen" */
    public void setLauncherIdle(@Nullable android.app.Activity host) {
        attach(host);
        synchronized (this) {
            mScene = Scene.HOME;
            mSessionInstance = null;
            mSessionVersion = "";
            mWorldName = "";
            mServerHost = "";
            mServerPort = 0;
            mSessionStartMs = 0L;
            mReleased = false;
        }
        refresh();
    }

    /** PLAY pressed in the launcher */
    public void setMinecraftLaunching(@Nullable android.app.Activity host, @Nullable Instance instance, @Nullable String versionId) {
        attach(host);
        synchronized (this) {
            mScene = Scene.LAUNCHING;
            mSessionInstance = instance != null ? instance : loadSelectedInstanceQuietly();
            mSessionVersion = versionId == null ? "" : versionId;
            mWorldName = "";
            mServerHost = "";
            mServerPort = 0;
            mSessionStartMs = System.currentTimeMillis();
            mReleased = false;
        }
        refresh();
    }

    /**
     * The launcher left the foreground (game taking over, or the user switched apps): drop the
     * presence and the RPC connection so the other process / nobody owns it. The home screen is
     * published again by {@link #setLauncherIdle()} on resume.
     */
    public void releaseFromLauncher() {
        synchronized (this) {
            mReleased = true;
        }
        mExecutor.execute(() -> {
            DiscordSdkBridge.clearRichPresence();
            DiscordSdkBridge.shutdown();
        });
    }

    // ------------------------------------------------------------------ game process

    /**
     * Called in the {@code :game} process as soon as the game activity exists. Publishes
     * "Loading …" after a short delay (the launcher process releases the connection first) and
     * then follows the game log through menu, worlds and servers.
     */
    public void onGameProcessStarted(@Nullable android.app.Activity host, @Nullable Instance instance, @Nullable String versionId) {
        attach(host);
        boolean first;
        synchronized (this) {
            first = mScene == Scene.HOME || mScene == Scene.LAUNCHING;
            if (first) {
                mScene = Scene.LOADING;
                mSessionInstance = instance != null ? instance : loadSelectedInstanceQuietly();
                mSessionVersion = versionId == null ? "" : versionId;
                mWorldName = "";
                mServerHost = "";
                mServerPort = 0;
                mSessionStartMs = System.currentTimeMillis();
                mReleased = false;
            }
            if (!mGameLogWatcherInstalled) {
                mGameLogWatcherInstalled = true;
                try {
                    com.craftstudio.cslauncher.multiplayer.LogTap.addSink(this::onGameLogLine);
                } catch (Throwable ignored) {}
            }
        }
        if (first) {
            mSnapshot = compose();
            notifyListeners();
            mMainHandler.postDelayed(this::refresh, GAME_TAKEOVER_DELAY_MS);
        }
    }

    /** The game process is about to die: clear what we published */
    public void onGameExiting() {
        synchronized (this) {
            mReleased = true;
        }
        try {
            DiscordSdkBridge.clearRichPresence();
            DiscordSdkBridge.shutdown();
        } catch (Throwable ignored) {}
    }

    // ---- game log → scene -------------------------------------------------------------

    private static final Pattern LOG_CONNECTING = Pattern.compile("Connecting to ([^,\\s]+), (\\d{1,5})");
    private static final Pattern LOG_PREPARING_LEVEL = Pattern.compile("Preparing level \"(.+?)\"");
    private static final Pattern LOG_LAN = Pattern.compile("(?:Started serving on|Local game hosted on port|Published LAN server on port)\\s+\\d{2,5}");
    private static final Pattern LOG_MENU_READY = Pattern.compile("Sound engine started|OpenAL initialized|Created: \\d+x\\d+(?:x\\d+)? [a-z_:/]*atlas");
    private static final Pattern LOG_LEAVE_WORLD = Pattern.compile("Stopping singleplayer server|Stopping server$|Disconnected from server|Lost connection|Connection reset|Internal Exception|Timed out|Server closed|Disconnected:");
    /**
     * "Stopping worker threads" is logged whenever the chunk renderer is torn down. That happens when the
     * player leaves a world, but ALSO while joining a server (old level disposed), on resource-pack reloads
     * pushed by servers, and on fullscreen/graphics changes. Treat it as "left the world" only when the last
     * world/server switch is comfortably in the past, otherwise multiplayer presence would vanish right
     * after the join finished.
     */
    private static final Pattern LOG_WORKERS_STOPPED = Pattern.compile("Stopping worker threads");
    private static final long WORKERS_STOPPED_GRACE_MS = 90_000L;
    private long mLastWorldSwitchAt = 0L;
    private static final Pattern LOG_INTEGRATED = Pattern.compile("Starting integrated minecraft server version");

    /** One raw line of the game log (any thread) */
    void onGameLogLine(@Nullable String text) {
        if (text == null || text.isEmpty()) return;
        // The native logger may hand over several lines at once
        int from = 0;
        while (from < text.length()) {
            int nl = text.indexOf('\n', from);
            if (nl < 0) nl = text.length();
            if (nl > from) {
                String line = text.substring(from, nl);
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                if (!line.isEmpty()) handleLogLine(line);
            }
            from = nl + 1;
        }
    }

    private void handleLogLine(@NonNull String line) {
        if (line.length() > 600) line = line.substring(0, 600);
        Scene newScene = null;
        String world = null;
        String server = null;
        int port = 0;
        Matcher m;
        if ((m = LOG_CONNECTING.matcher(line)).find()) {
            newScene = Scene.MULTIPLAYER;
            server = m.group(1);
            try { port = Integer.parseInt(m.group(2)); } catch (NumberFormatException ignored) {}
        } else if ((m = LOG_PREPARING_LEVEL.matcher(line)).find()) {
            newScene = Scene.SINGLEPLAYER;
            world = m.group(1);
        } else if (LOG_LAN.matcher(line).find()) {
            newScene = Scene.LAN_HOST;
        } else if (LOG_INTEGRATED.matcher(line).find()) {
            newScene = Scene.SINGLEPLAYER;
        } else if (LOG_LEAVE_WORLD.matcher(line).find()) {
            newScene = Scene.MENU;
        } else if (LOG_WORKERS_STOPPED.matcher(line).find()) {
            synchronized (this) {
                // only a real "left the world" if nothing switched worlds recently (see field doc)
                if (mScene != Scene.MULTIPLAYER && mScene != Scene.SINGLEPLAYER && mScene != Scene.LAN_HOST) return;
                if (SystemClock.elapsedRealtime() - mLastWorldSwitchAt < WORKERS_STOPPED_GRACE_MS) return;
            }
            newScene = Scene.MENU;
        } else if (LOG_MENU_READY.matcher(line).find()) {
            newScene = Scene.MENU;
        } else {
            return;
        }
        synchronized (this) {
            if (mScene == Scene.HOME || mScene == Scene.LAUNCHING) return; // not our process' session
            switch (newScene) {
                case MENU:
                    // "menu ready" markers only lift LOADING; leaving a world always returns to the menu
                    boolean leaving = LOG_LEAVE_WORLD.matcher(line).find() || LOG_WORKERS_STOPPED.matcher(line).find();
                    if (!leaving && mScene != Scene.LOADING) return;
                    if (leaving && (mScene == Scene.LOADING || mScene == Scene.MENU)) return;
                    mScene = Scene.MENU;
                    mWorldName = "";
                    mServerHost = "";
                    mServerPort = 0;
                    break;
                case SINGLEPLAYER:
                    if (world == null && mScene == Scene.SINGLEPLAYER) return;
                    if (world == null && mScene == Scene.LAN_HOST) return;
                    mScene = Scene.SINGLEPLAYER;
                    mLastWorldSwitchAt = SystemClock.elapsedRealtime();
                    if (world != null) mWorldName = world;
                    mServerHost = "";
                    mServerPort = 0;
                    break;
                case LAN_HOST:
                    if (mScene == Scene.LAN_HOST) return;
                    mScene = Scene.LAN_HOST;
                    mLastWorldSwitchAt = SystemClock.elapsedRealtime();
                    mServerHost = "";
                    mServerPort = 0;
                    break;
                case MULTIPLAYER:
                    mScene = Scene.MULTIPLAYER;
                    mLastWorldSwitchAt = SystemClock.elapsedRealtime();
                    mServerHost = server == null ? "" : server;
                    mServerPort = port;
                    mWorldName = "";
                    break;
                default:
                    return;
            }
        }
        refresh();
    }

    // ------------------------------------------------------------------ composition

    @Nullable
    private static Instance loadSelectedInstanceQuietly() {
        try {
            return Instances.loadSelectedInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    @NonNull
    public static String loaderOf(@Nullable Instance instance, @NonNull String versionId) {
        String id = versionId;
        if ((id == null || id.isEmpty()) && instance != null && instance.versionId != null) id = instance.versionId;
        if (id == null) return "";
        String loader = InstanceVersionResolver.loaderFromVersionId(id);
        if (!loader.isEmpty()) return loader;
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("optifine")) return "optifine";
        if (lower.contains("neoforge")) return "neoforge";
        if (lower.contains("forge")) return "forge";
        if (lower.contains("fabric")) return "fabric";
        if (lower.contains("quilt")) return "quilt";
        return "";
    }

    @NonNull
    public static String loaderLabel(@NonNull String loader) {
        switch (loader) {
            case "fabric": return "Fabric";
            case "forge": return "Forge";
            case "neoforge": return "NeoForge";
            case "quilt": return "Quilt";
            case "optifine": return "OptiFine";
            default: return "Vanilla";
        }
    }

    @NonNull
    private static String versionOf(@Nullable Instance instance, @NonNull String versionId) {
        String id = versionId;
        if (id.isEmpty() && instance != null && instance.versionId != null) id = instance.versionId;
        if (id.isEmpty() || Instance.VERSION_LATEST_RELEASE.equals(id) || Instance.VERSION_LATEST_SNAPSHOT.equals(id)) {
            if (instance != null && instance.getKey() != null) {
                try {
                    String mc = InstanceVersionResolver.resolve(instance.getKey()).mcVersion;
                    if (mc != null && !mc.isEmpty()) return mc;
                } catch (Throwable ignored) {}
            }
            return id.isEmpty() ? "" : (Instance.VERSION_LATEST_SNAPSHOT.equals(id) ? "latest snapshot" : "latest release");
        }
        String shortVersion = InstanceVersionResolver.shortVersion(id);
        if (shortVersion != null && !shortVersion.isEmpty()) {
            // OptiFine ids keep "1.20.1-OptiFine_HD_U_I6": show only the Minecraft part
            int dash = shortVersion.indexOf("-OptiFine");
            if (dash > 0) return shortVersion.substring(0, dash);
            return shortVersion;
        }
        return id;
    }

    private static int countMods(@Nullable Instance instance) {
        if (instance == null) return 0;
        try {
            File modsDir = new File(instance.getGameDirectory(), "mods");
            File[] jars = modsDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.endsWith(".jar") || lower.endsWith(".jar.disabled") || lower.endsWith(".jar.old");
            });
            if (jars == null) return 0;
            int enabled = 0;
            for (File jar : jars) if (jar.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) enabled++;
            return enabled;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean looksLikePrivateAddress(@NonNull String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.isEmpty() || h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan")) return true;
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return true;       // raw IPv4
        if (h.contains(":")) return true;                           // raw IPv6
        return h.matches("[0-9a-f.:]+");
    }

    @NonNull
    private static String clamp(@NonNull String text) {
        if (text.length() <= 120) return text;
        return text.substring(0, 119) + "…";
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** Build the snapshot from the current session state (fast, no I/O besides a dir listing) */
    @NonNull
    private Snapshot compose() {
        Scene scene;
        Instance instance;
        String versionId, world, server, iconUrl, iconKey;
        int serverPort;
        long startMs;
        synchronized (this) {
            scene = mScene;
            instance = mSessionInstance != null ? mSessionInstance : null;
            versionId = mSessionVersion;
            world = mWorldName;
            server = mServerHost;
            serverPort = mServerPort;
            startMs = mSessionStartMs;
            iconUrl = mProfileIconUrl;
            iconKey = mProfileIconKey;
        }
        if (instance == null) instance = loadSelectedInstanceQuietly();
        if (iconKey != null && (instance == null || !iconKey.equals(instance.getKey()))) iconUrl = null;

        String profile = instance != null && instance.name != null && !instance.name.trim().isEmpty() ? instance.name.trim() : "Minecraft";
        String version = versionOf(instance, versionId);
        String loader = loaderOf(instance, versionId);
        int mods = loader.isEmpty() || "optifine".equals(loader) ? 0 : countMods(instance);

        String loaderIcon = loader.isEmpty() ? ASSETS_BASE_URL + "grass_block.png" : ASSETS_BASE_URL + loader + ".png";
        String profileIcon = iconUrl != null ? iconUrl
                : (instance != null && instance.iconUrl != null && instance.iconUrl.startsWith("https://") ? instance.iconUrl : loaderIcon);

        StringBuilder stack = new StringBuilder();
        if (!version.isEmpty()) stack.append("Minecraft ").append(version);
        if (!loader.isEmpty()) stack.append(stack.length() > 0 ? " · " : "").append(loaderLabel(loader));
        if (mods > 0) stack.append(stack.length() > 0 ? " · " : "").append(plural(mods, "mod", "mods"));
        String stackText = stack.length() == 0 ? "Minecraft" : stack.toString();
        // Short form for the second line of the card: "1.21.4 · Fabric · 42 mods"
        StringBuilder shortStackBuilder = new StringBuilder();
        if (!version.isEmpty()) shortStackBuilder.append(version);
        if (!loader.isEmpty()) shortStackBuilder.append(shortStackBuilder.length() > 0 ? " · " : "").append(loaderLabel(loader));
        if (mods > 0) shortStackBuilder.append(shortStackBuilder.length() > 0 ? " · " : "").append(plural(mods, "mod", "mods"));
        String shortStack = shortStackBuilder.length() == 0 ? "Minecraft" : fit(shortStackBuilder.toString(), 32);

        // Multiplayer: the saved server name + icon (servers.dat) when the player saved it
        MinecraftServerList.Entry serverEntry = null;
        String serverIcon = null;
        if (scene == Scene.MULTIPLAYER && !server.isEmpty()) {
            serverEntry = resolveServerEntry(instance, server, serverPort);
            synchronized (this) {
                serverIcon = mServerIconUrl;
            }
        }
        String serverName = serverEntry != null && !serverEntry.name.isEmpty() ? serverEntry.name
                : (looksLikePrivateAddress(server) ? "Private server" : server);

        String details, state, largeImage, largeText, smallImage, smallText;
        int display;
        switch (scene) {
            case HOME: {
                details = "Home screen";
                state = instance != null ? "Profile: " + fit(profile, 26) : "Picking a profile";
                largeImage = LAUNCHER_ICON_URL;
                largeText = APP_NAME + " · Minecraft Java on Android";
                smallImage = null;
                smallText = null;
                startMs = mLauncherOpenedMs;
                display = DiscordSdkBridge.DISPLAY_NAME;
                break;
            }
            case LAUNCHING: {
                details = fit("Launching " + profile, 32);
                state = shortStack;
                largeImage = profileIcon;
                largeText = profile + " · " + stackText;
                smallImage = LAUNCHER_ICON_URL;
                smallText = APP_NAME;
                display = DiscordSdkBridge.DISPLAY_DETAILS;
                break;
            }
            case LOADING: {
                details = fit(profile, 32);
                state = "Loading…";
                largeImage = profileIcon;
                largeText = profile + " · " + stackText;
                smallImage = LAUNCHER_ICON_URL;
                smallText = APP_NAME;
                display = DiscordSdkBridge.DISPLAY_DETAILS;
                break;
            }
            case MENU: {
                details = fit(profile, 32);
                state = "Main menu";
                largeImage = profileIcon;
                largeText = profile + " · " + stackText;
                smallImage = LAUNCHER_ICON_URL;
                smallText = APP_NAME;
                display = DiscordSdkBridge.DISPLAY_DETAILS;
                break;
            }
            case SINGLEPLAYER: {
                details = fit(profile, 32);
                state = world.isEmpty() ? "Singleplayer" : fit("World: " + world, 32);
                largeImage = profileIcon;
                largeText = profile + " · " + stackText;
                smallImage = loaderIcon;
                smallText = loaderLabel(loader) + (version.isEmpty() ? "" : " " + version);
                display = DiscordSdkBridge.DISPLAY_DETAILS;
                break;
            }
            case LAN_HOST: {
                details = fit(profile, 32);
                state = world.isEmpty() ? "Hosting a LAN world" : fit("LAN world: " + world, 32);
                largeImage = profileIcon;
                largeText = profile + " · " + stackText;
                smallImage = loaderIcon;
                smallText = loaderLabel(loader) + (version.isEmpty() ? "" : " " + version);
                display = DiscordSdkBridge.DISPLAY_DETAILS;
                break;
            }
            case MULTIPLAYER:
            default: {
                details = fit(serverName, 32);
                state = fit("Multiplayer · " + profile, 32);
                largeImage = serverIcon != null ? serverIcon : profileIcon;
                largeText = serverIcon != null
                        ? serverName + (looksLikePrivateAddress(server) ? "" : " · " + server)
                        : profile + " · " + stackText;
                smallImage = serverIcon != null ? profileIcon : loaderIcon;
                smallText = serverIcon != null ? profile + " · " + stackText
                        : loaderLabel(loader) + (version.isEmpty() ? "" : " " + version);
                display = DiscordSdkBridge.DISPLAY_DETAILS;
                break;
            }
        }
        return new Snapshot(scene, instance, profile, version, loader, mods, world, server, serverName, startMs,
                clamp(details), clamp(state), largeImage, clamp(largeText), smallImage,
                smallText == null ? null : clamp(smallText), display);
    }

    /** Cut a line so it fits the Discord mobile card without wrapping */
    @NonNull
    private static String fit(@NonNull String text, int max) {
        text = text.trim();
        if (text.length() <= max) return text;
        return text.substring(0, max - 1).trim() + "…";
    }

    /** servers.dat lookup, cached per host:port for the session */
    @Nullable
    private MinecraftServerList.Entry resolveServerEntry(@Nullable Instance instance, @NonNull String host, int port) {
        String key = host.toLowerCase(Locale.ROOT) + ":" + port;
        synchronized (this) {
            if (key.equals(mServerEntryKey)) return mServerEntry;
        }
        MinecraftServerList.Entry entry = null;
        try {
            entry = MinecraftServerList.find(instance == null ? null : instance.getGameDirectory(), host, port);
        } catch (Throwable ignored) {}
        synchronized (this) {
            mServerEntryKey = key;
            mServerEntry = entry;
            if (!key.equals(mServerIconAttemptedKey)) mServerIconUrl = null;
        }
        return entry;
    }

    // ------------------------------------------------------------------ publishing

    /** Recompose, tell the UI, and push to Discord (unless released / disabled) */
    public void refresh() {
        final int revision;
        synchronized (this) {
            revision = ++mRevision;
        }
        mExecutor.execute(() -> {
            Snapshot snapshot = compose();
            mSnapshot = snapshot;
            notifyListeners();
            publish(snapshot, revision);
            publishProfileIconIfNeeded(snapshot, revision);
            publishServerIconIfNeeded(snapshot, revision);
        });
    }

    private void publish(@NonNull Snapshot snapshot, int revision) {
        boolean released;
        synchronized (this) {
            released = mReleased || revision != mRevision;
        }
        if (released || !isEnabled()) return;
        if (!DiscordSdkBridge.isDiscordAppInstalled(mAppContext)) return;
        try {
            if (!DiscordSdkBridge.isInitialized() && !DiscordSdkBridge.initialize(hostContext(), Long.parseLong(APPLICATION_ID))) {
                Log.i(TAG, "Discord SDK not ready yet, presence deferred");
                return;
            }
            DiscordSdkBridge.updateRichPresence(APP_NAME, snapshot.details, snapshot.state,
                    snapshot.largeImageUrl, snapshot.largeText, snapshot.smallImageUrl, snapshot.smallText,
                    snapshot.startMs, snapshot.statusDisplayType,
                    snapshot.scene == Scene.HOME ? YOUTUBE_URL : null, null,
                    BUTTON_DISCORD, DISCORD_INVITE_URL, BUTTON_YOUTUBE, YOUTUBE_URL);
            // Let the UI pick up the acknowledgement a moment later
            mMainHandler.postDelayed(this::notifyListeners, 1200L);
        } catch (Throwable error) {
            Log.w(TAG, "Presence update failed", error);
        }
    }

    /** First time a profile is shown: publish its icon, then refresh so the real art appears */
    private void publishProfileIconIfNeeded(@NonNull Snapshot snapshot, int revision) {
        if (snapshot.scene == Scene.HOME || snapshot.instance == null) return;
        String key = snapshot.instance.getKey();
        synchronized (this) {
            if (key == null || key.equals(mProfileIconAttemptedKey) || revision != mRevision) return;
            mProfileIconAttemptedKey = key;
        }
        String url = mIconPublisher.publish(snapshot.instance);
        synchronized (this) {
            mProfileIconUrl = url;
            mProfileIconKey = url == null ? null : key;
        }
        if (url != null) refresh();
    }

    /** Multiplayer: publish the saved server's icon once, then refresh so it replaces the profile art */
    private void publishServerIconIfNeeded(@NonNull Snapshot snapshot, int revision) {
        if (snapshot.scene != Scene.MULTIPLAYER || snapshot.serverHost.isEmpty()) return;
        MinecraftServerList.Entry entry;
        String key;
        synchronized (this) {
            entry = mServerEntry;
            key = mServerEntryKey;
            if (entry == null || entry.iconPng == null || key == null || key.equals(mServerIconAttemptedKey) || revision != mRevision) return;
            mServerIconAttemptedKey = key;
        }
        String url = mIconPublisher.publishPng(DiscordProfileIconPublisher.upscalePixelArt(entry.iconPng));
        synchronized (this) {
            mServerIconUrl = url;
        }
        if (url != null) refresh();
    }

    /**
     * The settings/bottom sheet calls this so the preview mirrors Discord even on the home screen:
     * the selected profile is read fresh each time.
     */
    public void refreshPreview() {
        refresh();
    }
}
