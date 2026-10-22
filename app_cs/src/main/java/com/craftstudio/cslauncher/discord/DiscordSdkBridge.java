package com.craftstudio.cslauncher.discord;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * JNI Native Bridge to the official Discord Social SDK Client methods.
 */
public final class DiscordSdkBridge {

    private static final String TAG = "DiscordSdkBridge";
    private static volatile boolean sLibraryLoaded = false;
    private static volatile boolean sSdkInitialized = false;

    public interface NativeAuthCallback {
        void onSuccess(String code, String redirectUri, String verifier);
        void onError(String error);
    }

    public interface TokenCallback {
        void onSuccess(String accessToken, String refreshToken);
        void onError(String error);
    }

    private static java.util.concurrent.ScheduledExecutorService sCallbackTicker;

    static {
        try {
            System.loadLibrary("discord_partner_sdk");
            System.loadLibrary("discord_bridge");
            sLibraryLoaded = true;
            Log.i(TAG, "Discord Social SDK native libraries loaded successfully");
        } catch (Throwable t) {
            Log.w(TAG, "Could not load Discord Social SDK native libraries: " + t.getMessage());
            sLibraryLoaded = false;
        }
    }

    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }

    public static boolean isInitialized() {
        return sSdkInitialized;
    }

    public static synchronized boolean initialize(@NonNull Context context, long applicationId) {
        if (!sLibraryLoaded) {
            Log.w(TAG, "Native library not loaded; skipping Discord SDK init");
            return false;
        }
        if (sSdkInitialized) {
            return true;
        }

        try {
            // Discord Social SDK requires an engine Activity or application Context set
            // so DiscordRpcClient can resolve intents without NullPointerException
            android.app.Activity engineAct = null;
            try {
                engineAct = com.discord.socialsdk.DiscordSocialSdkInit.getEngineActivity();
            } catch (Throwable ignored) {}

            if (engineAct == null && context instanceof android.app.Activity) {
                try {
                    com.discord.socialsdk.DiscordSocialSdkInit.setEngineActivity((android.app.Activity) context);
                    engineAct = (android.app.Activity) context;
                } catch (Throwable ignored) {}
            }

            if (engineAct == null) {
                Log.w(TAG, "DiscordSocialSdkInit has no engine Activity yet; deferring init");
                return false;
            }

            boolean ok = nativeInit(applicationId);
            sSdkInitialized = ok;
            if (ok) {
                Log.i(TAG, "Official Discord Social SDK client initialized for ID: " + applicationId);
                startCallbackTicker();
            }
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "Error initializing Discord Social SDK native client", t);
            return false;
        }
    }

    private static synchronized void startCallbackTicker() {
        if (sCallbackTicker != null && !sCallbackTicker.isShutdown()) return;
        sCallbackTicker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DiscordSdk-Callbacks");
            t.setDaemon(true);
            return t;
        });
        sCallbackTicker.scheduleWithFixedDelay(DiscordSdkBridge::runCallbacks, 50, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public static void runCallbacks() {
        if (!sSdkInitialized) return;
        try {
            nativeRunCallbacks();
        } catch (Throwable ignored) {}
    }

    /**
     * Clear the presence and drop the native client so another process (launcher / game) can own
     * the single RPC connection to the Discord app. {@link #initialize} may be called again later.
     */
    public static synchronized void shutdown() {
        if (sCallbackTicker != null) {
            sCallbackTicker.shutdownNow();
            try { sCallbackTicker.awaitTermination(400, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            sCallbackTicker = null;
        }
        if (sSdkInitialized) {
            try {
                nativeShutdown();
            } catch (Throwable ignored) {}
            sSdkInitialized = false;
        }
    }

    public interface ConnectedUserCallback {
        void onSuccess(String userId, String username, String displayName, String avatarUrl);
        void onError(String error);
    }

    /** Friends-list text after "Playing": the application name */
    public static final int DISPLAY_NAME = 0;
    /** Friends-list text after "Playing": the state line */
    public static final int DISPLAY_STATE = 1;
    /** Friends-list text after "Playing": the details line */
    public static final int DISPLAY_DETAILS = 2;

    /**
     * Push a full activity to the Discord app. Images are public https URLs (Discord proxies
     * them), buttons are optional (both label and url needed), {@code detailsUrl} makes the
     * details line clickable and {@code largeUrl} makes the big image clickable.
     */
    public static boolean updateRichPresence(
            @Nullable String name,
            @Nullable String details,
            @Nullable String state,
            @Nullable String largeImage,
            @Nullable String largeText,
            @Nullable String smallImage,
            @Nullable String smallText,
            long startTimestampMs,
            int statusDisplayType,
            @Nullable String largeUrl,
            @Nullable String detailsUrl,
            @Nullable String button1Label, @Nullable String button1Url,
            @Nullable String button2Label, @Nullable String button2Url) {
        if (!sSdkInitialized) return false;
        try {
            return nativeUpdateRichPresence(name, details, state, largeImage, largeText, smallImage, smallText,
                    startTimestampMs, statusDisplayType, largeUrl, detailsUrl,
                    button1Label, button1Url, button2Label, button2Url);
        } catch (Throwable t) {
            Log.w(TAG, "Error in nativeUpdateRichPresence", t);
            return false;
        }
    }

    /**
     * Outcome of the latest presence round trip: {@code ""} while nothing was acknowledged yet,
     * {@code "ok"} once the Discord app accepted an update, {@code "error:<text>"} otherwise.
     */
    @NonNull
    public static String getLastPresenceResult() {
        if (!sLibraryLoaded || !sSdkInitialized) return "";
        try {
            String result = nativeGetLastPresenceResult();
            return result == null ? "" : result;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Whether the Discord Android app (the RPC transport) is installed on this device */
    public static boolean isDiscordAppInstalled(@NonNull Context context) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        for (String pkg : new String[]{"com.discord", "com.discord.canary", "com.discord.ptb"}) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static void clearRichPresence() {
        if (!sSdkInitialized) return;
        try {
            nativeClearRichPresence();
        } catch (Throwable ignored) {}
    }

    public static void authorize(long clientId, @Nullable String scopes, @NonNull NativeAuthCallback callback) {
        if (!sSdkInitialized) {
            callback.onError("Discord Social SDK not initialized");
            return;
        }
        try {
            nativeAuthorize(clientId, scopes, callback);
        } catch (Throwable t) {
            callback.onError(t.getMessage());
        }
    }

    public static void getToken(long applicationId, @NonNull String code, @NonNull String verifier, @NonNull String redirectUri, @NonNull TokenCallback callback) {
        if (!sSdkInitialized) {
            callback.onError("Discord Social SDK not initialized");
            return;
        }
        try {
            nativeGetToken(applicationId, code, verifier, redirectUri, callback);
        } catch (Throwable t) {
            callback.onError(t.getMessage());
        }
    }

    public static void fetchConnectedUser(long applicationId, @NonNull ConnectedUserCallback callback) {
        if (!sSdkInitialized) {
            callback.onError("Discord Social SDK not initialized");
            return;
        }
        try {
            nativeFetchConnectedUser(applicationId, callback);
        } catch (Throwable t) {
            callback.onError(t.getMessage());
        }
    }

    // Native JNI declarations
    private static native boolean nativeInit(long applicationId);
    private static native void nativeShutdown();
    private static native void nativeRunCallbacks();
    private static native boolean nativeUpdateRichPresence(
            String name,
            String details,
            String state,
            String largeImage,
            String largeText,
            String smallImage,
            String smallText,
            long startTimestampMs,
            int statusDisplayType,
            String largeUrl,
            String detailsUrl,
            String button1Label, String button1Url,
            String button2Label, String button2Url);
    private static native String nativeGetLastPresenceResult();
    private static native boolean nativeIsInitialized();
    private static native void nativeClearRichPresence();
    private static native void nativeAuthorize(long clientId, String scopes, NativeAuthCallback callback);
    private static native void nativeGetToken(long applicationId, String code, String verifier, String redirectUri, TokenCallback callback);
    private static native void nativeFetchConnectedUser(long applicationId, ConnectedUserCallback callback);
}
