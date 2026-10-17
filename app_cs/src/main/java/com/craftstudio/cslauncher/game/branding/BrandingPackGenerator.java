package com.craftstudio.cslauncher.game.branding;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Builds the "CS Dynamic Branding" resource pack for one client jar.
 * <p>
 * Design rules (see docs/DYNAMIC_BRANDING.md):
 * <ul>
 *   <li>Version-aware without version tables: pack.mcmeta uses the exact pack version read from the
 *   jar; every texture is only emitted when the jar itself ships that asset path, so a future
 *   version that renames a file simply loses that one override instead of breaking the pack.</li>
 *   <li>Atomic: files are written to a temp folder, then swapped in with renames. A crash can
 *   leave a stale temp folder (cleaned up next time) but never a half-written pack.</li>
 *   <li>Reusable: a marker file stores a stamp (generator revision + version + pack format). When
 *   it matches and every listed file is still present the pack is reused untouched.</li>
 *   <li>Owned: a folder without the marker is never deleted or replaced.</li>
 * </ul>
 * This class is plain Java (no Android types) so it can be unit tested on a desktop JVM.
 */
public final class BrandingPackGenerator {

    /** One bundled file and where it goes in the pack. */
    private static final class Mapping {
        final String asset;       // name in the branding asset folder
        final String packPath;    // path inside the pack (== jar path it overrides)
        final boolean copyMcmeta; // also copy the jar's <packPath>.mcmeta when present

        Mapping(String asset, String packPath, boolean copyMcmeta) {
            this.asset = asset;
            this.packPath = packPath;
            this.copyMcmeta = copyMcmeta;
        }
    }

    private static final String GUI = "assets/minecraft/textures/gui/";
    private static final Mapping[] MAPPINGS = {
            // Main menu title + edition line (1024x256 / 512x64, same canvas as vanilla since 1.20)
            new Mapping("minecraft.png", GUI + "title/minecraft.png", true),
            new Mapping("edition.png", GUI + "title/edition.png", true),
            // Panorama cube (face order and orientation match the vanilla cube map)
            new Mapping("panorama_0.png", GUI + "title/background/panorama_0.png", false),
            new Mapping("panorama_1.png", GUI + "title/background/panorama_1.png", false),
            new Mapping("panorama_2.png", GUI + "title/background/panorama_2.png", false),
            new Mapping("panorama_3.png", GUI + "title/background/panorama_3.png", false),
            new Mapping("panorama_4.png", GUI + "title/background/panorama_4.png", false),
            new Mapping("panorama_5.png", GUI + "title/background/panorama_5.png", false),
            // Buttons: nine-slice sprites, the jar's .mcmeta is copied so slicing stays correct
            new Mapping("button.png", GUI + "sprites/widget/button.png", true),
            new Mapping("button_highlighted.png", GUI + "sprites/widget/button_highlighted.png", true),
            new Mapping("button_disabled.png", GUI + "sprites/widget/button_disabled.png", true),
            // Other widgets so option screens match the launcher (sliders, fields, scrollbars, checkboxes)
            new Mapping("slider.png", GUI + "sprites/widget/slider.png", true),
            new Mapping("slider_handle.png", GUI + "sprites/widget/slider_handle.png", true),
            new Mapping("slider_handle_highlighted.png", GUI + "sprites/widget/slider_handle_highlighted.png", true),
            new Mapping("text_field.png", GUI + "sprites/widget/text_field.png", true),
            new Mapping("text_field_highlighted.png", GUI + "sprites/widget/text_field_highlighted.png", true),
            new Mapping("scroller.png", GUI + "sprites/widget/scroller.png", true),
            new Mapping("scroller_background.png", GUI + "sprites/widget/scroller_background.png", true),
            new Mapping("checkbox.png", GUI + "sprites/widget/checkbox.png", false),
            new Mapping("checkbox_highlighted.png", GUI + "sprites/widget/checkbox_highlighted.png", false),
            new Mapping("checkbox_selected.png", GUI + "sprites/widget/checkbox_selected.png", false),
            new Mapping("checkbox_selected_highlighted.png", GUI + "sprites/widget/checkbox_selected_highlighted.png", false),
            // Dark backgrounds for option/list screens outside a world (1.20.5+ layout)
            new Mapping("menu_background.png", GUI + "menu_background.png", false),
            new Mapping("menu_list_background.png", GUI + "menu_list_background.png", false),
            new Mapping("header_separator.png", GUI + "header_separator.png", false),
            new Mapping("footer_separator.png", GUI + "footer_separator.png", false),
    };
    private static final String SPLASHES = "assets/minecraft/texts/splashes.txt";
    private static final String EN_US = "assets/minecraft/lang/en_us.json";
    private static final String DEFAULT_NINE_SLICE =
            "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":200,\"height\":20,\"border\":3}}}";

