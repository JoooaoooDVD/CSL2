package com.craftstudio.cslauncher.modding.modpacks;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static data and small helpers shared by the content library and the install screen:
 * which category chips each content type offers, how a category is labelled and tinted,
 * and Minecraft version range math for the "Compatible" badge.
 */
public final class LibraryCatalog {
    private LibraryCatalog() {}

    /** Tag tints used by the category pills on the cards */
    public static final int TINT_NEUTRAL = 0;
    public static final int TINT_GREEN = 1;
    public static final int TINT_BLUE = 2;
    public static final int TINT_SLATE = 3;
    public static final int TINT_PURPLE = 4;
    public static final int TINT_AMBER = 5;

    public static final class Category {
        /** Modrinth category slug, empty for "All" */
        public final String slug;
        public final String label;
        public final int tint;

        Category(String slug, String label, int tint) {
            this.slug = slug;
            this.label = label;
            this.tint = tint;
        }
    }

    private static final Category[] MOD_CATEGORIES = {
            new Category("", "All", TINT_NEUTRAL),
            new Category("optimization", "Performance", TINT_GREEN),
            new Category("utility", "Utility", TINT_BLUE),
            new Category("library", "Library", TINT_SLATE),
            new Category("adventure", "Adventure", TINT_AMBER),
            new Category("decoration", "Decoration", TINT_PURPLE),
            new Category("technology", "Technology", TINT_BLUE),
            new Category("magic", "Magic", TINT_PURPLE),
            new Category("worldgen", "Worldgen", TINT_GREEN),
            new Category("storage", "Storage", TINT_SLATE),
            new Category("equipment", "Equipment", TINT_AMBER),
            new Category("mobs", "Mobs", TINT_AMBER),
    };

    private static final Category[] RESOURCE_PACK_CATEGORIES = {
            new Category("", "All", TINT_NEUTRAL),
            new Category("vanilla-like", "Vanilla-like", TINT_GREEN),
            new Category("realistic", "Realistic", TINT_BLUE),
            new Category("simplistic", "Simplistic", TINT_SLATE),
            new Category("themed", "Themed", TINT_PURPLE),
            new Category("16x", "16x", TINT_AMBER),
            new Category("32x", "32x", TINT_AMBER),
            new Category("64x", "64x", TINT_AMBER),
            new Category("tweaks", "Tweaks", TINT_SLATE),
            new Category("gui", "GUI", TINT_BLUE),
    };

    private static final Category[] SHADER_CATEGORIES = {
            new Category("", "All", TINT_NEUTRAL),
            new Category("realistic", "Realistic", TINT_BLUE),
            new Category("semi-realistic", "Semi-realistic", TINT_SLATE),
            new Category("vanilla-like", "Vanilla-like", TINT_GREEN),
            new Category("fantasy", "Fantasy", TINT_PURPLE),
            new Category("cartoon", "Cartoon", TINT_AMBER),
            new Category("potato", "Potato PC", TINT_GREEN),
            new Category("low", "Low-end", TINT_GREEN),
    };

    private static final Category[] MODPACK_CATEGORIES = {
            new Category("", "All", TINT_NEUTRAL),
            new Category("adventure", "Adventure", TINT_AMBER),
            new Category("kitchen-sink", "Kitchen sink", TINT_SLATE),
            new Category("technology", "Technology", TINT_BLUE),
            new Category("magic", "Magic", TINT_PURPLE),
            new Category("quests", "Quests", TINT_AMBER),
            new Category("lightweight", "Lightweight", TINT_GREEN),
            new Category("optimization", "Performance", TINT_GREEN),
            new Category("multiplayer", "Multiplayer", TINT_BLUE),
            new Category("challenging", "Challenging", TINT_PURPLE),
            new Category("combat", "Combat", TINT_AMBER),
    };

