package com.craftstudio.cslauncher.skins;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.authenticator.accounts.SkinHeadRenderer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Looks up the official (premium) profile behind a player name: UUID, exact name casing, skin
 * (+ slim/classic) and cape. Used by the offline-account page so a local account made with a
 * premium name wears that player's skin and cape automatically.
 */
public final class PremiumProfileLookup {
    private static final String TAG = "PremiumLookup";
    private static final String NAME_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final int TIMEOUT = 8000;

    private PremiumProfileLookup() {}

    public static final class Result {
        public String uuid;
        public String name;
        @Nullable public byte[] skinPng;
        public boolean slim;
        @Nullable public byte[] capePng;
        /** Face rendered from the skin (for the avatar tile) */
        @Nullable public Bitmap face;
    }

    /** Blocking. Returns null when no premium player has that name (or the network failed). */
    @Nullable
    public static Result lookup(@NonNull String name) {
        try {
            String body = get(NAME_URL + name);
            if (body == null) return null;
            JSONObject profile = new JSONObject(body);
            Result result = new Result();
            result.uuid = dashed(profile.getString("id"));
            result.name = profile.optString("name", name);

            String full = get(PROFILE_URL + profile.getString("id"));
            if (full == null) return result;
            JSONObject props = new JSONObject(full);
            org.json.JSONArray properties = props.optJSONArray("properties");
            if (properties == null) return result;
            for (int i = 0; i < properties.length(); i++) {
                JSONObject property = properties.getJSONObject(i);
                if (!"textures".equals(property.optString("name"))) continue;
                String decoded = new String(Base64.decode(property.getString("value"), Base64.DEFAULT), StandardCharsets.UTF_8);
                JSONObject textures = new JSONObject(decoded).optJSONObject("textures");
                if (textures == null) break;
                JSONObject skin = textures.optJSONObject("SKIN");
                if (skin != null) {
                    result.skinPng = getBytes(skin.getString("url"));
                    JSONObject metadata = skin.optJSONObject("metadata");
                    result.slim = metadata != null && "slim".equals(metadata.optString("model"));
                }
                JSONObject cape = textures.optJSONObject("CAPE");
                if (cape != null) result.capePng = getBytes(cape.getString("url"));
                break;
            }
            if (result.skinPng != null) {
                Bitmap raw = BitmapFactory.decodeByteArray(result.skinPng, 0, result.skinPng.length);
                if (raw != null) {
                    try {
                        Bitmap normalized = SkinTextures.normalizeSkin(raw);
                        result.face = new SkinHeadRenderer().render(128, normalized);
                        if (normalized != raw) normalized.recycle();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Face render failed", e);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Lookup failed for " + name, e);
            return null;
        }
    }

    /**
     * Stores the skin and cape in the local libraries and selects them for the local account
     * with that name. Blocking (file I/O).
     */
    public static void applyToLocalAccount(@NonNull Result result, @NonNull String username) throws IOException {
        String key = LocalSkinStore.keyOfName(username);
        if (result.skinPng != null) {
            SkinLibrary.Entry entry = SkinLibrary.add(result.skinPng, result.name + "'s skin", result.slim);
            SkinLibrary.setSelected(key, entry.id);
        }
        if (result.capePng != null) {
            Bitmap cape = BitmapFactory.decodeByteArray(result.capePng, 0, result.capePng.length);
            if (cape != null && LocalSkinStore.isValidCapeSize(cape.getWidth(), cape.getHeight())) {
                LocalSkinStore.Cape stored = LocalSkinStore.addCape(result.capePng, result.name + "'s cape");
                LocalSkinStore.setSelectedCapeByKey(key, stored.id);
            } else if (cape != null) {
                // Mojang capes are 64x32 but some are 22x17 or padded: normalize to a standard sheet
                Bitmap normalized = SkinTextures.normalizeCape(cape);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                normalized.compress(Bitmap.CompressFormat.PNG, 100, out);
                LocalSkinStore.Cape stored = LocalSkinStore.addCape(out.toByteArray(), result.name + "'s cape");
                LocalSkinStore.setSelectedCapeByKey(key, stored.id);
            }
            if (cape != null) cape.recycle();
        }
        LocalSkinStore.updateFaceForKey(key);
    }

    private static String dashed(String id) {
        if (id == null || id.length() != 32) return id;
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
    }

    @Nullable
    private static String get(@NonNull String url) throws IOException {
        byte[] bytes = getBytes(url);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    @Nullable
    private static byte[] getBytes(@NonNull String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setRequestProperty("User-Agent", "CSLauncher");
        try {
            int code = connection.getResponseCode();
            if (code != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
