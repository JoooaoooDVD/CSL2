package com.craftstudio.cslauncher.e4mc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * JNI binding of {@code libcsrelay.so} (app_cs/src/main/jni/csrelay): the Craft Studio relay
 * tunnel ({@code play.craftstudios.site:25210}) as an alternative to e4mc. Pure C on purpose —
 * busy Java threads doing socket I/O inside the game process (next to HotSpot) killed the
 * process without a trace; native threads never touch the Android runtime, and Java only polls
 * state from the main thread. The door policy (player limit, allowed / blocked names, Kick) and
 * the custom MOTD are enforced in the native client: a guest is named by their login packet
 * before a byte reaches the game.
 */
public final class CsRelayNative {
    public static final String DEFAULT_HOST = "play.craftstudios.site";
    public static final int DEFAULT_PORT = 25210;

    public static final int STATE_CONNECTING = 0, STATE_UP = 1, STATE_FAILED = 2, STATE_STOPPED = 3;

    private static volatile boolean sLoaded;
    @Nullable private static volatile String sLoadError;

    private CsRelayNative() {}

    public static boolean load() {
        if (sLoaded) return true;
        synchronized (CsRelayNative.class) {
            if (sLoaded) return true;
            try {
                System.loadLibrary("csrelay");
                sLoaded = true;
            } catch (UnsatisfiedLinkError | SecurityException e) {
                sLoadError = e.getMessage();
            }
            return sLoaded;
        }
    }

    @Nullable public static String loadError() { return sLoadError; }

    @NonNull public static String relayAddress() { return DEFAULT_HOST + ":" + DEFAULT_PORT; }

    /** Connects in the background; 0 only when the thread could not be created. */
    public static native long nativeStart(@Nullable String host, int port, int localPort, @Nullable String playerName);
    /** One of the STATE_* values. */
    public static native int nativeState(long handle);
    /** Exact join address from the relay's OK line ({@code rohit.play.craftstudios.site:25210}) while UP, else null. */
    @Nullable public static native String nativeJoinAddress(long handle);
    /** Why the tunnel failed, or null. */
    @Nullable public static native String nativeProblem(long handle);
    /** Guest streams currently open. */
    public static native int nativeStreams(long handle);
    /** Closes the relay socket, every guest stream and frees the handle. Blocks briefly (thread joins). */
    public static native void nativeStop(long handle);
    /**
     * Who the door lets in from now on: {@code maxGuests} guests at once (0 = no limit) and, when
     * {@code allowedNamesOrNull} is set (newline separated), only those names — others read {@code reason}.
     */
    public static native void nativeSetPolicy(long handle, int maxGuests, @Nullable String allowedNamesOrNull, @Nullable String reason);
    /** Names the host removed (newline separated, null = none): refused however open the world is. */
    public static native void nativeSetBlocked(long handle, @Nullable String blockedOrNull);
    /** Custom MOTD spliced into status pings (empty/null = the game's own line). */
    public static native void nativeSetMotd(long handle, @Nullable String motd);
    /** Guests logged in right now, one name per line; null when none. */
    @Nullable public static native String nativeGuestNames(long handle);
    /** Ends the streams of the guest logged in as {@code name}; how many were ended. */
    public static native int nativeKick(long handle, @NonNull String name);
    /** Refused guests since the last call, one {@code name<TAB>reason} per line; null when none. */
    @Nullable public static native String nativePoll(long handle);
}