    /** Extra labels for categories that are not offered as chips but may show on a card */
    private static final String[][] EXTRA_LABELS = {
            {"food", "Food"}, {"economy", "Economy"}, {"social", "Social"},
            {"management", "Management"}, {"minigame", "Minigame"}, {"transportation", "Transport"},
            {"game-mechanics", "Mechanics"}, {"cursed", "Cursed"}, {"medium", "Medium"},
            {"high", "High-end"}, {"screenshot", "Screenshot"}, {"atmosphere", "Atmosphere"},
            {"bloom", "Bloom"}, {"shadows", "Shadows"}, {"pbr", "PBR"}, {"reflections", "Reflections"},
            {"path-tracing", "Path tracing"}, {"colored-lighting", "Colored lighting"},
            {"foliage", "Foliage"}, {"audio", "Audio"}, {"blocks", "Blocks"}, {"entities", "Entities"},
            {"environment", "Environment"}, {"fonts", "Fonts"}, {"items", "Items"},
            {"locale", "Locale"}, {"models", "Models"}, {"modded", "Modded"}, {"combat", "Combat"},
            {"128x", "128x"}, {"256x", "256x"}, {"512x+", "512x+"}, {"8x-", "8x"},
            {"core-shaders", "Core shaders"}, {"kitchen-sink", "Kitchen sink"},
            {"lightweight", "Lightweight"}, {"multiplayer", "Multiplayer"},
            {"challenging", "Challenging"}, {"quests", "Quests"}, {"tech", "Tech"},
    };

    private static final Category[] DATAPACK_CATEGORIES = {
            new Category("", "All", TINT_NEUTRAL),
            new Category("adventure", "Adventure", TINT_GREEN),
            new Category("utility", "Utility", TINT_BLUE),
            new Category("game-mechanics", "Mechanics", TINT_SLATE),
            new Category("magic", "Magic", TINT_AMBER),
            new Category("worldgen", "Worldgen", TINT_GREEN),
            new Category("equipment", "Equipment", TINT_AMBER),
            new Category("decoration", "Decoration", TINT_BLUE),
            new Category("mobs", "Mobs", TINT_AMBER),
            new Category("technology", "Technology", TINT_SLATE),
            new Category("minigame", "Minigame", TINT_GREEN),
    };

    private static final Category[] WORLD_CATEGORIES = {
            new Category("", "All", TINT_NEUTRAL),
            new Category("adventure", "Adventure", TINT_GREEN),
            new Category("survival", "Survival", TINT_AMBER),
            new Category("creation", "Creation", TINT_BLUE),
            new Category("game-map", "Game map", TINT_SLATE),
            new Category("parkour", "Parkour", TINT_GREEN),
            new Category("puzzle", "Puzzle", TINT_BLUE),
            new Category("modded-world", "Modded", TINT_AMBER),
    };

    @NonNull
    public static Category[] categoriesFor(@NonNull ContentType type) {
        switch (type) {
            case WORLD: return WORLD_CATEGORIES;
            case RESOURCE_PACK: return RESOURCE_PACK_CATEGORIES;
            case SHADER_PACK: return SHADER_CATEGORIES;
            case MODPACK: return MODPACK_CATEGORIES;
            case DATAPACK: return DATAPACK_CATEGORIES;
            default: return MOD_CATEGORIES;
        }
    }

    @Nullable
    private static Category findCategory(@NonNull ContentType type, String slug) {
        for (Category category : categoriesFor(type)) {
            if (category.slug.equals(slug)) return category;
        }
        return null;
    }

    @NonNull
    public static String labelFor(@NonNull ContentType type, @NonNull String slug) {
        Category category = findCategory(type, slug);
        if (category != null) return category.label;
        for (ContentType other : ContentType.values()) {
            category = findCategory(other, slug);
            if (category != null && !category.slug.isEmpty()) return category.label;
        }
        for (String[] pair : EXTRA_LABELS) {
            if (pair[0].equals(slug)) return pair[1];
        }
        return capitalize(slug.replace('-', ' '));
    }

