package com.craftstudio.cslauncher.game.crash.autofix;

import java.util.Locale;

/**
 * Decides whether a downloaded jar really is the dependency the loader asked for, comparing the
 * mod id declared inside the jar with the requested id and the Modrinth slug it came from.
 */
public final class DependencyIdMatcher {
    private DependencyIdMatcher() {}

    public static boolean matches(String declaredId, String wantedId, String slug) {
        if (declaredId == null) return false;
        String d = norm(declaredId), w = norm(wantedId), s = norm(slug);
        if (d.isEmpty()) return false;
        if (d.equals(w)) return true;
        if (s.isEmpty()) return false;
        // alias case: loader asked for "fabric" / "cloth-config2", the jar declares the canonical id
        return d.equals(s) || (d + "-api").equals(s) || d.equals(s + "-api") || s.startsWith(d) || d.startsWith(s);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
