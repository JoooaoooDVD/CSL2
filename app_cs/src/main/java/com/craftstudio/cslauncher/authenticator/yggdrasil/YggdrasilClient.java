package com.craftstudio.cslauncher.authenticator.yggdrasil;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal client for a Yggdrasil-compatible authentication server (the protocol authlib-injector
 * servers speak: Ely.by, Blessing Skin, LittleSkin, self-hosted ones ...).
 * <ul>
 *   <li>{@link #resolveApiRoot}: follows the {@code X-Authlib-Injector-API-Location} header and
 *   reads the metadata, exactly like authlib-injector does on startup.</li>
 *   <li>{@code /authserver/authenticate} + {@code /authserver/refresh}</li>
 *   <li>{@link #profileTextures}: the player's SKIN / CAPE urls from the session server.</li>
 * </ul>
 * Everything is blocking; call from a worker thread.
 */
public final class YggdrasilClient {
    private static final String TAG = "Yggdrasil";
    private static final int TIMEOUT = 15000;

    private YggdrasilClient() {}

    /** Server metadata + the API root it actually lives on */
    public static final class Server {
        @NonNull public final String apiRoot;   // always ends with "/"
        @NonNull public final String name;      // meta.serverName or the host
        @Nullable public final String homepage; // links.homepage
        @Nullable public final String register; // links.register

        public Server(@NonNull String apiRoot, @NonNull String name, @Nullable String homepage, @Nullable String register) {
            this.apiRoot = apiRoot;
            this.name = name;
            this.homepage = homepage;
            this.register = register;
        }
    }

    public static final class Session {
        public String accessToken;
        public String clientToken;
        public String profileId;    // hyphenless or hyphenated, as the server sent it
        public String profileName;
        public int profileCount;    // available profiles (1 when the server picked one)
    }

    /** Thrown with the server's own error message ({@code errorMessage}) when it rejects a request */
    public static final class ServerException extends IOException {
        public final int httpCode;
        public final String error;

        ServerException(int httpCode, String error, String message) {
            super(message);
            this.httpCode = httpCode;
            this.error = error;
        }
    }

    /**
     * Turns what the user typed ("littleskin.cn", "https://authserver.ely.by", an "authlib-injector:"
     * prefixed URL ...) into the API root, following the ALI header once and checking the metadata.
     */
    @NonNull
    public static Server resolveApiRoot(@NonNull String typed) throws IOException {
        String url = typed.trim();
        if (url.startsWith("authlib-injector:")) url = url.substring("authlib-injector:".length());
        if (!url.contains("://")) url = "https://" + url;
        if (!url.endsWith("/")) url += "/";
        String root = url;
        JsonObject meta = null;
        for (int hop = 0; hop < 3 && meta == null; hop++) {
            HttpURLConnection conn = open(root, "GET");
            conn.connect();
            String ali = conn.getHeaderField("X-Authlib-Injector-API-Location");
            if (ali != null && !ali.isEmpty()) {
                String next = new URL(new URL(root), ali).toString();
                if (!next.endsWith("/")) next += "/";
                if (!next.equals(root)) {
                    conn.disconnect();
                    root = next;
                    continue;
                }
            }
            int code = conn.getResponseCode();
            String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            conn.disconnect();
            if (code >= 400) throw new ServerException(code, "Http", "HTTP " + code + " from " + root);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(body);
            } catch (RuntimeException e) {
                throw new IOException("Not a Yggdrasil server: " + root + " did not answer with JSON");
            }
            if (!parsed.isJsonObject()) throw new IOException("Not a Yggdrasil server: " + root);
            meta = parsed.getAsJsonObject();
        }
        if (meta == null) throw new IOException("Too many redirects from " + typed);
        String host = new URL(root).getHost();
        String name = host;
        String homepage = null, register = null;
        JsonObject m = meta.has("meta") && meta.get("meta").isJsonObject() ? meta.getAsJsonObject("meta") : null;
        if (m != null) {
            if (m.has("serverName") && m.get("serverName").isJsonPrimitive()) name = m.get("serverName").getAsString();
            if (m.has("links") && m.get("links").isJsonObject()) {
                JsonObject links = m.getAsJsonObject("links");
                if (links.has("homepage")) homepage = links.get("homepage").getAsString();
                if (links.has("register")) register = links.get("register").getAsString();
            }
        }
        return new Server(root, name, homepage, register);
    }

    @NonNull
    public static Session authenticate(@NonNull String apiRoot, @NonNull String username, @NonNull String password,
                                       @NonNull String clientToken) throws IOException {
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        JsonObject body = new JsonObject();
        body.add("agent", agent);
        body.addProperty("username", username);
        body.addProperty("password", password);
        body.addProperty("clientToken", clientToken);
        body.addProperty("requestUser", false);
        JsonObject response = postJson(apiRoot + "authserver/authenticate", body);
        return session(response, clientToken);
    }

    @NonNull
    public static Session refresh(@NonNull String apiRoot, @NonNull String accessToken, @NonNull String clientToken)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        body.addProperty("clientToken", clientToken);
        body.addProperty("requestUser", false);
        JsonObject response = postJson(apiRoot + "authserver/refresh", body);
        return session(response, clientToken);
    }

    /** True when the token is still valid (so no refresh is needed) */
    public static boolean validate(@NonNull String apiRoot, @NonNull String accessToken, @NonNull String clientToken) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("accessToken", accessToken);
            body.addProperty("clientToken", clientToken);
            HttpURLConnection conn = open(apiRoot + "authserver/validate", "POST");
            write(conn, body);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
        } catch (IOException e) {
            return false;
        }
    }

    public static final class Textures {
        @Nullable public String skinUrl;
        public boolean slim;
        @Nullable public String capeUrl;
    }

    /** SKIN / CAPE of a profile from {@code sessionserver/session/minecraft/profile/<uuid>} (null when unknown) */
    @Nullable
    public static Textures profileTextures(@NonNull String apiRoot, @NonNull String uuid) {
        try {
            HttpURLConnection conn = open(apiRoot + "sessionserver/session/minecraft/profile/" + uuid.replace("-", "") + "?unsigned=false", "GET");
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }
            String body = readAll(conn.getInputStream());
            conn.disconnect();
            JsonObject profile = JsonParser.parseString(body).getAsJsonObject();
            if (!profile.has("properties")) return null;
            for (JsonElement e : profile.getAsJsonArray("properties")) {
                JsonObject prop = e.getAsJsonObject();
                if (!"textures".equals(prop.get("name").getAsString())) continue;
                String value = new String(Base64.decode(prop.get("value").getAsString(), Base64.DEFAULT), StandardCharsets.UTF_8);
                JsonObject payload = JsonParser.parseString(value).getAsJsonObject();
                JsonObject textures = payload.has("textures") ? payload.getAsJsonObject("textures") : new JsonObject();
                Textures result = new Textures();
                if (textures.has("SKIN")) {
                    JsonObject skin = textures.getAsJsonObject("SKIN");
                    result.skinUrl = skin.has("url") ? skin.get("url").getAsString() : null;
                    result.slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                            && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
                }
                if (textures.has("CAPE")) {
                    JsonObject cape = textures.getAsJsonObject("CAPE");
                    result.capeUrl = cape.has("url") ? cape.get("url").getAsString() : null;
                }
                return result;
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "profile textures of " + uuid + " on " + apiRoot + " unavailable", e);
        }
        return null;
    }

    /** Downloads a texture (PNG bytes), null on any failure */
    @Nullable
    public static byte[] download(@NonNull String url) {
        try {
            HttpURLConnection conn = open(url, "GET");
            conn.connect();
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }
            byte[] bytes = readBytes(conn.getInputStream());
            conn.disconnect();
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- internals

    @NonNull
    private static Session session(@NonNull JsonObject response, @NonNull String clientToken) throws IOException {
        Session s = new Session();
        s.accessToken = response.has("accessToken") ? response.get("accessToken").getAsString() : null;
        s.clientToken = response.has("clientToken") ? response.get("clientToken").getAsString() : clientToken;
        if (s.accessToken == null || s.accessToken.isEmpty()) throw new IOException("The server sent no access token");
        JsonObject selected = response.has("selectedProfile") && response.get("selectedProfile").isJsonObject()
                ? response.getAsJsonObject("selectedProfile") : null;
        JsonArray available = response.has("availableProfiles") && response.get("availableProfiles").isJsonArray()
                ? response.getAsJsonArray("availableProfiles") : new JsonArray();
        s.profileCount = available.size();
        if (selected == null && available.size() > 0) selected = available.get(0).getAsJsonObject();
        if (selected == null) throw new ServerException(200, "NoProfile", "This account has no game profile on the server yet");
        s.profileId = selected.get("id").getAsString();
        s.profileName = selected.get("name").getAsString();
        return s;
    }

    @NonNull
    private static JsonObject postJson(@NonNull String url, @NonNull JsonObject body) throws IOException {
        HttpURLConnection conn = open(url, "POST");
        write(conn, body);
        int code = conn.getResponseCode();
        String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();
        JsonObject json = null;
        try {
            JsonElement parsed = JsonParser.parseString(text == null ? "" : text);
            if (parsed.isJsonObject()) json = parsed.getAsJsonObject();
        } catch (RuntimeException ignored) {}
        if (code >= 400 || json == null) {
            String error = json != null && json.has("error") ? json.get("error").getAsString() : "Http";
            String message = json != null && json.has("errorMessage") ? json.get("errorMessage").getAsString()
                    : "HTTP " + code + " from " + url;
            throw new ServerException(code, error, message);
        }
        return json;
    }

    @NonNull
    private static HttpURLConnection open(@NonNull String url, @NonNull String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CS-Launcher-Plus");
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static void write(@NonNull HttpURLConnection conn, @NonNull JsonObject body) throws IOException {
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
    }

    @Nullable
    private static String readAll(@Nullable InputStream in) throws IOException {
        if (in == null) return null;
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    @NonNull
    private static byte[] readBytes(@NonNull InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new IOException("Response too large");
            }
            return out.toByteArray();
        }
    }

    /** Hyphenated lower-case UUID from either form */
    @NonNull
    public static String hyphenate(@NonNull String uuid) {
        String u = uuid.replace("-", "").toLowerCase(Locale.ROOT);
        if (u.length() != 32) return uuid;
        return u.substring(0, 8) + "-" + u.substring(8, 12) + "-" + u.substring(12, 16) + "-" + u.substring(16, 20) + "-" + u.substring(20);
    }
}
