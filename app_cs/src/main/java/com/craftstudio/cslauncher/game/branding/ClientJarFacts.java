package com.craftstudio.cslauncher.game.branding;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What the generator needs to know about one Minecraft client jar, read from the jar itself so the
 * pack is always built for the exact version that will run (no hard-coded version tables).
 * <p>
 * {@code version.json} carries the pack version in two shapes:
 * <pre>
 *   1.21 – 1.21.8 : "pack_version": {"resource": 34, "data": 48}
 *   1.21.9+       : "pack_version": {"resource_major": 75, "resource_minor": 0, ...}
 * </pre>
 * Both are normalised into {@link #resourceMajor}/{@link #resourceMinor}; {@link #majorMinorForm}
 * remembers which pack.mcmeta syntax the game understands.
 */
public final class ClientJarFacts {
    public final String versionId;
    public final int resourceMajor;
    public final int resourceMinor;
    public final boolean majorMinorForm;
    /** Jar entries (full paths) that exist among the paths the generator asked about. */
    public final Set<String> presentEntries;
    /** Keys of assets/minecraft/lang/en_us.json (so lang overrides only touch real keys). */
    public final Set<String> englishKeys;

    private ClientJarFacts(String versionId, int major, int minor, boolean majorMinorForm,
                           Set<String> present, Set<String> englishKeys) {
        this.versionId = versionId;
        this.resourceMajor = major;
        this.resourceMinor = minor;
        this.majorMinorForm = majorMinorForm;
        this.presentEntries = present;
        this.englishKeys = englishKeys;
    }

    public boolean has(String entry) {
        return presentEntries.contains(entry);
    }

    public boolean isSupported() {
        return resourceMajor >= BrandingPack.MIN_SUPPORTED_RESOURCE_FORMAT;
    }

    public String packFormatLabel() {
        return majorMinorForm ? resourceMajor + "." + resourceMinor : String.valueOf(resourceMajor);
    }

    /**
     * @param jar       the client jar (vanilla one even for Fabric/Forge profiles: assets live there)
     * @param candidates jar paths the generator may override; only their presence is recorded
     */
    public static ClientJarFacts read(File jar, Set<String> candidates) throws BrandingException {
        if (jar == null || !jar.isFile()) {
            throw new BrandingException(BrandingException.Reason.JAR_MISSING,
                    "client jar not found: " + jar);
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            ZipEntry versionEntry = zip.getEntry("version.json");
            if (versionEntry == null) {
                throw new BrandingException(BrandingException.Reason.INVALID_METADATA,
                        "version.json missing in " + jar.getName() + " (pre-1.14 jar?)");
            }
            JsonObject version = readJson(zip, versionEntry);
            String id = version.has("id") ? version.get("id").getAsString() : jar.getName();
            int major, minor = 0;
            boolean majorMinor;
            JsonElement pv = version.get("pack_version");
            if (pv == null || !pv.isJsonObject()) {
                throw new BrandingException(BrandingException.Reason.INVALID_METADATA,
                        "pack_version missing in version.json of " + id);
            }
            JsonObject packVersion = pv.getAsJsonObject();
            if (packVersion.has("resource_major")) {
                major = packVersion.get("resource_major").getAsInt();
                if (packVersion.has("resource_minor")) minor = packVersion.get("resource_minor").getAsInt();
                majorMinor = true;
            } else if (packVersion.has("resource")) {
                major = packVersion.get("resource").getAsInt();
                majorMinor = false;
            } else {
                throw new BrandingException(BrandingException.Reason.INVALID_METADATA,
                        "pack_version has neither 'resource' nor 'resource_major' (" + id + ")");
            }

            Set<String> present = new HashSet<>();
            for (String candidate : candidates) {
                if (zip.getEntry(candidate) != null) present.add(candidate);
            }
            Set<String> englishKeys = new HashSet<>();
            ZipEntry lang = zip.getEntry("assets/minecraft/lang/en_us.json");
            if (lang != null) {
                try {
                    JsonObject en = readJson(zip, lang);
                    for (java.util.Map.Entry<String, JsonElement> e : en.entrySet()) englishKeys.add(e.getKey());
                } catch (Exception e) {
                    // Lang overrides are optional polish: a broken lang file must not block the pack.
                    englishKeys.clear();
                }
            }
            return new ClientJarFacts(id, major, minor, majorMinor, present, englishKeys);
        } catch (BrandingException e) {
            throw e;
        } catch (Exception e) {
            throw new BrandingException(BrandingException.Reason.INVALID_METADATA,
                    "cannot read " + jar.getName() + ": " + e, e);
        } finally {
            if (zip != null) try { zip.close(); } catch (IOException ignored) {}
        }
    }

    private static JsonObject readJson(ZipFile zip, ZipEntry entry) throws IOException {
        InputStream in = zip.getInputStream(entry);
        try {
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } finally {
            in.close();
        }
    }
}
