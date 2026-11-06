package com.craftstudio.cslauncher.friends;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.fragments.InstanceVersionResolver;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.modding.modpacks.api.ApiHandler;
import com.kdt.mcgui.ProgressLayout;
import com.craftstudio.cslauncher.utils.DownloadUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cross-version "Join World" for Java profiles.
 *
 * <p>An invite carries the host's game version. When the profile that joins runs a different
 * version, the protocol translator for that direction is put into the profile's mods folder
 * before the game starts, like the built-in Via support of the original CS mod:
 * <ul>
 *   <li>Fabric / Quilt, joining an <b>older</b> host → ViaFabricPlus;</li>
 *   <li>Fabric / Quilt, joining a <b>newer</b> host → ViaFabric + ViaVersion + ViaBackwards;</li>
 *   <li>Forge / NeoForge, joining an <b>older</b> host → ViaForge;</li>
 *   <li>everything else (vanilla profile, Forge joining a newer host) → the join still happens,
 *       with a message explaining why it may be refused by the server.</li>
 * </ul>
 * Jars are fetched from Modrinth for the profile's exact game version and loader; the set that is
 * not needed for the current direction is parked in {@code mods/.cs-via-off/} because
 * ViaFabricPlus and ViaFabric exclude each other. Nothing is touched when the versions match.
 */
public final class CrossVersion {
    private static final String TAG = "CrossVersion";
    private static final String MODRINTH = "https://api.modrinth.com/v2";
    private static final String PARKED = ".cs-via-off";

    /** What happened, for the launcher's message. */
    public static final class Outcome {
        public final boolean changed;
        @Nullable public final String message;
        Outcome(boolean changed, @Nullable String message) { this.changed = changed; this.message = message; }
    }

    private CrossVersion() {}

    /** Modrinth project slug + the file-name prefixes that identify its jars in a mods folder. */
    private static final class Via {
        final String slug, label; final String[] prefixes;
        Via(String slug, String label, String... prefixes) { this.slug = slug; this.label = label; this.prefixes = prefixes; }
    }
    private static final Via VIAFABRICPLUS = new Via("viafabricplus", "ViaFabricPlus", "viafabricplus");
    private static final Via VIAFABRIC = new Via("viafabric", "ViaFabric", "viafabric-");
    private static final Via VIAVERSION = new Via("viaversion", "ViaVersion", "viaversion-");
    private static final Via VIABACKWARDS = new Via("viabackwards", "ViaBackwards", "viabackwards-");
    private static final Via VIAFORGE = new Via("viaforge", "ViaForge", "viaforge");

