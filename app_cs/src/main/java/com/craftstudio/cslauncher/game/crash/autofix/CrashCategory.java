package com.craftstudio.cslauncher.game.crash.autofix;

/**
 * Crash classes the analyzer can recognise. Pure data, no Android dependency so the
 * classification layer can run in plain JVM unit tests.
 */
public enum CrashCategory {
    JAVA_RUNTIME_ERROR,
    OUT_OF_MEMORY,
    MOD_CONFLICT,
    MISSING_DEPENDENCY,
    INCOMPATIBLE_MOD_VERSION,
    CORRUPTED_CONFIGURATION,
    CORRUPTED_LIBRARY,
    INVALID_JVM_ARGUMENT,
    GRAPHICS_INITIALIZATION_ERROR,
    SHADER_ERROR,
    RESOURCE_PACK_ERROR,
    NATIVE_LIBRARY_ERROR,
    DISK_OR_STORAGE_ERROR,
    NETWORK_DOWNLOAD_ERROR,
    UNKNOWN_ERROR;

    /** Parses a category name leniently (case-insensitive); null when unknown. */
    public static CrashCategory parse(String name) {
        if (name == null) return null;
        String n = name.trim().toUpperCase(java.util.Locale.ROOT);
        for (CrashCategory c : values()) if (c.name().equals(n)) return c;
        return null;
    }
}
