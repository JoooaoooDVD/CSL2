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
    /** The game's own LAN limit (IntegratedPlayerList passes 8): what applies without the host agent. */
    public static final int JAVA_DEFAULT_MAX = 8;
    /**
     * The ceiling the CS host agent lifts a Java world to. "No limit" is this number: the game needs a
     * number, the relay carries 512 streams at most, and nobody brings more friends than that.
     */
    public static final int JAVA_MAX_PLAYERS = 512;
    /** The system property the host agent reads at game start ({@code -Dcs.host.maxPlayers}). */
    public static final String JAVA_PROPERTY = "cs.host.maxPlayers";
    /** The value {@link #maxPlayers()} stores for "no limit". */
    public static final int NO_LIMIT = 0;

    private static final String K_MAX = "cs.host.max_players";
    private static final String K_ACCESS = "cs.host.access";
    private static final String K_RELAY = "cs.host.relay";

    /** Names invited from this process while hosting ("invited only" lets them in). */
    private static final Set<String> sInvited = Collections.synchronizedSet(new LinkedHashSet<String>());

    private HostSettings() {}

    private static SharedPreferences prefs() { return LauncherPreferences.DEFAULT_PREF; }

    /** Server icon friends see in their server list: the CS Launcher mark, the profile's icon, or a custom PNG. */
    public static final int ICON_CS = 0, ICON_PROFILE = 1, ICON_CUSTOM = 2;
    private static final String K_ICON = "cs.host.icon";
    /** A 64x64 PNG with this name in the profile's game folder is the custom server icon (same as CS Engine). */
    public static final String CUSTOM_ICON = "cs-server-icon-custom.png";

    public static int icon() {
        int i = prefs().getInt(K_ICON, ICON_CS);
        return i < ICON_CS || i > ICON_CUSTOM ? ICON_CS : i;
    }

    public static void setIcon(int mode) { prefs().edit().putInt(K_ICON, mode).apply(); }

    @NonNull
    public static String iconLabel(int mode) {
        switch (mode) {
            case ICON_PROFILE: return "Profile icon";
            case ICON_CUSTOM: return "Custom (" + CUSTOM_ICON + ")";
            default: return "CS Launcher";
        }
    }

    /**
     * Writes {@code server-icon.png} (64x64) into the game folder before the game starts: the
     * integrated server reads it from there for the icon in friends' server lists.
     */
    public static void writeServerIcon(@NonNull android.content.Context context, @NonNull java.io.File gameDir, @Nullable java.io.File profileIcon) {
        java.io.File out = new java.io.File(gameDir, "server-icon.png");
        android.graphics.Bitmap src = null;
        try {
            int mode = icon();
            if (mode == ICON_CUSTOM) {
                java.io.File custom = new java.io.File(gameDir, CUSTOM_ICON);
                if (custom.isFile()) src = android.graphics.BitmapFactory.decodeFile(custom.getAbsolutePath());
            } else if (mode == ICON_PROFILE && profileIcon != null && profileIcon.isFile()) {
                src = android.graphics.BitmapFactory.decodeFile(profileIcon.getAbsolutePath());
            }
            if (src == null) {
                android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(context, com.craftstudio.cslauncher.R.mipmap.ic_launcher);
                if (d != null) {
                    src = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas c = new android.graphics.Canvas(src);
                    d.setBounds(0, 0, 64, 64);
                    d.draw(c);
                }
            }
            if (src == null) return;
            android.graphics.Bitmap scaled = src.getWidth() == 64 && src.getHeight() == 64 ? src : android.graphics.Bitmap.createScaledBitmap(src, 64, 64, true);
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo);
            }
        } catch (java.io.IOException | RuntimeException e) {
            android.util.Log.w("HostSettings", "server icon: " + e);
        }
    }

    /** The stops the +/- buttons walk; 0 is "no limit". */
    public static final int[] STEPS = {2, 4, 6, 8, 10, 12, 16, 20, 24, 32, 48, 64, 100, 200, NO_LIMIT};

    /** Players the world takes, host included: 2..512, or {@link #NO_LIMIT}. */
    public static int maxPlayers() {
        int n = prefs().getInt(K_MAX, JAVA_DEFAULT_MAX);
        if (n == NO_LIMIT) return NO_LIMIT;
        return Math.max(2, Math.min(JAVA_MAX_PLAYERS, n));
    }

    /** What the game is told at start: the limit, or the ceiling for "no limit". */
    public static int javaMaxPlayers() {
        int n = maxPlayers();
        return n == NO_LIMIT ? JAVA_MAX_PLAYERS : n;
    }

    public static void setMaxPlayers(int n) {
        prefs().edit().putInt(K_MAX, n == NO_LIMIT ? NO_LIMIT : Math.max(2, Math.min(JAVA_MAX_PLAYERS, n))).apply();
        E4mcPublisher.get().applyPolicy();
    }

    /** The next stop up ({@code +1}) or down ({@code -1}) from the current limit. */
    public static int stepMaxPlayers(int direction) {
        int cur = maxPlayers();
        int i = STEPS.length - 1;
        if (cur != NO_LIMIT) {
            i = STEPS.length - 2;
            for (int k = 0; k < STEPS.length - 1; k++) if (STEPS[k] >= cur) { i = k; break; }
        }
        i = Math.max(0, Math.min(STEPS.length - 1, i + direction));
        return STEPS[i];
    }

    @NonNull
    public static String maxPlayersLabel() {
        int n = maxPlayers();
        return n == NO_LIMIT ? "No limit" : String.valueOf(n);
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