    /**
     * Blocking (network). Prepares {@code instance} to join a host running {@code hostVersion}.
     * Reports on {@link ProgressLayout#INSTALL_MODPACK} and clears it. Never throws.
     */
    @NonNull
    public static Outcome prepare(@NonNull android.content.Context context, @NonNull Instance instance, @NonNull String hostVersion) {
        try {
            InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(instance.getKey());
            String mine = info.mcVersion;
            if (mine == null || mine.isEmpty()) return new Outcome(false, null);
            int cmp = compare(mine, hostVersion);
            if (cmp == 0) return new Outcome(false, null);
            boolean fabric = "fabric".equals(info.loader) || "quilt".equals(info.loader);
            boolean forge = "forge".equals(info.loader) || "neoforge".equals(info.loader);
            File mods = new File(instance.getGameDirectory(), "mods");
            if (!mods.isDirectory() && !mods.mkdirs()) return new Outcome(false, context.getString(R.string.cross_version_failed, hostVersion));
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.cross_version_preparing, hostVersion);
            try {
                if (fabric && cmp > 0) {
                    // Newer client → older host: ViaFabricPlus alone
                    park(mods, VIAFABRIC, VIAVERSION, VIABACKWARDS);
                    boolean ok = ensure(mods, mine, "fabric", VIAFABRICPLUS);
                    return done(context, ok, hostVersion, VIAFABRICPLUS.label);
                }
                if (fabric && cmp < 0) {
                    // Older client → newer host: ViaFabric with ViaVersion + ViaBackwards
                    park(mods, VIAFABRICPLUS);
                    boolean ok = ensure(mods, mine, "fabric", VIAFABRIC) & ensure(mods, mine, "fabric", VIAVERSION) & ensure(mods, mine, "fabric", VIABACKWARDS);
                    return done(context, ok, hostVersion, VIAFABRIC.label + " + ViaBackwards");
                }
                if (forge && cmp > 0) {
                    boolean ok = ensure(mods, mine, info.loader, VIAFORGE);
                    return done(context, ok, hostVersion, VIAFORGE.label);
                }
                if (forge) return new Outcome(false, context.getString(R.string.cross_version_forge_newer, hostVersion));
                return new Outcome(false, context.getString(R.string.cross_version_vanilla, hostVersion));
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            }
        } catch (Throwable t) {
            Log.w(TAG, "prepare failed", t);
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            return new Outcome(false, context.getString(R.string.cross_version_failed, hostVersion));
        }
    }

    private static Outcome done(android.content.Context c, boolean ok, String hostVersion, String what) {
        return ok ? new Outcome(true, c.getString(R.string.cross_version_ready, what, hostVersion))
                  : new Outcome(false, c.getString(R.string.cross_version_failed, hostVersion));
    }

    // ------------------------------------------------------------------ versions

    /** "1.21.4", "26.3", "1.20" → compare numerically by component; snapshots compare as text. */
    static int compare(@NonNull String a, @NonNull String b) {
        int[] x = parts(a), y = parts(b);
        if (x == null || y == null) return a.trim().equalsIgnoreCase(b.trim()) ? 0 : (a.compareTo(b) < 0 ? -1 : 1);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    @Nullable
    private static int[] parts(String v) {
        String s = v.trim();
        if (!s.matches("\\d+(\\.\\d+)*")) return null;
        String[] bits = s.split("\\.");
        int[] out = new int[bits.length];
        for (int i = 0; i < bits.length; i++) {
            try { out[i] = Integer.parseInt(bits[i]); } catch (NumberFormatException e) { return null; }
        }
        return out;
    }

    // ------------------------------------------------------------------ jars

    private static boolean present(File mods, Via via) {
        File[] all = mods.listFiles();
        if (all == null) return false;
        for (File f : all) if (f.isFile() && matches(f.getName(), via)) return true;
        return false;
    }

    private static boolean matches(String name, Via via) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.endsWith(".jar")) return false;
        for (String p : via.prefixes) if (n.startsWith(p)) return true;
        return false;
    }

    /** Moves the given translators out of the way (they conflict with the ones being installed). */
    private static void park(File mods, Via... vias) {
        File[] all = mods.listFiles();
        if (all == null) return;
        File parked = new File(mods, PARKED);
        for (File f : all) {
            if (!f.isFile()) continue;
            for (Via v : vias) {
                if (matches(f.getName(), v)) {
                    if (!parked.isDirectory() && !parked.mkdirs()) break;
                    File to = new File(parked, f.getName());
                    if (to.exists()) to.delete();
                    if (!f.renameTo(to)) Log.w(TAG, "could not park " + f);
                }
            }
        }
    }

    /** Restores a parked jar if one is there, else downloads the newest compatible file. */
    private static boolean ensure(File mods, String mcVersion, String loader, Via via) {
        if (present(mods, via)) return true;
        File parked = new File(mods, PARKED);
        File[] saved = parked.listFiles();
        if (saved != null) {
            for (File f : saved) {
                if (matches(f.getName(), via) && f.renameTo(new File(mods, f.getName()))) return true;
            }
        }
        try {
            JsonObject file = pick(via.slug, mcVersion, loader);
            if (file == null && "quilt".equals(loader)) file = pick(via.slug, mcVersion, "fabric");
            if (file == null && "neoforge".equals(loader)) file = pick(via.slug, mcVersion, "forge");
            if (file == null) { Log.w(TAG, via.slug + ": nothing for " + mcVersion + "/" + loader); return false; }
            String name = sanitize(file.get("filename").getAsString());
            if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) name += ".jar";
            File out = new File(mods, name);
            File tmp = new File(mods, name + ".part");
            DownloadUtils.downloadFile(file.get("url").getAsString(), tmp);
            String sha1 = file.has("hashes") && file.getAsJsonObject("hashes").has("sha1") ? file.getAsJsonObject("hashes").get("sha1").getAsString() : null;
            if (sha1 != null && !sha1.equalsIgnoreCase(sha1(tmp))) { tmp.delete(); Log.w(TAG, via.slug + ": checksum mismatch"); return false; }
            if (out.exists()) out.delete();
            if (!tmp.renameTo(out)) { tmp.delete(); return false; }
            Log.i(TAG, "installed " + name);
            return true;
        } catch (IOException e) {
            Log.w(TAG, via.slug + ": " + e.getMessage());
            return false;
        }
    }

    /** The primary file of the newest Modrinth version matching game version + loader (release first). */
    @Nullable
    private static JsonObject pick(String slug, String mcVersion, String loader) throws IOException {
        String url = MODRINTH + "/project/" + slug + "/version?loaders=" + URLEncoder.encode("[\"" + loader + "\"]", "UTF-8")
                + "&game_versions=" + URLEncoder.encode("[\"" + mcVersion + "\"]", "UTF-8");
        String raw = ApiHandler.getRaw(url);
        if (raw == null || raw.isEmpty()) return null;
        JsonElement root = JsonParser.parseString(raw);
        if (!root.isJsonArray()) return null;
        JsonArray versions = root.getAsJsonArray();
        List<JsonObject> candidates = new ArrayList<>();
        for (JsonElement e : versions) if (e.isJsonObject()) candidates.add(e.getAsJsonObject());
        JsonObject best = null;
        for (JsonObject v : candidates) {
            if ("release".equals(optString(v, "version_type"))) { best = v; break; }
        }
        if (best == null && !candidates.isEmpty()) best = candidates.get(0); // Modrinth lists newest first
        if (best == null || !best.has("files")) return null;
        JsonArray files = best.getAsJsonArray("files");
        JsonObject primary = null;
        for (JsonElement e : files) {
            JsonObject f = e.getAsJsonObject();
            if (primary == null) primary = f;
            if (f.has("primary") && f.get("primary").getAsBoolean()) { primary = f; break; }
        }
        return primary;
    }

    @Nullable
    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._+\\-]", "_");
    }

    private static String sha1(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