    public static int tintFor(@NonNull ContentType type, @NonNull String slug) {
        Category category = findCategory(type, slug);
        return category != null ? category.tint : TINT_NEUTRAL;
    }

    /**
     * Picks the category shown as the first pill of a card: the one matching the active
     * chip if any, otherwise the first category that has a chip, otherwise the first one.
     */
    @Nullable
    public static String primaryCategory(@NonNull ContentType type, @NonNull ModItem item, @Nullable String activeCategory) {
        if (item.categories == null || item.categories.length == 0) return null;
        List<String> categories = Arrays.asList(item.categories);
        if (activeCategory != null && !activeCategory.isEmpty() && categories.contains(activeCategory)) {
            return activeCategory;
        }
        for (Category category : categoriesFor(type)) {
            if (!category.slug.isEmpty() && categories.contains(category.slug)) return category.slug;
        }
        return item.categories[0];
    }

    @NonNull
    public static String loaderLabel(@Nullable String loader) {
        if (loader == null || loader.isEmpty()) return "";
        switch (loader.toLowerCase(Locale.ROOT)) {
            case "fabric": return "Fabric";
            case "forge": return "Forge";
            case "neoforge": return "NeoForge";
            case "quilt": return "Quilt";
            case "iris": return "Iris";
            case "optifine": return "OptiFine";
            case "canvas": return "Canvas";
            case "vanilla": return "Vanilla";
            case "minecraft": return "Minecraft";
            case "datapack": return "Datapack";
            default: return capitalize(loader);
        }
    }

    /**
     * Whether a project/version built for the given loaders runs on the profile loader.
     * Quilt loads Fabric mods, and an empty profile loader means "anything goes".
     */
    public static boolean loaderMatches(@Nullable String[] loaders, @Nullable String profileLoader) {
        if (profileLoader == null || profileLoader.isEmpty()) return true;
        if (loaders == null || loaders.length == 0) return true;
        for (String loader : loaders) {
            if (loader == null) continue;
            if (loader.equalsIgnoreCase(profileLoader)) return true;
            if ("quilt".equals(profileLoader) && "fabric".equalsIgnoreCase(loader)) return true;
        }
        return false;
    }

    /** Chooses the loader pill for a card: the profile loader when supported, else the first one */
    @Nullable
    public static String displayLoader(@NonNull ContentType type, @NonNull ModItem item, @Nullable String profileLoader) {
        if (item.loaders == null || item.loaders.length == 0) return null;
        if (type == ContentType.MOD && profileLoader != null && !profileLoader.isEmpty()) {
            for (String loader : item.loaders) {
                if (profileLoader.equalsIgnoreCase(loader)) return loader;
            }
        }
        for (String loader : item.loaders) {
            if (type == ContentType.RESOURCE_PACK && "minecraft".equals(loader)) continue;
            return loader;
        }
        return null;
    }

    public static boolean containsVersion(@Nullable String[] versions, @Nullable String version) {
        if (versions == null || version == null) return false;
        for (String candidate : versions) {
            if (version.equals(candidate)) return true;
        }
        return false;
    }

    // ─── Minecraft version helpers ──────────────────────────────────────────

    private static final Pattern RELEASE_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?$");

    public static boolean isRelease(@Nullable String version) {
        return version != null && RELEASE_PATTERN.matcher(version).matches();
    }