    /** Jar entries the generator wants to know about (passed to {@link ClientJarFacts#read}). */
    public static Set<String> candidateEntries() {
        Set<String> set = new LinkedHashSet<>();
        for (Mapping m : MAPPINGS) {
            set.add(m.packPath);
            if (m.copyMcmeta) set.add(m.packPath + ".mcmeta");
        }
        set.add(SPLASHES);
        set.add(EN_US);
        return set;
    }

    /** Result of {@link #generate}: what was written (or reused) for logging and verification. */
    public static final class Result {
        public final File packDir;
        public final boolean reused;
        public final String stamp;
        public final List<String> written;
        public final List<String> skipped;
        public final int langOverrides;

        Result(File packDir, boolean reused, String stamp, List<String> written, List<String> skipped, int langOverrides) {
            this.packDir = packDir;
            this.reused = reused;
            this.stamp = stamp;
            this.written = written;
            this.skipped = skipped;
            this.langOverrides = langOverrides;
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final BrandingAssetSource mAssets;

    public BrandingPackGenerator(BrandingAssetSource assets) {
        mAssets = assets;
    }

    /** Stamp identifying one exact pack output; stored in the marker and compared for reuse. */
    public static String stampFor(ClientJarFacts facts) {
        return "r" + BrandingPack.GENERATOR_REVISION + ":" + facts.versionId + ":" + facts.packFormatLabel();
    }

    /**
     * Ensures {@code <resourcepacks>/CS-Dynamic-Branding} exists and matches {@code facts}.
     *
     * @param jar             client jar (used for the title .mcmeta / nine-slice metadata copies)
     * @param resourcepacksDir the profile's {@code resourcepacks} folder (created when missing)
     */
    public Result generate(File jar, ClientJarFacts facts, File resourcepacksDir) throws BrandingException {
        if (!facts.isSupported()) {
            throw new BrandingException(BrandingException.Reason.UNSUPPORTED_VERSION,
                    facts.versionId + " uses resource pack format " + facts.packFormatLabel()
                            + " (< " + BrandingPack.MIN_SUPPORTED_RESOURCE_FORMAT + ")");
        }
        // Sanity: a 1.21+ jar must carry the title texture, otherwise our override has no target
        if (!facts.has(GUI + "title/minecraft.png")) {
            throw new BrandingException(BrandingException.Reason.UNSUPPORTED_VERSION,
                    facts.versionId + " does not ship " + GUI + "title/minecraft.png");
        }
        if (!resourcepacksDir.isDirectory() && !resourcepacksDir.mkdirs()) {
            throw new BrandingException(BrandingException.Reason.WRITE_FAILED,
                    "cannot create " + resourcepacksDir);
        }

        File packDir = new File(resourcepacksDir, BrandingPack.DIR_NAME);
        String stamp = stampFor(facts);
        List<String> reusable = reusableFiles(packDir, stamp);
        if (reusable != null) {
            return new Result(packDir, true, stamp, reusable, new ArrayList<String>(), -1);
        }
        if (packDir.exists() && !isOwned(packDir)) {
            throw new BrandingException(BrandingException.Reason.WRITE_FAILED,
                    packDir + " exists but was not created by the launcher; refusing to replace it");
        }

        File tmp = new File(resourcepacksDir, BrandingPack.TMP_DIR_NAME);
        File old = new File(resourcepacksDir, BrandingPack.OLD_DIR_NAME);
        deleteRecursively(tmp);
        deleteRecursively(old);
        if (!tmp.mkdirs()) {
            throw new BrandingException(BrandingException.Reason.WRITE_FAILED, "cannot create " + tmp);
        }

        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int langCount;
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            // 1. pack.mcmeta in the syntax this game version understands
            writeText(new File(tmp, "pack.mcmeta"), packMcmeta(facts));
            written.add("pack.mcmeta");
            // 2. pack icon
            copyAsset("pack.png", new File(tmp, "pack.png"));
            written.add("pack.png");
            // 3. textures that exist in this jar
            for (Mapping m : MAPPINGS) {
                if (!facts.has(m.packPath)) {
                    skipped.add(m.packPath);
                    continue;
                }
                copyAsset(m.asset, new File(tmp, m.packPath));
                written.add(m.packPath);
                if (m.copyMcmeta) {
                    String metaPath = m.packPath + ".mcmeta";
                    if (facts.has(metaPath)) {
                        copyZipEntry(zip, metaPath, new File(tmp, metaPath));
                        written.add(metaPath);
                    } else if (m.packPath.contains("/sprites/widget/button")) {
                        // Button sprites must be nine-sliced; fall back to the known vanilla metadata
                        writeText(new File(tmp, metaPath), DEFAULT_NINE_SLICE);
                        written.add(metaPath);
                    }
                }
            }
            // 4. splashes (the game picks one line at random on the title screen)
            if (facts.has(SPLASHES)) {
                copyAsset("splashes.txt", new File(tmp, SPLASHES));
                written.add(SPLASHES);
            } else {
                skipped.add(SPLASHES);
            }
            // 5. lang overrides: only keys that exist in this version's en_us.json
            langCount = writeLangOverrides(facts, new File(tmp, EN_US));
            if (langCount > 0) written.add(EN_US);
            // 6. ownership marker (last: a folder without it is treated as foreign/incomplete)
            writeText(new File(tmp, BrandingPack.MARKER_FILE), marker(stamp, facts, written));
        } catch (BrandingException e) {
            deleteRecursively(tmp);
            throw e;
        } catch (IOException e) {
            deleteRecursively(tmp);
            throw new BrandingException(BrandingException.Reason.WRITE_FAILED, "write failed: " + e, e);
        } finally {
            if (zip != null) try { zip.close(); } catch (IOException ignored) {}
        }

        // Atomic swap: current -> .old, tmp -> current, delete .old. Roll back if the second rename fails.
        boolean hadOld = packDir.exists();
        if (hadOld && !packDir.renameTo(old)) {
            deleteRecursively(tmp);
            throw new BrandingException(BrandingException.Reason.WRITE_FAILED, "cannot move aside " + packDir);
        }
        if (!tmp.renameTo(packDir)) {
            if (hadOld) old.renameTo(packDir);
            deleteRecursively(tmp);
            throw new BrandingException(BrandingException.Reason.WRITE_FAILED, "cannot activate " + packDir);
        }
        deleteRecursively(old);

        // Verify what we just installed before anyone reports success
        List<String> check = reusableFiles(packDir, stamp);
        if (check == null) {
            throw new BrandingException(BrandingException.Reason.VERIFY_FAILED,
                    "pack verification failed right after install: " + packDir);
        }
        return new Result(packDir, false, stamp, written, skipped, langCount);
    }

