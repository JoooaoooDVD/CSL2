package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Guest side of {@link SessionDirectoryServer}: talks to the host directory through the local
 * tunnel port. Every successful call is also proof that the tunnel really carries traffic to
 * the host, which is what gates the "Connected" state.
 */
public final class SessionDirectoryClient {
    public static final class Snapshot {
        public final String sessionId;
        public final String hostName;
        public final long expiresAt;
        public final boolean ending;
        public final int proxyPort;
        public final int inGame;
        public final boolean worldOpen;
        public final int lanPort;
        public final String motd;
        public final String worldName;
        public final String versionName;
        public final int protocol;
        public final int online;
        public final int max;
        public final List<MemberInfo> members;
        @Nullable public final String token;

        Snapshot(JSONObject o) {
            sessionId = o.optString("sessionId", "");
            hostName = o.optString("hostName", "");
            expiresAt = o.optLong("expiresAt", 0);
            ending = o.optBoolean("ending", false);
            proxyPort = o.optInt("proxyPort", 0);
            inGame = o.optInt("inGame", 0);
            JSONObject w = o.optJSONObject("world");
            worldOpen = w != null && w.optBoolean("open", false);
            lanPort = w != null ? w.optInt("lanPort", 0) : 0;
            motd = w != null ? w.optString("motd", "") : "";
            worldName = w != null ? w.optString("name", "") : "";
            versionName = w != null ? w.optString("versionName", "") : "";
            protocol = w != null ? w.optInt("protocol", -1) : -1;
            online = w != null ? w.optInt("online", 0) : 0;
            max = w != null ? w.optInt("max", 0) : 0;
            List<MemberInfo> list = new ArrayList<>();
            JSONArray arr = o.optJSONArray("members");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m != null) list.add(new MemberInfo(m.optString("id", ""), m.optString("name", ""), m.optString("vip", ""), m.optLong("joinedAt", 0)));
            }
            members = list;
            token = o.has("token") ? o.optString("token") : null;
        }
    }

    public static final class MemberInfo {
        public final String id, name, vip;
        public final long joinedAt;
        MemberInfo(String id, String name, String vip, long joinedAt) {
            this.id = id; this.name = name; this.vip = vip; this.joinedAt = joinedAt;
        }
    }

    /** Thrown for HTTP-level rejections so the session can react (kicked, unauthorized, ...) */
    public static final class RejectedException extends IOException {
        public final int code;
        public final String reason;
        RejectedException(int code, String reason) {
            super("HTTP " + code + " " + reason);
            this.code = code;
            this.reason = reason;
        }
    }

    private final String mAuthToken;
    private volatile int mLocalPort;
    private final int mTimeoutMs;

    public SessionDirectoryClient(@NonNull String authToken, int localPort, int timeoutMs) {
        mAuthToken = authToken;
        mLocalPort = localPort;
        mTimeoutMs = timeoutMs;
    }

    public void setLocalPort(int port) { mLocalPort = port; }

    @NonNull
    public Snapshot session() throws IOException {
        return new Snapshot(call("GET", "/v1/session", null));
    }

    @NonNull
    public Snapshot join(String memberId, String name, String vip) throws IOException {
        try {
            JSONObject body = new JSONObject().put("id", memberId).put("name", name).put("vip", vip);
            return new Snapshot(call("POST", "/v1/join", body));
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    @NonNull
    public Snapshot heartbeat(String memberId, String token, String vip) throws IOException {
        try {
            JSONObject body = new JSONObject().put("id", memberId).put("token", token).put("vip", vip == null ? "" : vip);
            return new Snapshot(call("POST", "/v1/heartbeat", body));
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    public void leave(String memberId, String token) {
        try {
            JSONObject body = new JSONObject().put("id", memberId).put("token", token);
            call("POST", "/v1/leave", body);
        } catch (Exception ignored) {
        }
    }

    private JSONObject call(String method, String path, @Nullable JSONObject body) throws IOException {
        int port = mLocalPort;
        if (port <= 0) throw new IOException("directory port not ready");
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(mTimeoutMs);
            c.setReadTimeout(mTimeoutMs);
            c.setUseCaches(false);
            c.setRequestProperty("X-CSL-Auth", mAuthToken);
            c.setRequestProperty("Connection", "close");
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400) {
                String reason = "";
                try { reason = new JSONObject(text).optString("error", ""); } catch (Exception ignored) {}
                throw new RejectedException(code, reason);
            }
            return new JSONObject(text);
        } catch (org.json.JSONException e) {
            throw new IOException("bad directory response", e);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
