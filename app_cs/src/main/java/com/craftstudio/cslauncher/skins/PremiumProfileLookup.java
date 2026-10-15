package com.craftstudio.cslauncher.skins;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.authenticator.accounts.SkinHeadRenderer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Looks up the official (premium) profile behind a player name: UUID, exact name casing, skin
 * (+ slim/classic) and cape.
 * <p>
 * Every step has fallbacks because the Mojang endpoints are rate limited (the session server
 * allows one request per profile per minute) and are blocked on some networks:
 * <ul>
 *   <li>name → UUID: api.mojang.com → api.minecraftservices.com → playerdb.co</li>
 *   <li>UUID → textures: sessionserver.mojang.com → playerdb.co (same signed property)</li>
 *   <li>texture bytes: textures.minecraft.net over HTTPS (the property says http://, which
 *       Android blocks as cleartext) → mc-heads.net / crafatar.com image mirrors</li>
 * </ul>
 */
public final class PremiumProfileLookup {
    private static final String TAG = "PremiumLookup";
    private static final int TIMEOUT = 9000;

    private PremiumProfileLookup() {}

    public enum Status { FOUND, NOT_FOUND, ERROR }

    public static final class Result {
        public Status status = Status.ERROR;
        public String uuid;          // dashed
        public String rawUuid;       // 32 hex chars
        public String name;
        @Nullable public byte[] skinPng;
        public boolean slim;
        @Nullable public byte[] capePng;
        /** Face rendered from the skin (for avatar tiles) */
        @Nullable public Bitmap face;

        @Nullable String pendingSkinUrl, pendingCapeUrl;

        public boolean found() { return status == Status.FOUND; }
    }

    /** Blocking. Never throws; look at {@link Result#status}. */
    @NonNull
    public static Result lookup(@NonNull String name) {
        Result result = new Result();
        try {
            if (!resolveName(name, result)) return result;
            result.status = Status.FOUND;
            fetchTextures(result);
            if (result.skinPng == null) {
                // Textures unreachable: image mirrors still know the skin
                result.skinPng = firstPng(
                        "https://mc-heads.net/skin/" + result.rawUuid,
                        "https://crafatar.com/skins/" + result.rawUuid,
                        "https://api.mineatar.io/skin/" + result.rawUuid);
                if (result.skinPng != null && !result.slim) {
                    Bitmap probe = BitmapFactory.decodeByteArray(result.skinPng, 0, result.skinPng.length);
                    if (probe != null) { result.slim = SkinTextures.looksSlim(probe); probe.recycle(); }
                }
                if (result.capePng == null) result.capePng = firstPng("https://crafatar.com/capes/" + result.rawUuid);
            }
            result.face = renderFace(result.skinPng);
        } catch (Exception e) {
            Log.w(TAG, "Lookup failed for " + name, e);
            if (result.status != Status.FOUND) result.status = Status.ERROR;
        }
        return result;
    }

    // ------------------------------------------------------------------ name → uuid

