package com.craftstudio.cslauncher.e4mc;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.friends.FriendsClient;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * World hosting settings for Java-edition worlds shared through e4mc (the CS Engine keeps its
 * own copy in its Host page): who may join, how many, and which relay carries the connection.
 * The relay is the door: a guest's login names the player before a byte reaches the game, so
 * a refused guest sees the reason on the login screen and the world never hears of them.
 *
 * <p>The Java integrated server itself caps a LAN world at 8 players, so the Java limit stays
 * within 2..8; cheats for guests are what the game's own Open to LAN screen chose.
 */
public final class HostSettings {
    public static final int ACCESS_ANYONE = 0, ACCESS_FRIENDS = 1, ACCESS_INVITED = 2;
    /** The integrated server's own player list size (IntegratedPlayerList): the Java ceiling. */
    public static final int JAVA_MAX_PLAYERS = 8;

    private static final String K_MAX = "cs.host.max_players";
    private static final String K_ACCESS = "cs.host.access";
    private static final String K_RELAY = "cs.host.relay";

    /** Names invited from this process while hosting ("invited only" lets them in). */
    private static final Set<String> sInvited = Collections.synchronizedSet(new LinkedHashSet<String>());

    private HostSettings() {}

    private static SharedPreferences prefs() { return LauncherPreferences.DEFAULT_PREF; }

    /** Players the world takes, host included (2..8 for Java). */
    public static int maxPlayers() {
        int n = prefs().getInt(K_MAX, JAVA_MAX_PLAYERS);
        return Math.max(2, Math.min(JAVA_MAX_PLAYERS, n));
    }

    public static void setMaxPlayers(int n) {
        prefs().edit().putInt(K_MAX, Math.max(2, Math.min(JAVA_MAX_PLAYERS, n))).apply();
        E4mcPublisher.get().applyPolicy();
    }

    /** {@link #ACCESS_ANYONE}, {@link #ACCESS_FRIENDS} or {@link #ACCESS_INVITED}. */
    public static int access() {
        int a = prefs().getInt(K_ACCESS, ACCESS_ANYONE);
        return a < 0 || a > 2 ? ACCESS_ANYONE : a;
    }

    public static void setAccess(int access) {
        prefs().edit().putInt(K_ACCESS, access < 0 || access > 2 ? ACCESS_ANYONE : access).apply();
        E4mcPublisher.get().applyPolicy();
    }

    /** A relay {@code host:port} the player chose; empty = the broker's least loaded relay. */
    @NonNull
    public static String relay() {
        String r = prefs().getString(K_RELAY, "");
        return r == null ? "" : r.trim();
    }

    /** Null when the text is empty or not {@code host:port}; else the value to hand the native side. */
    @Nullable
    public static String relayOrNull() {
        String r = relay();
        int i = r.lastIndexOf(':');
        if (i <= 0 || i == r.length() - 1) return null;
        try {
            int port = Integer.parseInt(r.substring(i + 1));
            if (port <= 0 || port > 65535) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return r;
    }

    public static void setRelay(@Nullable String relay) {
        prefs().edit().putString(K_RELAY, relay == null ? "" : relay.trim()).apply();
    }

    /** A friend was invited to the running world. */
    public static void noteInvited(@Nullable String name) {
        if (name != null && !name.trim().isEmpty()) sInvited.add(name.trim());
        E4mcPublisher.get().applyPolicy();
    }

    /** Every online friend was invited. */
    public static void noteInvitedAll() {
        for (FriendsClient.Friend f : FriendsClient.get().snapshot().friends) {
            if (f.online() && f.name != null) sInvited.add(f.name.trim());
        }
        E4mcPublisher.get().applyPolicy();
    }

    public static int invitedCount() { return sInvited.size(); }

    public static void clearInvited() { sInvited.clear(); }

    /** The names the relay admits under the current access setting; null = anyone. */
    @Nullable
    public static List<String> allowedNames() {
        switch (access()) {
            case ACCESS_FRIENDS: {
                List<String> out = new ArrayList<>();
                for (FriendsClient.Friend f : FriendsClient.get().snapshot().friends) {
                    if (f.name != null && !f.name.isEmpty()) out.add(f.name);
                }
                return out;
            }
            case ACCESS_INVITED:
                synchronized (sInvited) { return new ArrayList<>(sInvited); }
            default:
                return null;
        }
    }

    /** What a guest the list keeps out reads. */
    @NonNull
    public static String closedReason() {
        switch (access()) {
            case ACCESS_FRIENDS: return "This world is open to the host's CS friends only.";
            case ACCESS_INVITED: return "This world is open to invited players only.";
            default: return "";
        }
    }

    @NonNull
    public static String accessLabel(int access) {
        switch (access) {
            case ACCESS_FRIENDS: return "CS friends only";
            case ACCESS_INVITED: return "Invited players only";
            default: return "Anyone with the address";
        }
    }
}
