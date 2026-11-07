package com.craftstudio.cslauncher.e4mc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * JNI binding of {@code libcs_e4mc.so}: the e4mc tunnel built by the CS Engine CI (same code the
 * engine uses for its own "Share with friends"). Publishes a local TCP port under a public
 * {@code *.e4mc.link} address through the e4mc relay network (QUIC), so a Java "Open to LAN" world
 * is reachable by friends on any network. No VPN, no permissions.
 */
public final class E4mcNative {
    private static volatile boolean sLoaded;
    @Nullable private static volatile String sLoadError;

    private E4mcNative() {}

    /** Loads the library once; false (with {@link #loadError()}) when this build has no e4mc library. */
    public static boolean load() {
        if (sLoaded) return true;
        synchronized (E4mcNative.class) {
            if (sLoaded) return true;
            try {
                System.loadLibrary("cs_e4mc");
                sLoaded = true;
            } catch (UnsatisfiedLinkError | SecurityException e) {
                sLoadError = e.getMessage();
            }
            return sLoaded;
        }
    }

    @Nullable public static String loadError() { return sLoadError; }

    /** Starts a publish of local port {@code port}; 0 on failure (see {@link #nativeError()}). */
    public static native long nativeStart(int port, @Nullable String relayOrNull);
    /** The assigned public address ({@code name.e4mc.link}) or null while connecting. */
    @Nullable public static native String nativeDomain(long handle);
    /** A problem reported by the tunnel, or null. */
    @Nullable public static native String nativeProblem(long handle);
    /** Guests currently connected through the tunnel. */
    public static native int nativeGuests(long handle);
    public static native void nativeStop(long handle);
    /**
     * Who the relay lets in from now on: {@code maxPlayers} guests at once (0 = no limit at the
     * relay), and when {@code allowedNamesOrNull} is set (newline separated) only those names,
     * others shown {@code reason}. Guests already playing stay.
     */
    public static native void nativeSetPolicy(long handle, int maxPlayers, @Nullable String allowedNamesOrNull, @Nullable String reason);
    /** Names the host removed (newline separated, null = none): refused at the door however open the world is. */
    public static native void nativeSetBlocked(long handle, @Nullable String blockedOrNull);
    /** The guests connected right now, one name per line (login order); null when none. */
    @Nullable public static native String nativeGuestNames(long handle);
    /** Ends the connection of the guest logged in as {@code name}; how many streams were ended. */
    public static native int nativeKick(long handle, @NonNull String name);
    /** Refused guests since the last call, one {@code name<TAB>reason} per line; null when none. */
    @Nullable public static native String nativePoll(long handle);
    @Nullable public static native String nativeError();
}
