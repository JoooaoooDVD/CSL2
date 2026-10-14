package com.craftstudio.cslauncher.modding.modpacks;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.modding.modpacks.api.ApiHandler;
import com.craftstudio.cslauncher.modding.modpacks.models.Constants;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;
import com.craftstudio.cslauncher.utils.Murmur2;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Snapshot of what is installed in one content folder of the selected profile, used by the
 * library cards to show Install / Update / Uninstall. Built off the main thread with
 * {@link #build}: local files are hashed (results are cached per file), then resolved in bulk
 * against Modrinth (version_files + version_files/update) and, when a key is available,
 * CurseForge (fingerprints). Jars that neither site knows are still matched by the mod id
 * written inside them, so manually added mods are recognised by slug.
 */
public final class InstalledContentIndex {
    private static final String TAG = "InstalledContentIndex";
    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final String CURSEFORGE_API = "https://api.curseforge.com/v1";

    public static final class Entry {
        public final File file;
        /** Modrinth version id or CurseForge file id of the installed file, may be null */
        public final String versionId;
        public boolean updateAvailable;

        public Entry(File file, String versionId) {
            this.file = file;
            this.versionId = versionId;
        }
    }

    private final Map<String, Entry> mModrinth = new HashMap<>();
    private final Map<String, Entry> mCurseforge = new HashMap<>();
    private final Map<String, Entry> mModIds = new HashMap<>();
    public final ContentType contentType;

    private InstalledContentIndex(ContentType contentType) {
        this.contentType = contentType;
    }

    /** @return an empty index, used before the first scan finishes */
    public static InstalledContentIndex empty(ContentType type) {
        return new InstalledContentIndex(type);
    }

    @Nullable
    public Entry find(@NonNull ModItem item) {
        Entry entry = null;
        if (item.apiSource == Constants.SOURCE_MODRINTH) {
            entry = mModrinth.get(item.id);
        } else if (item.apiSource == Constants.SOURCE_CURSEFORGE) {
            entry = mCurseforge.get(item.id);
        }
        if (entry == null && item.slug != null) {
            entry = mModIds.get(item.slug.toLowerCase(Locale.ROOT));
        }
        if (entry != null && !entry.file.exists()) return null;
        return entry;
    }

    /** Forgets a file after it was deleted, so every card pointing at it flips back to Install */
    public void remove(@NonNull File file) {
        removeFile(mModrinth, file);
        removeFile(mCurseforge, file);
        removeFile(mModIds, file);
    }

    private static void removeFile(Map<String, Entry> map, File file) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : map.entrySet()) {
            if (entry.getValue().file.getAbsolutePath().equals(file.getAbsolutePath())) keys.add(entry.getKey());
        }
        for (String key : keys) map.remove(key);
    }

    /** Whether a CurseForge result has a newer file than the installed one for this profile */
    public static boolean curseforgeHasNewer(@NonNull ModItem item, @NonNull Entry entry,
                                             @Nullable String mcVersion, @Nullable String loader) {
        if (item.cfIndexFileIds == null || entry.versionId == null || mcVersion == null) return false;
        long installed;
        try {
            installed = Long.parseLong(entry.versionId);
        } catch (NumberFormatException e) {
            return false;
        }
        long newest = 0;
        for (int i = 0; i < item.cfIndexFileIds.length; i++) {
            if (!mcVersion.equals(item.cfIndexGameVersions[i])) continue;
            String indexLoader = item.cfIndexLoaders[i];
            if (loader != null && !loader.isEmpty() && indexLoader != null
                    && !LibraryCatalog.loaderMatches(new String[]{indexLoader}, loader)) continue;
            newest = Math.max(newest, item.cfIndexFileIds[i]);
        }
        return newest > installed;
    }

    // ─── Building ───────────────────────────────────────────────────────────

    /** World whose datapacks folder the DATAPACK content type currently targets */
    @Nullable
    private static volatile File sDatapackWorld;

    /** Points every DATAPACK install/index at the given world (null clears it) */
    public static void setDatapackWorld(@Nullable File worldDir) {
        sDatapackWorld = worldDir;
    }

    @Nullable
    public static File getDatapackWorld() {
        return sDatapackWorld;
    }

    @NonNull
    public static File contentDir(@NonNull ContentType type) {
        if (type == ContentType.DATAPACK) {
            File world = sDatapackWorld;
            if (world != null) return new File(world, "datapacks");
        }
        try {
            Instance instance = Instances.loadInstance(Instances.getSelectedInstanceKey());
            if (instance != null) return new File(instance.getGameDirectory(), type.folderName);
        } catch (Exception ignored) {}
        return new File(Tools.DIR_GAME_NEW, type.folderName);
    }

    @NonNull
    public static File[] listContentFiles(@NonNull ContentType type) {
        File dir = contentDir(type);
        File[] files = dir.listFiles(f -> f.isFile() &&
                (f.getName().endsWith(type.fileExtension) || f.getName().endsWith(type.fileExtension + ".disabled")));
        return files == null ? new File[0] : files;
    }

    /**
     * Scans the folder of the given content type. Blocking, network bound, call from a worker.
     * @param mcVersion profile Minecraft version for the update check, null skips it
     * @param loader profile loader, "" when vanilla
     * @param curseforgeKey null when CurseForge is disabled
     */
    @NonNull
    public static InstalledContentIndex build(@NonNull ContentType type, @Nullable String mcVersion,
                                              @Nullable String loader, @Nullable String curseforgeKey) {
        InstalledContentIndex index = new InstalledContentIndex(type);
        File[] files = listContentFiles(type);
        if (files.length == 0) return index;

        Map<String, File> bySha1 = new HashMap<>();
        for (File file : files) {
            String sha1 = sha1(file);
            if (sha1 != null) bySha1.put(sha1, file);
            if (Thread.currentThread().isInterrupted()) return index;
        }

        Map<String, String> currentVersionByHash = new HashMap<>();
        Map<String, String> currentDateByHash = new HashMap<>();
        if (!bySha1.isEmpty()) {
            try {
                JsonObject body = new JsonObject();
                JsonArray hashes = new JsonArray();
                for (String hash : bySha1.keySet()) hashes.add(hash);
                body.add("hashes", hashes);
                body.addProperty("algorithm", "sha1");
                String raw = ApiHandler.postRaw(jsonHeaders(), MODRINTH_API + "/version_files", body.toString());
                if (raw != null) {
                    JsonObject response = JsonParser.parseString(raw).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> item : response.entrySet()) {
                        if (!item.getValue().isJsonObject()) continue;
                        JsonObject version = item.getValue().getAsJsonObject();
                        File file = bySha1.get(item.getKey());
                        if (file == null) continue;
                        String projectId = string(version, "project_id");
                        String versionId = string(version, "id");
                        if (projectId == null) continue;
                        index.mModrinth.put(projectId, new Entry(file, versionId));
                        currentVersionByHash.put(item.getKey(), versionId);
                        currentDateByHash.put(item.getKey(), string(version, "date_published"));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Modrinth lookup failed: " + e.getMessage());
            }
        }

        if (mcVersion != null && !currentVersionByHash.isEmpty()) {
            checkModrinthUpdates(index, type, mcVersion, loader, currentVersionByHash, currentDateByHash, bySha1);
        }

        if (curseforgeKey != null && !curseforgeKey.isEmpty()) {
            resolveCurseforge(index, files, curseforgeKey);
        }

        if (type == ContentType.MOD) {
            for (File file : files) {
                String modId = readModId(file);
                if (modId != null) index.mModIds.put(modId.toLowerCase(Locale.ROOT), new Entry(file, null));
            }
        }
        return index;
    }

    private static void checkModrinthUpdates(InstalledContentIndex index, ContentType type, String mcVersion,
                                             @Nullable String loader, Map<String, String> currentVersionByHash,
                                             Map<String, String> currentDateByHash, Map<String, File> bySha1) {
        try {
            JsonObject body = new JsonObject();
            JsonArray hashes = new JsonArray();
            for (String hash : currentVersionByHash.keySet()) hashes.add(hash);
            body.add("hashes", hashes);
            body.addProperty("algorithm", "sha1");
            JsonArray loaders = new JsonArray();
            switch (type) {
                case MOD:
                    if (loader == null || loader.isEmpty()) return;
                    loaders.add(loader);
                    if ("quilt".equals(loader)) loaders.add("fabric");
                    break;
                case RESOURCE_PACK:
                    loaders.add("minecraft");
                    break;
                case DATAPACK:
                    loaders.add("datapack");
                    break;
                case SHADER_PACK:
                    loaders.add("iris");
                    loaders.add("optifine");
                    loaders.add("canvas");
                    loaders.add("vanilla");
                    break;
            }
            body.add("loaders", loaders);
            JsonArray gameVersions = new JsonArray();
            gameVersions.add(mcVersion);
            body.add("game_versions", gameVersions);

            String raw = ApiHandler.postRaw(jsonHeaders(), MODRINTH_API + "/version_files/update", body.toString());
            if (raw == null) return;
            JsonObject response = JsonParser.parseString(raw).getAsJsonObject();
            for (Map.Entry<String, JsonElement> item : response.entrySet()) {
                if (!item.getValue().isJsonObject()) continue;
                JsonObject latest = item.getValue().getAsJsonObject();
                String latestId = string(latest, "id");
                String currentId = currentVersionByHash.get(item.getKey());
                if (latestId == null || latestId.equals(currentId)) continue;
                long latestDate = LibraryCatalog.parseIsoDate(string(latest, "date_published"));
                long currentDate = LibraryCatalog.parseIsoDate(currentDateByHash.get(item.getKey()));
                if (latestDate >= 0 && currentDate >= 0 && latestDate < currentDate) continue;
                String projectId = string(latest, "project_id");
                Entry entry = projectId == null ? null : index.mModrinth.get(projectId);
                File file = bySha1.get(item.getKey());
                if (entry != null && file != null && entry.file.equals(file)) entry.updateAvailable = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Modrinth update check failed: " + e.getMessage());
        }
    }

    private static void resolveCurseforge(InstalledContentIndex index, File[] files, String key) {
        try {
            Map<Long, File> byFingerprint = new HashMap<>();
            JsonArray fingerprints = new JsonArray();
            for (File file : files) {
                Long fingerprint = murmur(file);
                if (fingerprint == null) continue;
                byFingerprint.put(fingerprint, file);
                fingerprints.add(fingerprint);
            }
            if (fingerprints.size() == 0) return;
            JsonObject body = new JsonObject();
            body.add("fingerprints", fingerprints);
            Map<String, String> headers = jsonHeaders();
            headers.put("x-api-key", key);
            String raw = ApiHandler.postRaw(headers, CURSEFORGE_API + "/fingerprints", body.toString());
            if (raw == null) return;
            JsonObject response = JsonParser.parseString(raw).getAsJsonObject();
            JsonElement dataElement = response.get("data");
            if (dataElement == null || !dataElement.isJsonObject()) return;
            JsonElement matchesElement = dataElement.getAsJsonObject().get("exactMatches");
            if (matchesElement == null || !matchesElement.isJsonArray()) return;
            for (JsonElement matchElement : matchesElement.getAsJsonArray()) {
                if (!matchElement.isJsonObject()) continue;
                JsonObject match = matchElement.getAsJsonObject();
                JsonElement fileElement = match.get("file");
                if (fileElement == null || !fileElement.isJsonObject()) continue;
                JsonObject cfFile = fileElement.getAsJsonObject();
                JsonElement fingerprint = cfFile.get("fileFingerprint");
                JsonElement modId = cfFile.get("modId");
                JsonElement fileId = cfFile.get("id");
                if (fingerprint == null || modId == null || fingerprint.isJsonNull() || modId.isJsonNull()) continue;
                File local = byFingerprint.get(fingerprint.getAsLong());
                if (local == null) continue;
                index.mCurseforge.put(modId.getAsString(),
                        new Entry(local, fileId == null || fileId.isJsonNull() ? null : fileId.getAsString()));
            }
        } catch (Exception e) {
            Log.w(TAG, "CurseForge lookup failed: " + e.getMessage());
        }
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        return headers;
    }

    @Nullable
    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsString();
    }

    // ─── Hashing with a small cache, rescanning on every visit stays cheap ──

    private static final class HashRecord {
        final long length;
        final long modified;
        String sha1;
        Long murmur;

        HashRecord(long length, long modified) {
            this.length = length;
            this.modified = modified;
        }
    }

    private static final Map<String, HashRecord> sHashCache = new HashMap<>();

    private static HashRecord record(File file) {
        synchronized (sHashCache) {
            HashRecord record = sHashCache.get(file.getAbsolutePath());
            if (record == null || record.length != file.length() || record.modified != file.lastModified()) {
                record = new HashRecord(file.length(), file.lastModified());
                sHashCache.put(file.getAbsolutePath(), record);
            }
            return record;
        }
    }

    @Nullable
    public static String sha1(@NonNull File file) {
        HashRecord record = record(file);
        if (record.sha1 != null) return record.sha1;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[16384];
            try (FileInputStream input = new FileInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest()) builder.append(String.format("%02x", b));
            record.sha1 = builder.toString();
            return record.sha1;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Long murmur(@NonNull File file) {
        HashRecord record = record(file);
        if (record.murmur != null) return record.murmur;
        try {
            record.murmur = Murmur2.hashFile(file);
            return record.murmur;
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Mod id from the jar metadata ───────────────────────────────────────

    @Nullable
    public static String readModId(@NonNull File jarFile) {
        try (ZipFile zip = new ZipFile(jarFile)) {
            String content = readEntry(zip, "fabric.mod.json");
            if (content != null) {
                String id = jsonId(content, null);
                if (id != null) return id;
            }
            content = readEntry(zip, "quilt.mod.json");
            if (content != null) {
                String id = jsonId(content, "quilt_loader");
                if (id != null) return id;
            }
            for (String toml : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                content = readEntry(zip, toml);
                if (content == null) continue;
                Matcher matcher = Pattern.compile("modId\\s*=\\s*\"([^\"]*)\"").matcher(content);
                if (matcher.find() && !matcher.group(1).trim().isEmpty()) return matcher.group(1).trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Nullable
    private static String jsonId(String content, @Nullable String wrapper) {
        try {
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (wrapper != null) {
                JsonElement inner = root.get(wrapper);
                if (inner == null || !inner.isJsonObject()) return null;
                root = inner.getAsJsonObject();
            }
            String id = string(root, "id");
            return id == null || id.trim().isEmpty() ? null : id.trim();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String readEntry(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return null;
        try (InputStream input = zip.getInputStream(entry)) {
            return Tools.read(input);
        } catch (Exception e) {
            return null;
        }
    }
}
