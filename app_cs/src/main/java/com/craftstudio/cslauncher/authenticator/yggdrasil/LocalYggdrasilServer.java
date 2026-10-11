package com.craftstudio.cslauncher.authenticator.yggdrasil;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny Yggdrasil (Mojang auth/session API) server that runs inside the game process on
 * {@code 127.0.0.1}. authlib-injector ({@code -javaagent}) points the game at it, so a local
 * (offline) account gets its custom skin and cape in game without any online account:
 * <ol>
 *     <li>the game asks for the player's profile → we answer with a {@code textures} property
 *     signed with our own RSA key (authlib-injector trusts the key published in the metadata);</li>
 *     <li>the texture URLs point back at this server, which serves the PNG files.</li>
 * </ol>
 * Implements the authlib-injector Yggdrasil server specification (metadata, auth, session,
 * profile and name lookup endpoints) for a single player.
 */
public final class LocalYggdrasilServer {
    private static final String TAG = "LocalYggdrasil";
    private static final int KEY_SIZE = 2048;
    private static final int MAX_BODY = 64 * 1024;

    @Nullable private static LocalYggdrasilServer sRunning;

    private final ServerSocket mServer;
    private final ExecutorService mWorkers;
    private final KeyPair mKeys;
    private final String mName;
    private final String mUuid;         // unhyphenated
    private final boolean mSlim;
    private final Map<String, byte[]> mTextures = new HashMap<>(); // hash -> png
    @Nullable private final String mSkinHash;
    @Nullable private final String mCapeHash;
    private final String mAccessToken = UUID.randomUUID().toString().replace("-", "");

    /**
     * Starts the server (only one per process; a previous one is stopped).
     * @param keyDir   directory that keeps the RSA signing key between launches
     * @param name     player name
     * @param uuid     player UUID, with or without hyphens
     * @param skinPng  skin texture or null for the default skin
     * @param slim     slim (Alex) arms
     * @param capePng  cape texture or null
     */
    @NonNull
    public static synchronized LocalYggdrasilServer start(@NonNull File keyDir, @NonNull String name, @NonNull String uuid,
                                                          @Nullable byte[] skinPng, boolean slim,
                                                          @Nullable byte[] capePng) throws IOException, GeneralSecurityException {
        if (sRunning != null) sRunning.stop();
        sRunning = new LocalYggdrasilServer(loadOrCreateKeys(keyDir), name, uuid, skinPng, slim, capePng);
        return sRunning;
    }

