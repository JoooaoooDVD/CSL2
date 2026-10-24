package com.craftstudio.cslauncher.game.crash.autofix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.modding.modpacks.api.ApiHandler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the download for a missing dependency on Modrinth (the only source used; every file is
 * checksum verified by the caller and its declared mod id must match the requested one).
 */
public final class ModrinthDependencyResolver {
    private static final String API = "https://api.modrinth.com/v2";

    /** Mod ids whose Modrinth slug differs from the id the loader prints. */
    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        ALIASES.put("fabric", "fabric-api");
        ALIASES.put("fabric-api", "fabric-api");
        ALIASES.put("cloth-config2", "cloth-config");
        ALIASES.put("cloth_config", "cloth-config");
        ALIASES.put("forgeconfigapiport", "forge-config-api-port");
        ALIASES.put("yet_another_config_lib_v3", "yacl");
        ALIASES.put("yet-another-config-lib", "yacl");
        ALIASES.put("owo", "owo-lib");
        ALIASES.put("puzzleslib", "puzzles-lib");
        ALIASES.put("kotlinforforge", "kotlin-for-forge");
        ALIASES.put("resourcefullib", "resourceful-lib");
        ALIASES.put("resourcefulconfig", "resourceful-config");
        ALIASES.put("supermartijn642corelib", "supermartijn642s-core-lib");
        ALIASES.put("supermartijn642configlib", "supermartijn642s-config-lib");
        ALIASES.put("cardinal-components", "cardinal-components-api");
        ALIASES.put("cardinal-components-base", "cardinal-components-api");
        ALIASES.put("fabric-language-kotlin", "fabric-language-kotlin");
        ALIASES.put("architectury", "architectury-api");
        ALIASES.put("mixinextras", "mixinextras");
        ALIASES.put("libipn", "libipn");
        ALIASES.put("player-animator", "playeranimator");
        ALIASES.put("bclib", "bclib");
        ALIASES.put("modmenu", "modmenu");
        ALIASES.put("geckolib", "geckolib");
        ALIASES.put("cristellib", "cristel-lib");
        ALIASES.put("creativecore", "creativecore");
        ALIASES.put("balm", "balm");
        ALIASES.put("collective", "collective");
        ALIASES.put("citadel", "citadel");
        ALIASES.put("curios", "curios");
        ALIASES.put("trinkets", "trinkets");
        ALIASES.put("ferritecore", "ferrite-core");
        ALIASES.put("indium", "indium");
        ALIASES.put("sodium", "sodium");
        ALIASES.put("iris", "iris");
        ALIASES.put("lithium", "lithium");
        ALIASES.put("midnightlib", "midnightlib");
        ALIASES.put("terrablender", "terrablender");
        ALIASES.put("konkrete", "konkrete");
        ALIASES.put("bookshelf", "bookshelf-lib");
        ALIASES.put("jei", "jei");
        ALIASES.put("emi", "emi");
        ALIASES.put("rei", "rei");
        ALIASES.put("roughlyenoughitems", "rei");
        ALIASES.put("patchouli", "patchouli");
        ALIASES.put("caelus", "caelus");
        ALIASES.put("placebo", "placebo");
        ALIASES.put("moonlight", "moonlight");
        ALIASES.put("lambdynlights", "lambdynamiclights");
        ALIASES.put("fabric-permissions-api-v0", "fabric-permissions-api");
    }

    public static final class Candidate {
        public final String projectSlug;
        public final String versionNumber;
        public final String fileName;
        public final String url;
        public final String sha1;
        public final long size;

        Candidate(String projectSlug, String versionNumber, String fileName, String url, String sha1, long size) {
            this.projectSlug = projectSlug; this.versionNumber = versionNumber; this.fileName = fileName;
            this.url = url; this.sha1 = sha1; this.size = size;
        }
    }

    private ModrinthDependencyResolver() {}

    /** Slugs to try, most likely first. */
    @NonNull
    public static List<String> slugCandidates(String modId) {
        List<String> out = new ArrayList<>();
        if (modId == null) return out;
        String id = modId.trim().toLowerCase(Locale.ROOT);
        String alias = ALIASES.get(id);
        if (alias != null) out.add(alias);
        if (!out.contains(id)) out.add(id);
        String dashed = id.replace('_', '-');
        if (!out.contains(dashed)) out.add(dashed);
        return out;
    }

    /**
     * Resolves the newest version of the dependency matching the profile's loader and game version.
     * @param requirement version requirement text from the log ("", "0.100.0", "[1.2,)"), used as a lower bound when parseable
     * @return null when no acceptable file exists; throws on network trouble
     */
    @Nullable
    public static Candidate resolve(String modId, String loader, String mcVersion, String requirement) throws IOException {
        if (loader == null || loader.isEmpty() || mcVersion == null || mcVersion.isEmpty()) return null;
        String minVersion = lowerBound(requirement);
        for (String slug : slugCandidates(modId)) {
            JsonObject project = getObject(API + "/project/" + enc(slug));
            if (project == null) continue;
            String type = str(project, "project_type");
            if (!"mod".equals(type)) continue;
            String id = str(project, "id");
            if (id == null) continue;
            String q = API + "/project/" + enc(id) + "/version?loaders=" + enc("[\"" + loader + "\"]")
                    + "&game_versions=" + enc("[\"" + mcVersion + "\"]");
            JsonArray versions = getArray(q);
            if (versions == null) continue;
            for (JsonElement el : versions) {
                if (!el.isJsonObject()) continue;
                JsonObject v = el.getAsJsonObject();
                String number = str(v, "version_number");
                if (minVersion != null && number != null && compareVersions(number, minVersion) < 0) continue;
                JsonArray files = v.has("files") && v.get("files").isJsonArray() ? v.getAsJsonArray("files") : null;
                if (files == null || files.size() == 0) continue;
                JsonObject pick = null;
                for (JsonElement fe : files) {
                    JsonObject f = fe.getAsJsonObject();
                    if (f.has("primary") && f.get("primary").getAsBoolean()) { pick = f; break; }
                }
                if (pick == null) pick = files.get(0).getAsJsonObject();
                String url = str(pick, "url");
                String fileName = str(pick, "filename");
                String sha1 = pick.has("hashes") && pick.get("hashes").isJsonObject() ? str(pick.getAsJsonObject("hashes"), "sha1") : null;
                long size = pick.has("size") ? pick.get("size").getAsLong() : -1;
                if (url == null || fileName == null || sha1 == null) continue;
                if (!url.startsWith("https://cdn.modrinth.com/")) continue; // trusted CDN only
                if (!PathGuard.isPlainFileName(fileName) || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                return new Candidate(slug, number == null ? "" : number, fileName, url, sha1, size);
            }
        }
        return null;
    }

    // ---- version helpers (lenient, numeric segments) -----------------------------------------

    /** Extracts a minimum version from "0.100.0", ">=0.100.0", "[0.100.0,)", "version 0.100 or later". */
    @Nullable
    static String lowerBound(String requirement) {
        if (requirement == null) return null;
        String r = requirement.trim();
        if (r.isEmpty() || r.equals("*")) return null;
        if (r.startsWith("[") || r.startsWith("(")) {
            int comma = r.indexOf(',');
            String lo = (comma > 0 ? r.substring(1, comma) : r.substring(1, r.length() - 1)).trim();
            return lo.isEmpty() ? null : lo;
        }
        if (r.startsWith(">=")) return r.substring(2).trim();
        if (r.startsWith(">")) return r.substring(1).trim();
        if (r.startsWith("^") || r.startsWith("~")) return r.substring(1).trim();
        if (r.startsWith("<") || r.startsWith("=")) return null;
        return r;
    }

    /** Compares dotted numeric versions ("0.100.1+1.21" vs "0.99"); non-numeric tails are ignored. */
    static int compareVersions(String a, String b) {
        int[] x = parts(a), y = parts(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int[] parts(String v) {
        String core = v.trim();
        int cut = core.length();
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            if (!(Character.isDigit(c) || c == '.')) { cut = i; break; }
        }
        core = core.substring(0, cut);
        if (core.isEmpty()) return new int[0];
        String[] s = core.split("\\.");
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            try { out[i] = s[i].isEmpty() ? 0 : Integer.parseInt(s[i]); } catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    // ---- http ------------------------------------------------------------------------------

    @Nullable
    private static JsonObject getObject(String url) throws IOException {
        String raw = ApiHandler.getRaw(url);
        if (raw == null) {
            int status = ApiHandler.getLastStatus();
            if (status == 404) return null;
            throw new IOException("Modrinth request failed (" + status + ")");
        }
        try {
            JsonElement e = JsonParser.parseString(raw);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static JsonArray getArray(String url) throws IOException {
        String raw = ApiHandler.getRaw(url);
        if (raw == null) {
            int status = ApiHandler.getLastStatus();
            if (status == 404) return null;
            throw new IOException("Modrinth request failed (" + status + ")");
        }
        try {
            JsonElement e = JsonParser.parseString(raw);
            return e.isJsonArray() ? e.getAsJsonArray() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { return s; }
    }
}
