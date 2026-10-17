package com.craftstudio.cslauncher.game.branding;

/**
 * Per-profile "Minecraft Appearance" choice.
 * <ul>
 *   <li>{@link #VANILLA}: the game keeps its original menu and loading screen. The launcher pack
 *   is left on disk but removed from the active list and the previous options are restored.</li>
 *   <li>{@link #CUSTOM}: the CS Dynamic Branding resource pack is generated for the profile's
 *   game version, installed in the profile's own resourcepacks folder and enabled before launch.</li>
 * </ul>
 * Stored in the profile JSON as a plain string so a corrupted or unknown value degrades to VANILLA.
 */
public enum BrandingMode {
    VANILLA("vanilla"),
    CUSTOM("custom");

    public final String key;

    BrandingMode(String key) {
        this.key = key;
    }

    /** Lenient parser: null, empty or unknown values mean VANILLA (never throws). */
    public static BrandingMode fromKey(String key) {
        if (key == null) return VANILLA;
        String k = key.trim().toLowerCase(java.util.Locale.ROOT);
        for (BrandingMode mode : values()) if (mode.key.equals(k)) return mode;
        return VANILLA;
    }
}
