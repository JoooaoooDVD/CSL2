package com.craftstudio.cslauncher.friends;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.authenticator.accounts.SkinHeadRenderer;
import com.craftstudio.cslauncher.skins.SkinTextures;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Skins shared between CS Launcher players through the friends backend.
 *
 * <ul>
 *   <li>{@link #publish}: the local account's skin/cape goes up with the CS account token and
 *       the in-game name, so other CS Launcher players can resolve it.</li>
 *   <li>{@link #lookupByUuid}: the local session server asks here when the game requests the
 *       profile of another player (offline-mode UUIDs are derived from the name, the backend
 *       keeps that mapping), so friends in a hosted world or on any offline server show their
 *       launcher skin.</li>
 *   <li>{@link #head}: the head icon of a player by name (friends list, inbox), Steve when unknown.</li>
 * </ul>
 */
public final class RemoteSkins {
    private static final String TAG = "RemoteSkins";
    private static final long NEGATIVE_TTL = 10 * 60 * 1000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cs-skins");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A resolved player skin. */
    public static final class Skin {
        public String name;
        @Nullable public byte[] skinPng, capePng;
        /** SHA-256 of the PNGs as published (public texture URLs), or null. */
        @Nullable public String skinHash, capeHash;
        public boolean slim;
    }

    /** Public, cacheable texture URL for a published hash (served by the cs-skin edge function). */
    @NonNull
    public static String textureUrl(@NonNull String hash) {
        return "https://ygteavpkursyeyfthlxm.supabase.co/functions/v1/cs-skin/" + hash + ".png";
    }

    /** Host name of {@link #textureUrl} (authlib-injector texture domain whitelist). */
    public static final String TEXTURE_DOMAIN = "ygteavpkursyeyfthlxm.supabase.co";

    @Nullable private static volatile String sMySkinHash, sMyCapeHash;

    /** SHA-256 of the local player's skin as last published (null when not published / default). */
    @Nullable public static String mySkinHash() { return sMySkinHash; }
    @Nullable public static String myCapeHash() { return sMyCapeHash; }

    public interface HeadListener { void onHeadsChanged(); }

    private static final Map<String, Skin> sByUuid = new HashMap<>();
    private static final Map<String, Long> sMissUuid = new HashMap<>();
    private static final Map<String, Bitmap> sHeads = new HashMap<>();
    private static final Set<String> sHeadPending = new HashSet<>();
    private static final Set<String> sHeadMissing = new HashSet<>();
    private static final List<HeadListener> sHeadListeners = new ArrayList<>();
    private static String sLastPublished;

    private RemoteSkins() {}

    // ---------------------------------------------------------------- publish

    /** Uploads the player's skin/cape once per content change (no-op without a CS account). */
    public static void publish(@NonNull String mcName, @Nullable byte[] skinPng, boolean slim, @Nullable byte[] capePng) {
        publish(mcName, null, skinPng, slim, capePng);
    }

    /**
     * Same, with the UUID the game plays under so other players' session lookups by that UUID
     * resolve too (the integrated server hands the host's real profile id to guests).
     * The upload is synchronous when called off the main thread so the hashes are known before
     * the game starts; on the main thread it runs in the background.
     */
    public static void publish(@NonNull String mcName, @Nullable String uuid, @Nullable byte[] skinPng, boolean slim, @Nullable byte[] capePng) {
        final String token = CsAccount.token();
        if (token == null) { sMySkinHash = null; sMyCapeHash = null; return; }
        sMySkinHash = skinPng == null ? null : sha256(skinPng);
        sMyCapeHash = capePng == null ? null : sha256(capePng);
        final String skin = skinPng == null ? null : Base64.encodeToString(skinPng, Base64.NO_WRAP);
        final String cape = capePng == null ? null : Base64.encodeToString(capePng, Base64.NO_WRAP);
        final String fingerprint = mcName + "|" + (skin == null ? 0 : skin.hashCode()) + "|" + (cape == null ? 0 : cape.hashCode()) + "|" + slim;
        synchronized (RemoteSkins.class) {
            if (fingerprint.equals(sLastPublished)) return;
            sLastPublished = fingerprint;
        }
        Runnable upload = () -> {
            try {
                JSONObject a = new JSONObject();
                a.put("p_token", token);
                a.put("p_skin", skin == null ? JSONObject.NULL : skin);
                a.put("p_model", slim ? "slim" : "classic");
                a.put("p_cape", cape == null ? JSONObject.NULL : cape);
                a.put("p_mc_name", mcName);
                a.put("p_uuid", uuid == null ? JSONObject.NULL : uuid.replace("-", "").toLowerCase(Locale.ROOT));
                FriendsApi.call("cs_set_skin3", a);
                Log.i(TAG, "published skin for " + mcName);
            } catch (IOException | JSONException e) {
                synchronized (RemoteSkins.class) { sLastPublished = null; }
                Log.w(TAG, "publish failed: " + e.getMessage());
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) IO.execute(upload); else upload.run();
    }

    @NonNull
    static String sha256(@NonNull byte[] data) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- lookup by name (hasJoined)

    private static final Map<String, Skin> sByName = new HashMap<>();
    private static final Map<String, Long> sMissName = new HashMap<>();

    /** Blocking: the published skin of a CS Launcher player by in-game name, or null. Cached. */
    @Nullable
    public static Skin lookupByName(@NonNull String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return null;
        synchronized (RemoteSkins.class) {
            Skin s = sByName.get(key);
            if (s != null) return s;
            Long miss = sMissName.get(key);
            if (miss != null && System.currentTimeMillis() - miss < NEGATIVE_TTL) return null;
        }
        Skin s = null;
        try {
            JSONObject r = FriendsApi.call("cs_skin_by_name", new JSONObject().put("p_name", name.trim()));
            if (r.has("name")) s = parse(r);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "lookup name " + key + ": " + e.getMessage());
        }
        synchronized (RemoteSkins.class) {
            if (s != null) sByName.put(key, s);
            else sMissName.put(key, System.currentTimeMillis());
        }
        return s;
    }

    // ---------------------------------------------------------------- lookup by uuid (session server)

    /** Blocking (network, ≤ ~12 s): the skin behind an offline-mode UUID, or null. Cached. */
    @Nullable
    public static Skin lookupByUuid(@NonNull String rawUuid) {
        String key = rawUuid.replace("-", "").toLowerCase(Locale.ROOT);
        synchronized (RemoteSkins.class) {
            Skin s = sByUuid.get(key);
            if (s != null) return s;
            Long miss = sMissUuid.get(key);
            if (miss != null && System.currentTimeMillis() - miss < NEGATIVE_TTL) return null;
        }
        Skin s = null;
        try {
            JSONObject a = new JSONObject().put("p_uuid", key);
            JSONObject r = FriendsApi.call("cs_skin_by_uuid", a);
            if (r.has("name")) s = parse(r);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "lookup " + key + ": " + e.getMessage());
        }
        synchronized (RemoteSkins.class) {
            if (s != null) sByUuid.put(key, s);
            else sMissUuid.put(key, System.currentTimeMillis());
        }
        return s;
    }

    @Nullable
    private static Skin parse(JSONObject o) {
        Skin s = new Skin();
        s.name = o.optString("name");
        s.slim = "slim".equals(o.optString("model"));
        s.skinPng = decode(o.isNull("skin") ? null : o.optString("skin", null));
        s.capePng = decode(o.isNull("cape") ? null : o.optString("cape", null));
        s.skinHash = o.isNull("skin_hash") ? null : o.optString("skin_hash", null);
        s.capeHash = o.isNull("cape_hash") ? null : o.optString("cape_hash", null);
        if (s.skinPng == null && s.capePng == null) return null;
        return s;
    }

    @Nullable
    private static byte[] decode(@Nullable String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try { return Base64.decode(b64, Base64.DEFAULT); } catch (IllegalArgumentException e) { return null; }
    }

    // ---------------------------------------------------------------- heads (UI)

    public static void addHeadListener(HeadListener l) { synchronized (sHeadListeners) { if (!sHeadListeners.contains(l)) sHeadListeners.add(l); } }
    public static void removeHeadListener(HeadListener l) { synchronized (sHeadListeners) { sHeadListeners.remove(l); } }

    /** The cached head of {@code name}, or null (a fetch starts; listeners fire when it lands). */
    @Nullable
    public static Bitmap head(@Nullable String name) {
        if (name == null || name.isEmpty()) return null;
        String key = name.toLowerCase(Locale.ROOT);
        synchronized (RemoteSkins.class) {
            Bitmap b = sHeads.get(key);
            if (b != null) return b;
            if (sHeadMissing.contains(key) || sHeadPending.contains(key)) return null;
            sHeadPending.add(key);
        }
        IO.execute(() -> fetchHeads());
        return null;
    }

    /** Drops the cached heads so changed skins show up (called on each friends sync). */
    public static void invalidateMissing() {
        synchronized (RemoteSkins.class) { sHeadMissing.clear(); }
    }

    private static void fetchHeads() {
        List<String> names;
        synchronized (RemoteSkins.class) {
            if (sHeadPending.isEmpty()) return;
            names = new ArrayList<>(sHeadPending);
        }
        Map<String, Bitmap> got = new HashMap<>();
        try {
            JSONArray arr = new JSONArray();
            for (String n : names) arr.put(n);
            JSONObject r = FriendsApi.call("cs_get_skins", new JSONObject().put("p_names", arr));
            Object v = r.opt("value");
            JSONArray rows = v instanceof JSONArray ? (JSONArray) v : null;
            if (rows == null && r.has("value")) rows = new JSONArray(String.valueOf(v));
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject o = rows.getJSONObject(i);
                byte[] png = decode(o.isNull("skin") ? null : o.optString("skin", null));
                if (png == null) continue;
                Bitmap raw = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (raw == null) continue;
                try {
                    Bitmap normalized = SkinTextures.normalizeSkin(raw);
                    Bitmap head = new SkinHeadRenderer().render(64, normalized);
                    if (head != null) got.put(o.optString("name").toLowerCase(Locale.ROOT), head);
                } catch (RuntimeException ignored) {}
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "heads: " + e.getMessage());
        }
        synchronized (RemoteSkins.class) {
            for (String n : names) {
                sHeadPending.remove(n);
                Bitmap b = got.get(n);
                if (b != null) sHeads.put(n, b); else sHeadMissing.add(n);
            }
        }
        MAIN.post(() -> {
            List<HeadListener> ls;
            synchronized (sHeadListeners) { ls = new ArrayList<>(sHeadListeners); }
            for (HeadListener l : ls) l.onHeadsChanged();
        });
    }
}
