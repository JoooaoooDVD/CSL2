package com.craftstudio.cslauncher.worlds;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads the save folders of an instance game directory. A real world always
 * carries level.dat; folders without one are still listed so renamed or
 * half-copied saves never silently disappear.
 */
public final class WorldSaves {

    private WorldSaves() {}

    public static final class WorldInfo {
        /** The world folder itself (its name is the fallback display name). */
        public final File dir;
        public final String name;
        /** Last time the world was played (level.dat mtime, else the folder mtime). */
        public final long lastPlayed;
        public final long sizeBytes;
        /** Optional world icon (icon.png inside the folder), may not exist. */
        @Nullable
        public final File iconFile;

        WorldInfo(@NonNull File dir) {
            this.dir = dir;
            this.name = dir.getName();
            File level = new File(dir, "level.dat");
            this.lastPlayed = level.isFile() ? level.lastModified() : dir.lastModified();
            this.sizeBytes = sizeOf(dir);
            File icon = new File(dir, "icon.png");
            this.iconFile = icon.isFile() ? icon : null;
        }
    }

    /** Worlds of the given game directory, newest first. Never null. */
    @NonNull
    public static List<WorldInfo> listWorlds(@Nullable File gameDir) {
        if (gameDir == null) return Collections.emptyList();
        File saves = new File(gameDir, "saves");
        File[] dirs = saves.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return Collections.emptyList();
        List<WorldInfo> worlds = new ArrayList<>();
        List<WorldInfo> loose = new ArrayList<>();
        for (File dir : dirs) {
            WorldInfo info = new WorldInfo(dir);
            if (new File(dir, "level.dat").isFile() || new File(dir, "level.dat_old").isFile()) {
                worlds.add(info);
            } else {
                loose.add(info);
            }
        }
        if (worlds.isEmpty()) worlds.addAll(loose);
        worlds.sort((a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
        return worlds;
    }

    /** The datapacks folder of a world, created on demand. */
    @NonNull
    public static File datapacksDir(@NonNull File worldDir) {
        File datapacks = new File(worldDir, "datapacks");
        //noinspection ResultOfMethodCallIgnored
        datapacks.mkdirs();
        return datapacks;
    }

    /** Checks a picked file is a datapack: a zip containing pack.mcmeta at its root. */
    public static boolean looksLikeDatapack(@NonNull File zipFile) {
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(zipFile);
            return zip.getEntry("pack.mcmeta") != null;
        } catch (Exception e) {
            return false;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /** "1.4 GB" style size for the detail line. */
    @NonNull
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** Total size of a directory tree. Worlds are small enough to total synchronously. */
    public static long sizeOf(@NonNull File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            total += file.isDirectory() ? sizeOf(file) : file.length();
        }
        return total;
    }
}
