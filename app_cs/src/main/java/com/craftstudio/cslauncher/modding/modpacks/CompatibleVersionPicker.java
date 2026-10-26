package com.craftstudio.cslauncher.modding.modpacks;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModDetail;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Picks the version of a project that should be installed for a given Minecraft version and
 * mod loader - the same rule the install screen uses for its "Recommended" badge:
 * newest compatible release, else newest compatible pre-release, else nothing (-1).
 * Pure function over {@link ModDetail}; safe to call from any thread.
 */
public final class CompatibleVersionPicker {
    private CompatibleVersionPicker() {}

    /** @return index into the detail's version arrays, or -1 when nothing fits */
    public static int pick(@Nullable ModDetail detail, @Nullable String mcVersion, @Nullable String loader, @NonNull ContentType type) {
        if (detail == null || detail.versionUrls == null) return -1;
        int count = detail.versionUrls.length;
        int best = -1, bestFallback = -1;
        for (int i = 0; i < count; i++) {
            if (!downloadable(detail, i) || !compatible(detail, i, mcVersion, loader, type)) continue;
            if (release(detail, i)) {
                if (best < 0 || newer(detail, i, best)) best = i;
            } else if (bestFallback < 0 || newer(detail, i, bestFallback)) {
                bestFallback = i;
            }
        }
        return best >= 0 ? best : bestFallback;
    }

    /** True when at least one downloadable version fits the profile */
    public static boolean hasCompatible(@Nullable ModDetail detail, @Nullable String mcVersion, @Nullable String loader, @NonNull ContentType type) {
        return pick(detail, mcVersion, loader, type) >= 0;
    }

    public static boolean downloadable(@NonNull ModDetail d, int i) {
        return d.versionUrls != null && i < d.versionUrls.length && d.versionUrls[i] != null && !d.versionUrls[i].isEmpty();
    }

    public static boolean compatible(@NonNull ModDetail d, int i, @Nullable String mcVersion, @Nullable String loader, @NonNull ContentType type) {
        if (mcVersion == null || mcVersion.isEmpty() || type == ContentType.WORLD) return true;
        if (!LibraryCatalog.containsVersion(gameVersions(d, i), mcVersion)) return false;
        return type != ContentType.MOD || LibraryCatalog.loaderMatches(loaders(d, i), loader);
    }

    public static boolean release(@NonNull ModDetail d, int i) {
        if (d.versionTypes == null || i >= d.versionTypes.length) return true;
        String t = d.versionTypes[i];
        return t == null || t.isEmpty() || "release".equalsIgnoreCase(t);
    }

    @NonNull
    public static String[] gameVersions(@NonNull ModDetail d, int i) {
        if (d.versionGameVersions != null && i < d.versionGameVersions.length && d.versionGameVersions[i] != null) {
            return d.versionGameVersions[i];
        }
        if (d.mcVersionNames != null && i < d.mcVersionNames.length && d.mcVersionNames[i] != null) {
            return new String[]{d.mcVersionNames[i]};
        }
        return new String[0];
    }

    @Nullable
    public static String[] loaders(@NonNull ModDetail d, int i) {
        return d.versionLoaders != null && i < d.versionLoaders.length ? d.versionLoaders[i] : null;
    }

    /** a is newer than b: by publish date when both are known, else by API order (lower index = newer) */
    public static boolean newer(@NonNull ModDetail d, int a, int b) {
        long da = date(d, a), db = date(d, b);
        if (da > 0 && db > 0 && da != db) return da > db;
        return a < b;
    }

    private static long date(ModDetail d, int i) {
        if (d.versionDates == null || i >= d.versionDates.length || d.versionDates[i] == null) return 0;
        String iso = d.versionDates[i];
        try {
            int dot = iso.indexOf('.');
            String trimmed = dot > 0 ? iso.substring(0, dot) : iso.replace("Z", "");
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(trimmed).getTime();
        } catch (ParseException | RuntimeException e) {
            return 0;
        }
    }
}
