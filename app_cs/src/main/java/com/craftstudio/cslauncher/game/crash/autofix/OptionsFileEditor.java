package com.craftstudio.cslauncher.game.crash.autofix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal, format preserving editor for Minecraft's options.txt. Only the two resource pack keys
 * are touched; every other line is written back byte for byte.
 */
public final class OptionsFileEditor {
    private OptionsFileEditor() {}

    public static final class Result {
        public final List<String> lines;
        public final List<String> removedEntries;
        Result(List<String> lines, List<String> removedEntries) { this.lines = lines; this.removedEntries = removedEntries; }
        public boolean changed() { return !removedEntries.isEmpty(); }
    }

    /**
     * Removes a pack from the enabled resource pack list.
     * @param packName file name (e.g. "Faithful.zip"), with or without the "file/" prefix. Empty = remove all file/ packs.
     */
    public static Result disableResourcePack(List<String> lines, String packName) {
        List<String> out = new ArrayList<>(lines.size());
        List<String> removed = new ArrayList<>();
        String want = normalise(packName);
        for (String line : lines) {
            if (line.startsWith("resourcePacks:") || line.startsWith("incompatibleResourcePacks:")) {
                int colon = line.indexOf(':');
                String key = line.substring(0, colon);
                List<String> entries = parseList(line.substring(colon + 1));
                List<String> kept = new ArrayList<>();
                for (String e : entries) {
                    if (shouldRemove(e, want)) { if (key.equals("resourcePacks")) removed.add(e); }
                    else kept.add(e);
                }
                out.add(key + ":" + toJsonList(kept));
            } else {
                out.add(line);
            }
        }
        return new Result(out, removed);
    }

    /** True while the pack (or any file/ pack when name is empty) is still enabled. */
    public static boolean isEnabled(List<String> lines, String packName) {
        String want = normalise(packName);
        for (String line : lines) {
            if (!line.startsWith("resourcePacks:")) continue;
            for (String e : parseList(line.substring("resourcePacks:".length()))) if (shouldRemove(e, want)) return true;
        }
        return false;
    }

    private static boolean shouldRemove(String entry, String want) {
        String e = entry;
        if (e.startsWith("file/")) e = e.substring(5);
        if (want.isEmpty()) return entry.startsWith("file/");
        String el = e.toLowerCase(Locale.ROOT);
        return el.equals(want) || el.equals(want + ".zip") || (want.endsWith(".zip") && el.equals(want.substring(0, want.length() - 4)));
    }

    private static String normalise(String packName) {
        if (packName == null) return "";
        String p = packName.trim();
        if (p.startsWith("file/")) p = p.substring(5);
        return p.toLowerCase(Locale.ROOT);
    }

    /** Parses the JSON array syntax Minecraft writes: ["vanilla","file/foo.zip"] (tolerant). */
    static List<String> parseList(String raw) {
        List<String> out = new ArrayList<>();
        String s = raw.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        StringBuilder cur = new StringBuilder();
        boolean inQ = false, any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQ) {
                if (c == '\\' && i + 1 < s.length()) { cur.append(s.charAt(++i)); }
                else if (c == '"') { inQ = false; }
                else cur.append(c);
            } else if (c == '"') { inQ = true; any = true; }
            else if (c == ',') { if (any) out.add(cur.toString()); cur.setLength(0); any = false; }
        }
        if (any) out.add(cur.toString());
        return out;
    }

    static String toJsonList(List<String> entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(entries.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
