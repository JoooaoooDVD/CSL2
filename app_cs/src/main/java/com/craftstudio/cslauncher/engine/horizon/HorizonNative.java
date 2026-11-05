package com.craftstudio.cslauncher.engine.horizon;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

/**
 * JNI surface of {@code libcs_engine.so} (CS Engine, built with the {@code cs-host} feature
 * (fork: JoooaoooDVD/horizon-android, {@code crates/horizon-client/src/android/cs_bridge.rs}).
 * The play path does not go through these methods: the game is started by {@link HorizonGameActivity}
 * (a NativeActivity) with the {@code cs_launch} intent extra.
 */
@Keep
public final class HorizonNative {
    /** CS Engine library (fork {@code crates/cs-brand} LIB; {@code android.app.lib_name} in the manifest). */
    public static final String LIBRARY = "cs_engine";
    private static Boolean sLoaded;

    private HorizonNative() {}

    /** Loads the engine library once; false when this APK/ABI does not contain it. */
    public static synchronized boolean load() {
        if (sLoaded != null) return sLoaded;
        try {
            System.loadLibrary(LIBRARY);
            sLoaded = Boolean.TRUE;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            sLoaded = Boolean.FALSE;
        }
        return sLoaded;
    }

    /** Progress sink of {@link #nativeInstall}; called on the thread that runs the install. */
    @Keep
    public interface InstallListener {
        void onProgress(String stage, long done, long total);
        boolean isCancelled();
    }

    /**
     * Downloads and prepares the Minecraft resources for the engine (official Mojang sources,
     * pinned hashes, resumable). Blocking; run on a worker thread of a foreground service.
     * @return null on success, otherwise the error text
     */
    @Nullable
    public static native String nativeInstall(String home, String cache, String engineData, InstallListener listener);

    /** True when the prepared resources (including sounds) are complete for this engine home. */
    public static native boolean nativeReady(String home);

    /** {@code "<engine version>;<minecraft version>;<build commit>"}. */
    public static native String nativeVersion();

    /** One-line summary of the previous engine session (crash/unclean exit), or null. */
    @Nullable
    public static native String nativeDiagnosticsSummary(String home);

    // --- CS control system -> engine (host input; only valid in the :horizon game process) ---

    /**
     * Skin &amp; Cape hot switch: PNG paths (empty = none) and {@code slim}/{@code classic}. The engine
     * shows them where no account/server texture applies (offline accounts always; Microsoft accounts
     * keep their official skin and only fill a missing cape).
     */
    public static native void nativeHostSkin(String skinPath, String model, String capePath);

    /** Key by Horizon name, e.g. {@code key.keyboard.w}; see {@link HorizonKeyMap}. */
    public static native void nativeHostKey(String token, boolean down);
    /** Relative camera motion in window pixels while the pointer is captured. */
    public static native void nativeHostLook(double dx, double dy);
    /** Absolute pointer position in window pixels (menus, inventory). */
    public static native void nativeHostCursor(double x, double y);
    /** 0 left, 1 right, 2 middle, 3 back, 4 forward. */
    public static native void nativeHostButton(int button, boolean down);
    public static native void nativeHostScroll(float x, float y);
    /** Typed text for chat, signs, text fields. */
    public static native void nativeHostText(String text);
    public static native void nativeHostReleaseAll();
    /** Bit 0: pointer captured (world view); bit 1: engine wants a text keyboard. */
    public static native int nativeHostPoll();
    /** CS voice chat: microphone samples (16 kHz mono PCM16) recorded by the launcher for the engine. */
    public static native void nativeHostMic(short[] data, int count);
    /** CS voice chat: the microphone's state (0 idle, 1 recording, 2 failed). */
    public static native void nativeHostMicState(int state);

    /**
     * The world's public e4mc address ({@code xxxx.e4mc.link}) while the engine publishes it,
     * else {@code null}. Play with Friends invites carry it (engine build 11+).
     */
    public static native String nativeHostPublished();

    /**
     * The next notice the engine wants shown as a system notification (CS Friends: an invite, a
     * chat message, a friend request) as {@code title\ntext}, else {@code null}; one per call,
     * oldest first (engine build 14+).
     */
    public static native String nativeHostNotice();
    /** What the player is doing (`scene\tworld\tserver\tplayers`): menu, singleplayer, hosting, multiplayer. */
    public static native String nativeHostStatus();

    /**
     * A touch began at (x, y) window pixels while the world has the pointer: if it lies on the
     * hotbar drawn last frame, the engine selects that slot and answers 0..8; -1 otherwise
     * (engine build 13+).
     */
    public static native int nativeHostHotbarTap(double x, double y);
}
