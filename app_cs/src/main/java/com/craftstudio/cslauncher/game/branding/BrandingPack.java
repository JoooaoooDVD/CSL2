package com.craftstudio.cslauncher.game.branding;

/** Constants shared by the generator, the options editor and the UI. */
public final class BrandingPack {
    private BrandingPack() {}

    /** Folder name inside {@code <gameDir>/resourcepacks}. No spaces: keeps options.txt and shells simple. */
    public static final String DIR_NAME = "CS-Dynamic-Branding";
    /** Entry Minecraft expects in options.txt {@code resourcePacks} for a folder pack of that name. */
    public static final String PACK_ENTRY = "file/" + DIR_NAME;
    /** Ownership marker written inside the pack. A folder without it is never touched or replaced. */
    public static final String MARKER_FILE = ".cs-dynamic-branding.json";
    /** Temporary build folder (sibling of the pack) used for the atomic swap. */
    public static final String TMP_DIR_NAME = "." + DIR_NAME + ".tmp";
    public static final String OLD_DIR_NAME = "." + DIR_NAME + ".old";

    /** Bump when the generator logic or the bundled branding assets change; forces a one-time rebuild. */
    public static final int GENERATOR_REVISION = 1;

    /** First resource pack format that this generator supports (Minecraft 1.21 / 1.21.1 = 34). */
    public static final int MIN_SUPPORTED_RESOURCE_FORMAT = 34;

    /** options.txt keys the manager edits. Nothing else in the file is modified. */
    public static final String OPT_RESOURCE_PACKS = "resourcePacks";
    public static final String OPT_INCOMPATIBLE_PACKS = "incompatibleResourcePacks";
    public static final String OPT_DARK_LOADING = "darkMojangStudiosBackgroundColor";
}