    /** True when the folder carries our marker (the only folders the launcher may replace). */
    public static boolean isOwned(File packDir) {
        return new File(packDir, BrandingPack.MARKER_FILE).isFile();
    }

    /**
     * @return the file list recorded in the marker when the pack matches {@code stamp} and every
     *         file is present and non-empty; null when it must be (re)built.
     */
    public static List<String> reusableFiles(File packDir, String stamp) {
        File markerFile = new File(packDir, BrandingPack.MARKER_FILE);
        if (!markerFile.isFile()) return null;
        try {
            JsonObject marker = JsonParser.parseString(readText(markerFile)).getAsJsonObject();
            if (!marker.has("stamp") || !stamp.equals(marker.get("stamp").getAsString())) return null;
            JsonArray files = marker.getAsJsonArray("files");
            if (files == null || files.size() == 0) return null;
            List<String> list = new ArrayList<>();
            for (JsonElement e : files) {
                String rel = e.getAsString();
                File f = new File(packDir, rel);
                if (!f.isFile() || f.length() == 0) return null;
                list.add(rel);
            }
            // pack.mcmeta must still parse (guards against partial edits by other tools)
            JsonParser.parseString(readText(new File(packDir, "pack.mcmeta"))).getAsJsonObject().getAsJsonObject("pack");
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    /** The pack.mcmeta content for a given jar (exposed for tests). */
    public static String packMcmeta(ClientJarFacts facts) {
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", facts.resourceMajor);
        if (facts.majorMinorForm) {
            // 1.21.9+ reads min_format/max_format. Plain ints are deliberate: the game expands an
            // int to [major, 0] for min and [major, MAX] for max (PackFormat BOTTOM/TOP codecs),
            // so every minor revision of this major (97.0, 97.1, ...) stays "compatible" and the
            // pack is never dropped from options.txt as "no longer compatible".
            pack.addProperty("min_format", facts.resourceMajor);
            pack.addProperty("max_format", facts.resourceMajor);
        }
        JsonObject description = new JsonObject();
        description.addProperty("text", "CS Dynamic Branding");
        description.addProperty("color", "gray");
        pack.add("description", description);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return GSON.toJson(root);
    }

    private static String marker(String stamp, ClientJarFacts facts, List<String> files) {
        JsonObject root = new JsonObject();
        root.addProperty("owner", "CS Launcher Plus");
        root.addProperty("stamp", stamp);
        root.addProperty("generatorRevision", BrandingPack.GENERATOR_REVISION);
        root.addProperty("gameVersion", facts.versionId);
        root.addProperty("packFormat", facts.packFormatLabel());
        root.addProperty("generatedAt", System.currentTimeMillis());
        JsonArray arr = new JsonArray();
        for (String f : files) arr.add(f);
        root.add("files", arr);
        return GSON.toJson(root);
    }

    private int writeLangOverrides(ClientJarFacts facts, File target) throws IOException, BrandingException {
        if (!facts.has(EN_US) || facts.englishKeys.isEmpty()) return 0;
        JsonObject overrides;
        InputStream in = mAssets.open("lang_overrides.json");
        try {
            overrides = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } finally {
            in.close();
        }
        Map<String, String> applied = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : overrides.entrySet()) {
            if (facts.englishKeys.contains(e.getKey())) applied.put(e.getKey(), e.getValue().getAsString());
        }
        if (applied.isEmpty()) return 0;
        writeText(target, GSON.toJson(applied));
        return applied.size();
    }

    private void copyAsset(String asset, File target) throws IOException, BrandingException {
        InputStream in;
        try {
            in = mAssets.open(asset);
        } catch (IOException e) {
            throw new BrandingException(BrandingException.Reason.ASSET_MISSING, "bundled asset missing: " + asset, e);
        }
        if (in == null) throw new BrandingException(BrandingException.Reason.ASSET_MISSING, "bundled asset missing: " + asset);
        try {
            writeStream(in, target);
        } finally {
            in.close();
        }
    }

    private static void copyZipEntry(ZipFile zip, String entryName, File target) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) return;
        InputStream in = zip.getInputStream(entry);
        try {
            writeStream(in, target);
        } finally {
            in.close();
        }
    }

    private static void writeStream(InputStream in, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        OutputStream out = new FileOutputStream(target);
        try {
            byte[] buf = new byte[16 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            if (total == 0) throw new IOException("empty source for " + target.getName());
        } finally {
            out.close();
        }
    }

    static void writeText(File target, String text) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        OutputStream out = new FileOutputStream(target);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    /** Test hook (desktop harness); not used by the launcher. */
    public static String readTextForTest(File file) throws IOException {
        return readText(file);
    }

    static String readText(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
