package com.craftstudio.cslauncher.game.branding;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * The independently selectable pieces of the CS Custom Experience. The user picks "everything"
 * or just the parts they want (e.g. only the main menu); the generated pack contains only the
 * selected parts and the options flag follows {@link #DARK_LOADING}.
 */
public enum BrandingPart {
    /** Title logo, edition line, panorama, splash texts: the home screen. */
    MENU("menu"),
    /** Buttons, sliders, text fields, scrollbars, checkboxes (every screen). */
    WIDGETS("widgets"),
    /** Dark backgrounds + separators of option/list screens outside a world. */
    SCREENS("screens"),
    /** English loading / connecting texts prefixed with Craft Studio. */
    TEXTS("texts"),
    /** options.txt darkMojangStudiosBackgroundColor=true for the first loading screen. */
    DARK_LOADING("dark_loading");

    public final String key;

    BrandingPart(String key) {
        this.key = key;
    }

    public static EnumSet<BrandingPart> all() {
        return EnumSet.allOf(BrandingPart.class);
    }

    /** Only the main menu: what "just the home screen" means. */
    public static EnumSet<BrandingPart> menuOnly() {
        return EnumSet.of(MENU);
    }

    /**
     * Parses the stored form ("menu,widgets,…"). Null/empty/"all" ⇒ everything (the default and
     * the migration path for profiles saved before parts existed). Unknown tokens are ignored;
     * if nothing valid remains, everything is selected rather than producing an empty pack.
     */
    public static EnumSet<BrandingPart> parse(String stored) {
        if (stored == null) return all();
        String s = stored.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("all")) return all();
        EnumSet<BrandingPart> set = EnumSet.noneOf(BrandingPart.class);
        for (String token : s.split("[,\\s]+")) {
            for (BrandingPart part : values()) if (part.key.equals(token)) set.add(part);
        }
        return set.isEmpty() ? all() : set;
    }

    /** Stored form; null when everything is selected (keeps old profiles byte-identical). */
    public static String serialize(EnumSet<BrandingPart> parts) {
        if (parts == null || parts.containsAll(all())) return null;
        List<String> keys = new ArrayList<>();
        for (BrandingPart part : values()) if (parts.contains(part)) keys.add(part.key);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(keys.get(i));
        }
        return sb.toString();
    }

    /** Stable, order-independent id used in the pack stamp. */
    public static String stampToken(EnumSet<BrandingPart> parts) {
        StringBuilder sb = new StringBuilder();
        for (BrandingPart part : values()) sb.append(parts.contains(part) ? '1' : '0');
        return sb.toString();
    }

    /** True when the pack would contain at least one file (anything but DARK_LOADING alone). */
    public static boolean needsPack(EnumSet<BrandingPart> parts) {
        for (BrandingPart part : parts) if (part != DARK_LOADING) return true;
        return false;
    }
}
