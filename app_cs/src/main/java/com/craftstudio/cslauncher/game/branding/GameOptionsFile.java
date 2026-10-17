package com.craftstudio.cslauncher.game.branding;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Minimal, line-preserving editor for Minecraft's {@code options.txt}.
 * <p>
 * Unlike {@code MCOptionUtils} (which parses into a HashMap and rewrites the whole file in map
 * order) this keeps every untouched line byte-for-byte and in place, only replacing the lines
 * whose key the caller sets. It also parses the JSON list values ({@code resourcePacks},
 * {@code incompatibleResourcePacks}) properly instead of splitting on commas.
 * The write is atomic (temp file + rename).
 */
public final class GameOptionsFile {
    private final File mFile;
    private final List<String> mLines = new ArrayList<>();

    public GameOptionsFile(File file) throws IOException {
        mFile = file;
        if (file.isFile()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) mLines.add(line);
            } finally {
                reader.close();
            }
        }
    }

    public File getFile() {
        return mFile;
    }

    /** Raw value after the first colon, or null when the key is absent. */
    public String getRaw(String key) {
        int idx = indexOf(key);
        return idx < 0 ? null : mLines.get(idx).substring(key.length() + 1);
    }

    public boolean has(String key) {
        return indexOf(key) >= 0;
    }

    /** Sets (or appends) {@code key:value}. */
    public void setRaw(String key, String value) {
        int idx = indexOf(key);
        String line = key + ":" + value;
        if (idx < 0) mLines.add(line);
        else mLines.set(idx, line);
    }

    public void remove(String key) {
        int idx = indexOf(key);
        if (idx >= 0) mLines.remove(idx);
    }

    /** Parses a JSON string-list option; a missing or malformed value yields an empty list. */
    public List<String> getList(String key) {
        List<String> out = new ArrayList<>();
        String raw = getRaw(key);
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JsonElement parsed = JsonParser.parseString(raw.trim());
            if (!parsed.isJsonArray()) return out;
            for (JsonElement e : parsed.getAsJsonArray()) {
                if (e.isJsonPrimitive()) out.add(e.getAsString());
            }
        } catch (Exception ignored) {
            // Malformed list: treat as empty, the caller will write a well-formed one
        }
        return out;
    }

    public void setList(String key, List<String> values) {
        JsonArray arr = new JsonArray();
        for (String v : values) arr.add(v);
        setRaw(key, arr.toString());
    }

    /** Atomic write: {@code options.txt.cs-tmp} then rename over the original. */
    public void save() throws IOException {
        File parent = mFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        File tmp = new File(parent, mFile.getName() + ".cs-tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : mLines) sb.append(line).append('\n');
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        if (mFile.exists() && !mFile.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("cannot replace " + mFile);
        }
        if (!tmp.renameTo(mFile)) throw new IOException("cannot rename " + tmp + " to " + mFile);
    }

    private int indexOf(String key) {
        String prefix = key + ":";
        for (int i = 0; i < mLines.size(); i++) {
            if (mLines.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }
}