    /** Compares two plain release versions numerically, "1.21.10" sorts above "1.21.9" */
    public static int compareVersions(@NonNull String a, @NonNull String b) {
        Matcher ma = RELEASE_PATTERN.matcher(a);
        Matcher mb = RELEASE_PATTERN.matcher(b);
        if (!ma.matches() || !mb.matches()) return a.compareTo(b);
        for (int group = 1; group <= 3; group++) {
            int va = ma.group(group) == null ? 0 : Integer.parseInt(ma.group(group));
            int vb = mb.group(group) == null ? 0 : Integer.parseInt(mb.group(group));
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    /** @return the supported release versions, oldest first */
    @NonNull
    public static List<String> sortedReleases(@Nullable String[] versions) {
        List<String> releases = new ArrayList<>();
        if (versions == null) return releases;
        for (String version : versions) {
            if (isRelease(version) && !releases.contains(version)) releases.add(version);
        }
        Collections.sort(releases, LibraryCatalog::compareVersions);
        return releases;
    }

    /** "1.21.1" becomes "1.21.x" */
    @NonNull
    public static String family(@NonNull String version) {
        Matcher matcher = RELEASE_PATTERN.matcher(version);
        if (!matcher.matches()) return version;
        return matcher.group(1) + "." + matcher.group(2) + ".x";
    }

    /**
     * Human readable range of supported versions for the card badge. When the whole
     * range sits inside one minor line it reads like "1.21.x - 1.21.11".
     */
    @Nullable
    public static String versionRange(@Nullable String[] versions) {
        List<String> releases = sortedReleases(versions);
        if (releases.isEmpty()) {
            if (versions != null && versions.length > 0) return versions[versions.length - 1];
            return null;
        }
        String oldest = releases.get(0);
        String newest = releases.get(releases.size() - 1);
        if (oldest.equals(newest)) return newest;
        if (family(oldest).equals(family(newest))) return family(newest) + " \u2013 " + newest;
        return oldest + " \u2013 " + newest;
    }

    /** Version pill of a card: the profile family when supported, else the newest family */
    @Nullable
    public static String versionPill(@Nullable String[] versions, @Nullable String profileVersion) {
        if (profileVersion != null && containsVersion(versions, profileVersion)) {
            return isRelease(profileVersion) ? family(profileVersion) : profileVersion;
        }
        List<String> releases = sortedReleases(versions);
        if (releases.isEmpty()) return null;
        return family(releases.get(releases.size() - 1));
    }

    // ─── Formatting ─────────────────────────────────────────────────────────

    @NonNull
    public static String compactNumber(long value) {
        if (value >= 1_000_000_000L) return trimDecimal(value / 1_000_000_000d) + "B";
        if (value >= 1_000_000L) return trimDecimal(value / 1_000_000d) + "M";
        if (value >= 1_000L) return trimDecimal(value / 1_000d) + "K";
        return String.valueOf(value);
    }

    private static String trimDecimal(double value) {
        String text = String.format(Locale.ROOT, value >= 100 ? "%.0f" : "%.1f", value);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return text;
    }

    @NonNull
    public static String fileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024d * 1024d));
        if (bytes >= 1024L) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024d);
        return bytes + " B";
    }

    /** Parses the date part of an ISO-8601 timestamp into epoch millis, -1 when it can't */
    public static long parseIsoDate(@Nullable String iso) {
        if (iso == null || iso.length() < 10) return -1;
        try {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date date = format.parse(iso.substring(0, 10));
            return date == null ? -1 : date.getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    /** "today", "3 days ago", "2 months ago" ... */
    @NonNull
    public static String relativeDate(@Nullable String iso) {
        long time = parseIsoDate(iso);
        if (time < 0) return "";
        long days = (System.currentTimeMillis() - time) / 86_400_000L;
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < 30) return days + " days ago";
        long months = days / 30;
        if (months < 12) return months == 1 ? "1 month ago" : months + " months ago";
        long years = days / 365;
        return years <= 1 ? "1 year ago" : years + " years ago";
    }

    @NonNull
    public static String capitalize(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    /** True for the loaders that actually load mods from the mods folder (not vanilla, not OptiFine). */
    public static boolean isModLoader(String loader) {
        if (loader == null) return false;
        switch (loader.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "fabric": case "quilt": case "forge": case "neoforge": case "liteloader": case "rift": return true;
            default: return false;
        }
    }
}
