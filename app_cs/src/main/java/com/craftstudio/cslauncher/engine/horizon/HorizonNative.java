package com.craftstudio.cslauncher.engine.horizon;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

/**
 * JNI surface of {@code libhorizon_android.so} built with the {@code cs-host} feature
 * (fork: JoooaoooDVD/horizon-android, {@code crates/horizon-client/src/android/cs_bridge.rs}).
 * The play path does not go through these methods: the game is started by {@link HorizonGameActivity}
 * (a NativeActivity) with the {@code cs_launch} intent extra.
 */
@Keep
public final class HorizonNative {
    public static final String LIBRARY = "horizon_android";
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
}