    /** @return true when the player exists; sets status NOT_FOUND / ERROR otherwise */
    private static boolean resolveName(@NonNull String name, @NonNull Result result) {
        boolean anyAnswer = false;
        for (String url : new String[]{
                "https://api.mojang.com/users/profiles/minecraft/" + name,
                "https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name}) {
            try {
                Http response = get(url);
                if (response.code == 200) {
                    JSONObject json = new JSONObject(response.text());
                    result.rawUuid = json.getString("id").replace("-", "");
                    result.uuid = dashed(result.rawUuid);
                    result.name = json.optString("name", name);
                    return true;
                }
                if (response.code == 404 || response.code == 204 || response.code == 400) {
                    anyAnswer = true; // a real "no such player"
                    break;
                }
            } catch (Exception e) {
                Log.w(TAG, "Name lookup failed via " + url + ": " + e);
            }
        }
        if (!anyAnswer) {
            // Mojang unreachable / rate limited: ask playerdb (its own cache + Mojang proxy)
            try {
                Http response = get("https://playerdb.co/api/player/minecraft/" + name);
                JSONObject json = new JSONObject(response.text());
                if (json.optBoolean("success", false)) {
                    JSONObject player = json.getJSONObject("data").getJSONObject("player");
                    result.rawUuid = player.getString("raw_id");
                    result.uuid = dashed(result.rawUuid);
                    result.name = player.optString("username", name);
                    cachePlayerdbTextures(player, result);
                    return true;
                }
                String code = json.optString("code", "");
                if (code.contains("not_found") || code.contains("invalid")) anyAnswer = true;
            } catch (Exception e) {
                Log.w(TAG, "playerdb lookup failed: " + e);
            }
        }
        result.status = anyAnswer ? Status.NOT_FOUND : Status.ERROR;
        return false;
    }

    // ------------------------------------------------------------------ uuid → textures

    private static void cachePlayerdbTextures(@NonNull JSONObject player, @NonNull Result result) {
        try {
            JSONArray properties = player.optJSONArray("properties");
            if (properties != null) parseProperties(properties, result);
            if (result.pendingSkinUrl == null) {
                String skin = player.optString("skin_texture", null);
                if (skin != null && !skin.isEmpty()) result.pendingSkinUrl = skin;
            }
        } catch (Exception ignored) {}
    }

    private static void fetchTextures(@NonNull Result result) {
        if (result.pendingSkinUrl == null) {
            try {
                Http response = get("https://sessionserver.mojang.com/session/minecraft/profile/" + result.rawUuid);
                if (response.code == 200) {
                    JSONArray properties = new JSONObject(response.text()).optJSONArray("properties");
                    if (properties != null) parseProperties(properties, result);
                }
            } catch (Exception e) {
                Log.w(TAG, "sessionserver failed: " + e);
            }
        }
        if (result.pendingSkinUrl == null) {
            try {
                Http response = get("https://playerdb.co/api/player/minecraft/" + result.rawUuid);
                JSONObject json = new JSONObject(response.text());
                if (json.optBoolean("success", false)) {
                    cachePlayerdbTextures(json.getJSONObject("data").getJSONObject("player"), result);
                }
            } catch (Exception e) {
                Log.w(TAG, "playerdb textures failed: " + e);
            }
        }
        String skinUrl = result.pendingSkinUrl, capeUrl = result.pendingCapeUrl;
        if (skinUrl != null) result.skinPng = firstPng(https(skinUrl));
        if (capeUrl != null) result.capePng = firstPng(https(capeUrl));
    }

    /** Reads the signed "textures" property (skin url + model, cape url) */
    private static void parseProperties(@NonNull JSONArray properties, @NonNull Result result) throws Exception {
        for (int i = 0; i < properties.length(); i++) {
            JSONObject property = properties.getJSONObject(i);
            if (!"textures".equals(property.optString("name"))) continue;
            String decoded = new String(Base64.decode(property.getString("value"), Base64.DEFAULT), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(decoded);
            if (result.name == null) result.name = root.optString("profileName", null);
            JSONObject textures = root.optJSONObject("textures");
            if (textures == null) return;
            JSONObject skin = textures.optJSONObject("SKIN");
            if (skin != null) {
                result.pendingSkinUrl = skin.optString("url", null);
                JSONObject metadata = skin.optJSONObject("metadata");
                result.slim = metadata != null && "slim".equals(metadata.optString("model"));
            }
            JSONObject cape = textures.optJSONObject("CAPE");
            if (cape != null) result.pendingCapeUrl = cape.optString("url", null);
            return;
        }
    }

    // ------------------------------------------------------------------ apply

    /**
     * Stores the skin and cape in the local libraries and selects them for the local account
     * with that name. Blocking (file I/O).
     */
    public static void applyToLocalAccount(@NonNull Result result, @NonNull String username) throws IOException {
        String key = LocalSkinStore.keyOfName(username);
        SkinLibrary.Entry skin = storeSkin(result);
        if (skin != null) SkinLibrary.setSelected(key, skin.id);
        LocalSkinStore.Cape cape = storeCape(result);
        if (cape != null) LocalSkinStore.setSelectedCapeByKey(key, cape.id);
        LocalSkinStore.updateFaceForKey(key);
    }

    /** Adds the skin to the shared skin library (null when the result has no usable skin) */
    @Nullable
    public static SkinLibrary.Entry storeSkin(@NonNull Result result) throws IOException {
        if (result.skinPng == null) return null;
        Bitmap bitmap = BitmapFactory.decodeByteArray(result.skinPng, 0, result.skinPng.length);
        if (bitmap == null) return null;
        byte[] png = result.skinPng;
        if (!SkinTextures.isValidSkinSize(bitmap.getWidth(), bitmap.getHeight())) {
            Bitmap normalized = SkinTextures.normalizeSkin(bitmap);
            png = encode(normalized);
            if (normalized != bitmap) normalized.recycle();
        }
        bitmap.recycle();
        return SkinLibrary.add(png, result.name + "'s skin", result.slim);
    }

    /** Adds the cape to the local cape library (null when the player wears none) */
    @Nullable
    public static LocalSkinStore.Cape storeCape(@NonNull Result result) throws IOException {
        if (result.capePng == null) return null;
        Bitmap bitmap = BitmapFactory.decodeByteArray(result.capePng, 0, result.capePng.length);
        if (bitmap == null) return null;
        byte[] png = result.capePng;
        if (!LocalSkinStore.isValidCapeSize(bitmap.getWidth(), bitmap.getHeight())) {
            Bitmap normalized = SkinTextures.normalizeCape(bitmap);
            png = encode(normalized);
            normalized.recycle();
        }
        bitmap.recycle();
        return LocalSkinStore.addCape(png, result.name + "'s cape");
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    private static Bitmap renderFace(@Nullable byte[] skinPng) {
        if (skinPng == null) return null;
        Bitmap raw = BitmapFactory.decodeByteArray(skinPng, 0, skinPng.length);
        if (raw == null) return null;
        try {
            Bitmap normalized = SkinTextures.normalizeSkin(raw);
            Bitmap face = new SkinHeadRenderer().render(128, normalized);
            if (normalized != raw) normalized.recycle();
            return face;
        } catch (RuntimeException e) {
            Log.w(TAG, "Face render failed", e);
            return null;
        } finally {
            raw.recycle();
        }
    }

    private static byte[] encode(@NonNull Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private static String https(@NonNull String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    private static String dashed(String id) {
        if (id == null || id.length() != 32) return id;
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
    }

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    /** First URL that answers 200 with a PNG */
    @Nullable
    private static byte[] firstPng(String... urls) {
        for (String url : urls) {
            try {
                Http response = get(url);
                if (response.code == 200 && response.body.length > 8
                        && response.body[0] == PNG_MAGIC[0] && response.body[1] == PNG_MAGIC[1]
                        && response.body[2] == PNG_MAGIC[2] && response.body[3] == PNG_MAGIC[3]) {
                    return response.body;
                }
            } catch (Exception e) {
                Log.w(TAG, "Image failed " + url + ": " + e);
            }
        }
        return null;
    }

    private static final class Http {
        int code;
        byte[] body = new byte[0];
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    @NonNull
    private static Http get(@NonNull String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "CSLauncherPlus/1.0 (Android)");
        connection.setRequestProperty("Accept", "application/json, image/png, */*");
        try {
            Http response = new Http();
            response.code = connection.getResponseCode();
            InputStream in = response.code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (in != null) {
                try (InputStream stream = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
                    response.body = out.toByteArray();
                }
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }
}
