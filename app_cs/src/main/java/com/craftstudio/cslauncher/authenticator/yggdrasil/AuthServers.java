package com.craftstudio.cslauncher.authenticator.yggdrasil;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.craftstudio.cslauncher.Tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The authentication servers the user added on the "Add account" page (HMCL / Zalith style:
 * add a server once by its URL, it then stays as its own sign-in option). Stored as
 * {@code auth_servers.json} in the launcher data dir.
 */
public final class AuthServers {
    private AuthServers() {}

    public static final class Entry {
        @NonNull public final String apiRoot;
        @NonNull public final String name;
        @Nullable public final String homepage;
        @Nullable public final String register;

        public Entry(@NonNull String apiRoot, @NonNull String name, @Nullable String homepage, @Nullable String register) {
            this.apiRoot = apiRoot;
            this.name = name;
            this.homepage = homepage;
            this.register = register;
        }

        /** Short host shown under the name, e.g. "littleskin.cn" */
        @NonNull public String host() {
            String s = apiRoot.replaceFirst("^https?://", "");
            int slash = s.indexOf('/');
            return slash > 0 ? s.substring(0, slash) : s;
        }

        @NonNull JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("apiRoot", apiRoot);
            o.addProperty("name", name);
            if (homepage != null) o.addProperty("homepage", homepage);
            if (register != null) o.addProperty("register", register);
            return o;
        }

        @Nullable static Entry fromJson(JsonObject o) {
            if (!o.has("apiRoot") || !o.has("name")) return null;
            return new Entry(o.get("apiRoot").getAsString(), o.get("name").getAsString(),
                    o.has("homepage") ? o.get("homepage").getAsString() : null,
                    o.has("register") ? o.get("register").getAsString() : null);
        }
    }

    private static File file() {
        return new File(Tools.DIR_DATA, "auth_servers.json");
    }

    @NonNull
    public static synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        File f = file();
        if (!f.isFile()) return out;
        try {
            JsonElement root = JsonParser.parseString(Tools.read(f.getAbsolutePath()));
            if (!root.isJsonArray()) return out;
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                Entry entry = Entry.fromJson(e.getAsJsonObject());
                if (entry != null) out.add(entry);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Adds (or updates, matched by API root) and returns the stored entry. */
    @NonNull
    public static synchronized Entry add(@NonNull YggdrasilClient.Server server) {
        Entry entry = new Entry(server.apiRoot, server.name, server.homepage, server.register);
        List<Entry> all = list();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).apiRoot.equalsIgnoreCase(entry.apiRoot)) {
                all.set(i, entry);
                save(all);
                return entry;
            }
        }
        all.add(entry);
        save(all);
        return entry;
    }

    public static synchronized void remove(@NonNull String apiRoot) {
        List<Entry> all = list();
        List<Entry> kept = new ArrayList<>();
        for (Entry e : all) if (!e.apiRoot.equalsIgnoreCase(apiRoot)) kept.add(e);
        save(kept);
    }

    @Nullable
    public static Entry find(@Nullable String apiRoot) {
        if (apiRoot == null) return null;
        for (Entry e : list()) if (e.apiRoot.equalsIgnoreCase(apiRoot)) return e;
        return null;
    }

    private static void save(List<Entry> all) {
        JsonArray array = new JsonArray();
        for (Entry e : all) array.add(e.toJson());
        try {
            File f = file();
            f.getParentFile().mkdirs();
            Tools.write(f.getAbsolutePath(), array.toString());
        } catch (IOException ignored) {
        }
    }
}
