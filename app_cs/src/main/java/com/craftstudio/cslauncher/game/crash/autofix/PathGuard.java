package com.craftstudio.cslauncher.game.crash.autofix;

import java.io.File;
import java.io.IOException;

/**
 * Keeps every repair inside the folders it is allowed to touch. All checks use canonical paths
 * so "..", symlinks and double slashes cannot escape the profile.
 */
public final class PathGuard {
    private PathGuard() {}

    /** True when {@code file} is {@code root} itself or lives below it (after canonicalisation). */
    public static boolean isInside(File root, File file) {
        if (root == null || file == null) return false;
        try {
            String r = root.getCanonicalPath();
            String f = file.getCanonicalPath();
            if (f.equals(r)) return true;
            if (!r.endsWith(File.separator)) r = r + File.separator;
            return f.startsWith(r);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Resolves a relative path supplied by a log line against a root, rejecting absolute paths,
     * parent references and anything that would land outside the root.
     * @return the resolved file or null when the path is not acceptable
     */
    public static File resolveRelative(File root, String relative) {
        if (root == null || relative == null) return null;
        String rel = relative.trim().replace('\\', '/');
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\0")) return null;
        for (String seg : rel.split("/")) if (seg.equals("..") || seg.isEmpty() || seg.equals(".")) return null;
        File f = new File(root, rel);
        return isInside(root, f) ? f : null;
    }

    /** A file name with no directory part and no traversal, usable as a mods/ entry. */
    public static boolean isPlainFileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255) return false;
        if (name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || c < 0x20) return false;
        }
        return true;
    }
}