    /** UUID that offline-mode servers give to a player name (also what the spec recommends) */
    @NonNull
    public static String offlineUuid(@NonNull String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private LocalYggdrasilServer(@NonNull KeyPair keys, @NonNull String name, @NonNull String uuid,
                                 @Nullable byte[] skinPng, boolean slim, @Nullable byte[] capePng) throws IOException, GeneralSecurityException {
        mKeys = keys;
        mName = name;
        mUuid = uuid.replace("-", "").toLowerCase(Locale.ROOT);
        mSlim = slim;
        mSkinHash = skinPng == null ? null : putTexture(skinPng);
        mCapeHash = capePng == null ? null : putTexture(capePng);
        mServer = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        mWorkers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "LocalYggdrasil");
            thread.setDaemon(true);
            return thread;
        });
        Thread acceptor = new Thread(this::acceptLoop, "LocalYggdrasil-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        Log.i(TAG, "Local skin server on port " + mServer.getLocalPort() + " for " + mName
                + " (skin=" + (mSkinHash != null) + ", cape=" + (mCapeHash != null) + ")");
    }

    public int getPort() {
        return mServer.getLocalPort();
    }

    /** API root given to authlib-injector */
    @NonNull
    public String getRootUrl() {
        return "http://127.0.0.1:" + getPort() + "/";
    }

    /** Base64 metadata for {@code -Dauthlibinjector.yggdrasil.prefetched} (skips one request) */
    @NonNull
    public String getPrefetchedMetadata() {
        return Base64.encodeToString(metadata().toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public void stop() {
        try {
            mServer.close();
        } catch (IOException ignored) {}
        mWorkers.shutdownNow();
        synchronized (LocalYggdrasilServer.class) {
            if (sRunning == this) sRunning = null;
        }
    }

    // ------------------------------------------------------------------ keys

    @NonNull
    private static KeyPair loadOrCreateKeys(@NonNull File dir) throws IOException, GeneralSecurityException {
        File privateFile = new File(dir, "signing_key.der");
        File publicFile = new File(dir, "signing_key.pub.der");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (privateFile.isFile() && publicFile.isFile()) {
            try {
                PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(readFile(privateFile)));
                PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(readFile(publicFile)));
                return new KeyPair(publicKey, privateKey);
            } catch (GeneralSecurityException | IOException e) {
                Log.w(TAG, "Stored signing key unreadable, creating a new one", e);
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE);
        KeyPair pair = generator.generateKeyPair();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        writeFile(privateFile, pair.getPrivate().getEncoded());
        writeFile(publicFile, pair.getPublic().getEncoded());
        return pair;
    }

    @NonNull
    private String publicKeyPem() {
        String body = Base64.encodeToString(mKeys.getPublic().getEncoded(), Base64.NO_WRAP);
        StringBuilder pem = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < body.length(); i += 64) {
            pem.append(body, i, Math.min(body.length(), i + 64)).append('\n');
        }
        return pem.append("-----END PUBLIC KEY-----\n").toString();
    }

    @NonNull
    private String sign(@NonNull String value) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(mKeys.getPrivate());
            signature.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(signature.sign(), Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    private String putTexture(@NonNull byte[] png) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(png);
        StringBuilder hash = new StringBuilder();
        for (byte b : digest) hash.append(String.format(Locale.ROOT, "%02x", b));
        String key = hash.toString();
        mTextures.put(key, png);
        return key;
    }

    // ------------------------------------------------------------------ documents

    @NonNull
    private JsonObject metadata() {
        JsonObject meta = new JsonObject();
        meta.addProperty("serverName", "CS Launcher Plus");
        meta.addProperty("implementationName", "CSLauncher-LocalYggdrasil");
        meta.addProperty("implementationVersion", "1.0");
        meta.addProperty("feature.non_email_login", true);
        JsonObject root = new JsonObject();
        root.add("meta", meta);
        JsonArray domains = new JsonArray();
        domains.add("127.0.0.1");
        domains.add("localhost");
        root.add("skinDomains", domains);
        root.addProperty("signaturePublickey", publicKeyPem());
        return root;
    }

    @NonNull
    private JsonObject profile(boolean withTextures, boolean signed) {
        JsonObject profile = new JsonObject();
        profile.addProperty("id", mUuid);
        profile.addProperty("name", mName);
        if (!withTextures) return profile;

        JsonObject textures = new JsonObject();
        String base = "http://127.0.0.1:" + getPort() + "/textures/";
        if (mSkinHash != null) {
            JsonObject skin = new JsonObject();
            skin.addProperty("url", base + mSkinHash);
            if (mSlim) {
                JsonObject metadata = new JsonObject();
                metadata.addProperty("model", "slim");
                skin.add("metadata", metadata);
            }
            textures.add("SKIN", skin);
        }
        if (mCapeHash != null) {
            JsonObject cape = new JsonObject();
            cape.addProperty("url", base + mCapeHash);
            textures.add("CAPE", cape);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("timestamp", System.currentTimeMillis());
        payload.addProperty("profileId", mUuid);
        payload.addProperty("profileName", mName);
        payload.add("textures", textures);
        String value = Base64.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        JsonObject property = new JsonObject();
        property.addProperty("name", "textures");
        property.addProperty("value", value);
        if (signed) property.addProperty("signature", sign(value));
        JsonArray properties = new JsonArray();
        properties.add(property);
        profile.add("properties", properties);
        return profile;
    }

    @NonNull
    private JsonObject authResponse(@Nullable JsonObject request) {
        JsonObject response = new JsonObject();
        response.addProperty("accessToken", mAccessToken);
        String clientToken = request != null && request.has("clientToken") && !request.get("clientToken").isJsonNull()
                ? request.get("clientToken").getAsString() : UUID.randomUUID().toString().replace("-", "");
        response.addProperty("clientToken", clientToken);
        JsonArray available = new JsonArray();
        available.add(profile(false, false));
        response.add("availableProfiles", available);
        response.add("selectedProfile", profile(false, false));
        JsonObject user = new JsonObject();
        user.addProperty("id", mUuid);
        user.add("properties", new JsonArray());
        response.add("user", user);
        return response;
    }

    private boolean isOurName(@Nullable String name) {
        return name != null && name.equalsIgnoreCase(mName);
    }

    // ------------------------------------------------------------------ HTTP

    private void acceptLoop() {
        while (!mServer.isClosed()) {
            try {
                Socket socket = mServer.accept();
                mWorkers.execute(() -> handle(socket));
            } catch (IOException e) {
                if (!mServer.isClosed()) Log.w(TAG, "accept failed", e);
            } catch (RuntimeException e) {
                Log.w(TAG, "worker rejected", e);
            }
        }
    }

    private static final class Request {
        String method;
        String path;
        final Map<String, String> query = new HashMap<>();
        byte[] body = new byte[0];

        @Nullable
        JsonElement json() {
            try {
                return JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static final class Response {
        int code = 200;
        String type = "application/json; charset=utf-8";
        byte[] body = new byte[0];

        static Response json(@NonNull JsonElement element) {
            Response response = new Response();
            response.body = element.toString().getBytes(StandardCharsets.UTF_8);
            return response;
        }

        static Response noContent() {
            Response response = new Response();
            response.code = 204;
            return response;
        }

        static Response error(int code, String error, String message) {
            JsonObject object = new JsonObject();
            object.addProperty("error", error);
            object.addProperty("errorMessage", message);
            Response response = json(object);
            response.code = code;
            return response;
        }
    }

    private void handle(@NonNull Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            Request request = readRequest(in);
            Response response;
            try {
                response = request == null ? Response.error(400, "BadRequest", "Malformed request") : route(request);
            } catch (RuntimeException e) {
                Log.w(TAG, "request failed", e);
                response = Response.error(500, "InternalError", String.valueOf(e.getMessage()));
            }
            writeResponse(s.getOutputStream(), response, request != null && "HEAD".equals(request.method));
        } catch (IOException e) {
            Log.d(TAG, "connection closed: " + e.getMessage());
        }
    }

    @NonNull
    private Response route(@NonNull Request r) {
        String path = r.path;
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        boolean get = "GET".equals(r.method) || "HEAD".equals(r.method);
        boolean post = "POST".equals(r.method);

        if (get && (path.equals("/") || path.isEmpty())) return Response.json(metadata());

        if (get && path.startsWith("/textures/")) {
            byte[] png = mTextures.get(path.substring("/textures/".length()));
            if (png == null) return Response.error(404, "NotFound", "No such texture");
            Response response = new Response();
            response.type = "image/png";
            response.body = png;
            return response;
        }

        // --- auth server (the launcher already gives the token, these only have to agree)
        if (post && (path.equals("/authserver/authenticate") || path.equals("/authserver/refresh"))) {
            JsonElement body = r.json();
            return Response.json(authResponse(body != null && body.isJsonObject() ? body.getAsJsonObject() : null));
        }
        if (post && (path.equals("/authserver/validate") || path.equals("/authserver/invalidate")
                || path.equals("/authserver/signout"))) {
            return Response.noContent();
        }

        // --- session server
        if (post && path.equals("/sessionserver/session/minecraft/join")) return Response.noContent();
        if (get && path.equals("/sessionserver/session/minecraft/hasJoined")) {
            return isOurName(r.query.get("username")) ? Response.json(profile(true, true)) : Response.noContent();
        }
        if (get && path.startsWith("/sessionserver/session/minecraft/profile/")) {
            String uuid = path.substring("/sessionserver/session/minecraft/profile/".length()).replace("-", "");
            if (!mUuid.equalsIgnoreCase(uuid)) return Response.noContent();
            boolean signed = "false".equalsIgnoreCase(r.query.get("unsigned"));
            return Response.json(profile(true, signed));
        }

        // --- name lookups (api.mojang.com / api.minecraftservices.com namespaces)
        if (post && (path.equals("/api/profiles/minecraft")
                || path.equals("/minecraftservices/minecraft/profile/lookup/bulk/byname"))) {
            JsonArray result = new JsonArray();
            JsonElement body = r.json();
            if (body != null && body.isJsonArray()) {
                for (JsonElement element : body.getAsJsonArray()) {
                    if (element.isJsonPrimitive() && isOurName(element.getAsString())) {
                        result.add(profile(false, false));
                        break;
                    }
                }
            }
            return Response.json(result);
        }
        if (get && (path.startsWith("/api/users/profiles/minecraft/")
                || path.startsWith("/minecraftservices/minecraft/profile/lookup/name/"))) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return isOurName(name) ? Response.json(profile(false, false)) : Response.noContent();
        }

        // --- minecraftservices extras some versions ask for
        if (get && path.equals("/minecraftservices/player/attributes")) {
            JsonObject privileges = new JsonObject();
            privileges.add("onlineChat", enabled(true));
            privileges.add("multiplayerServer", enabled(true));
            privileges.add("multiplayerRealms", enabled(false));
            privileges.add("telemetry", enabled(false));
            JsonObject filter = new JsonObject();
            filter.addProperty("profanityFilterOn", false);
            JsonObject root = new JsonObject();
            root.add("privileges", privileges);
            root.add("profanityFilterPreferences", filter);
            return Response.json(root);
        }
        if (get && path.equals("/minecraftservices/privacy/blocklist")) {
            JsonObject root = new JsonObject();
            root.add("blockedProfiles", new JsonArray());
            return Response.json(root);
        }

        return Response.error(404, "NotFound", "Not supported by the local skin server");
    }

    @NonNull
    private static JsonObject enabled(boolean value) {
        JsonObject object = new JsonObject();
        object.addProperty("enabled", value);
        return object;
    }

    @Nullable
    private static Request readRequest(@NonNull InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        Request request = new Request();
        request.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        int question = target.indexOf('?');
        String rawPath = question >= 0 ? target.substring(0, question) : target;
        if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
            int slash = rawPath.indexOf('/', rawPath.indexOf("//") + 2);
            rawPath = slash >= 0 ? rawPath.substring(slash) : "/";
        }
        request.path = URLDecoder.decode(rawPath, "UTF-8");
        if (question >= 0) {
            for (String pair : target.substring(question + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int equals = pair.indexOf('=');
                String key = equals >= 0 ? pair.substring(0, equals) : pair;
                String value = equals >= 0 ? pair.substring(equals + 1) : "";
                request.query.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            }
        }
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        if (contentLength > 0) {
            if (contentLength > MAX_BODY) return null;
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            request.body = body;
        }
        return request;
    }

    @Nullable
    private static String readLine(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 8192) throw new IOException("Header line too long");
        }
        if (c == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    private static void writeResponse(@NonNull OutputStream out, @NonNull Response response, boolean head) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(response.code).append(' ').append(reason(response.code)).append("\r\n");
        if (response.code != 204) {
            header.append("Content-Type: ").append(response.type).append("\r\n");
            header.append("Content-Length: ").append(response.body.length).append("\r\n");
        }
        header.append("X-Content-Type-Options: nosniff\r\n");
        header.append("Connection: close\r\n\r\n");
        out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        if (response.code != 204 && !head) out.write(response.body);
        out.flush();
    }

    @NonNull
    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            default: return "Error";
        }
    }

    private static byte[] readFile(@NonNull File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static void writeFile(@NonNull File file, @NonNull byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }
}
