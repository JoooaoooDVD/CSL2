package com.craftstudio.cslauncher.game.resources;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny per-profile record of which Modrinth project was installed to which file name
 * (gameDir/cs_resource_index.json). It is only a hint: {@link #installedFile} always checks
 * that the file really exists on disk, so manual deletions are reflected on reopen.
 */
final class ResourceInstallIndex {
    private static final String TAG = "ResourceIndex";
    private static final String FILE_NAME = "cs_resource_index.json";
    private static final Object LOCK = new Object();

    private ResourceInstallIndex() {}

    private static File indexFile(@NonNull File gameDir) {
        return new File(gameDir, FILE_NAME);
    }

    @NonNull
    private static Map<String, String> read(@NonNull File gameDir) {
        File f = indexFile(gameDir);
        if (!f.isFile()) return new HashMap<>();
        try {
            String json = org.apache.commons.io.FileUtils.readFileToString(f, StandardCharsets.UTF_8);
            Map<String, String> map = new Gson().fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            return map == null ? new HashMap<>() : map;
        } catch (Exception e) {
            Log.w(TAG, "Unreadable resource index, starting fresh: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /** Blocking (disk). key = type + project id, value = "resourcepacks/Name.zip". */
    static void record(@NonNull File gameDir, @NonNull String key, @NonNull String relativePath) {
        synchronized (LOCK) {
            Map<String, String> map = read(gameDir);
            map.put(key, relativePath);
            try (Writer w = new FileWriter(indexFile(gameDir))) {
                new Gson().toJson(map, w);
            } catch (Exception e) {
                Log.w(TAG, "Could not write the resource index: " + e.getMessage());
            }
        }
    }

    /** Blocking (disk). Returns the installed file only if it still exists. */
    @Nullable
    static File installedFile(@NonNull File gameDir, @NonNull String key) {
        String rel;
        synchronized (LOCK) {
            rel = read(gameDir).get(key);
        }
        if (rel == null) return null;
        File f = new File(gameDir, rel);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** Blocking (disk). Snapshot of every recorded key whose file is still present. */
    @NonNull
    static Map<String, File> presentFiles(@NonNull File gameDir) {
        Map<String, String> map;
        synchronized (LOCK) {
            map = read(gameDir);
        }
        Map<String, File> out = new HashMap<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            File f = new File(gameDir, e.getValue());
            if (f.isFile() && f.length() > 0) out.put(e.getKey(), f);
        }
        return out;
    }
}
