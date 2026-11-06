package com.craftstudio.cslauncher.friends;

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import java.io.IOException;
import java.util.List;

/**
 * "Join World" from an invite: picks a profile of the right engine (the selected one when it
 * matches, else the first), remembers the address for the launch that follows and presses PLAY.
 * The Java pipeline appends {@code --quickPlayMultiplayer} (1.20+) or {@code --server/--port};
 * the CS Engine gets {@code server} in its launch intent.
 */
public final class JoinWorld {
    private static final String K_ADDR = "cs.join.address";
    private static final String K_AT = "cs.join.at";
    private static final String K_VERSION = "cs.join.version";
    private static final long TTL = 3 * 60 * 1000L;

    private JoinWorld() {}

    public static void join(@NonNull Activity activity, @NonNull String address, @Nullable String engine) {
        join(activity, address, engine, null);
    }

    /** @param hostVersion the host's game version when the invite carried one (Java cross-version join) */
    public static void join(@NonNull Activity activity, @NonNull String address, @Nullable String engine, @Nullable String hostVersion) {
        boolean horizon = "horizon".equalsIgnoreCase(engine);
        Instance target = null;
        try {
            Instance selected = Instances.loadSelectedInstance();
            if (selected != null && selected.isHorizon() == horizon && selected.installer == null) target = selected;
            if (target == null) {
                List<Instance> all = Instances.loadAllInstances();
                for (Instance i : all) if (i.isHorizon() == horizon && i.installer == null) { target = i; break; }
            }
        } catch (IOException ignored) {}
        if (target == null) {
            Toast.makeText(activity, horizon ? R.string.friends_no_profile_horizon : R.string.friends_no_profile_java, Toast.LENGTH_LONG).show();
            return;
        }
        Instances.setSelectedInstance(target);
        LauncherPreferences.DEFAULT_PREF.edit().putString(K_ADDR, address).putLong(K_AT, System.currentTimeMillis())
                .putString(K_VERSION, horizon || hostVersion == null || hostVersion.trim().isEmpty() ? null : hostVersion.trim()).apply();
        Toast.makeText(activity, activity.getString(R.string.friends_joining, address), Toast.LENGTH_SHORT).show();
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    /** The host's game version of the pending join (not cleared; valid while the address is), or null. */
    @Nullable
    public static String pendingHostVersion() {
        if (LauncherPreferences.DEFAULT_PREF.getString(K_ADDR, null) == null) return null;
        long at = LauncherPreferences.DEFAULT_PREF.getLong(K_AT, 0);
        if (System.currentTimeMillis() - at > TTL) return null;
        return LauncherPreferences.DEFAULT_PREF.getString(K_VERSION, null);
    }

    /** The pending address for the launch happening now (cleared), or null. */
    @Nullable
    public static String consume() {
        String a = LauncherPreferences.DEFAULT_PREF.getString(K_ADDR, null);
        long at = LauncherPreferences.DEFAULT_PREF.getLong(K_AT, 0);
        if (a == null) return null;
        LauncherPreferences.DEFAULT_PREF.edit().remove(K_ADDR).remove(K_AT).remove(K_VERSION).apply();
        if (System.currentTimeMillis() - at > TTL) return null;
        return a.trim().isEmpty() ? null : a.trim();
    }

    /** Appends the Java client arguments for a pending join; no-op when there is none. */
    public static void appendJavaArgs(@NonNull List<String> args, @Nullable String versionId) {
        String address = consume();
        if (address == null) return;
        if (supportsQuickPlay(versionId)) {
            args.add("--quickPlayMultiplayer");
            args.add(address);
        } else {
            String host = address;
            int port = 25565;
            int colon = address.lastIndexOf(':');
            if (colon > 0 && address.indexOf(':') == colon) {
                try { port = Integer.parseInt(address.substring(colon + 1)); host = address.substring(0, colon); } catch (NumberFormatException ignored) {}
            }
            args.add("--server");
            args.add(host);
            args.add("--port");
            args.add(String.valueOf(port));
        }
    }

    /** Quick Play exists since 1.20 (23w14a); older releases use --server/--port. */
    static boolean supportsQuickPlay(@Nullable String versionId) {
        if (versionId == null) return true;
        String v = versionId.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^1\\.(\\d+)").matcher(v);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)) >= 20; } catch (NumberFormatException ignored) {}
        }
        m = java.util.regex.Pattern.compile("^(\\d\\d)w(\\d\\d)").matcher(v);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1)), week = Integer.parseInt(m.group(2));
            return year > 23 || (year == 23 && week >= 14);
        }
        // Modded ids like "fabric-loader-0.15-1.20.4": look for an embedded release number
        m = java.util.regex.Pattern.compile("1\\.(\\d+)").matcher(v);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)) >= 20; } catch (NumberFormatException ignored) {}
        }
        return true;
    }
}
