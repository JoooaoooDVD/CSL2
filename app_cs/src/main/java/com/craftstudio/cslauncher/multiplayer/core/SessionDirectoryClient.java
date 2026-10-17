package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
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

    /**
     * Minimal HTTP/1.1 client over a plain socket. Deliberately NOT HttpURLConnection: on
     * targetSdk >= 28 Android applies the network-security cleartext policy to loopback as well,
     * so an http://127.0.0.1 request fails with "Cleartext HTTP traffic not permitted" unless the
     * manifest whitelists it. The traffic here never leaves the device (it enters the encrypted
     * tunnel on 127.0.0.1), so a raw socket is the correct, policy-independent transport.
     */
    private JSONObject call(String method, String path, @Nullable JSONObject body) throws IOException {
        int port = mLocalPort;
        if (port <= 0) throw new IOException("directory port not ready");
        byte[] payload = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(256);
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(port).append("\r\n")
                .append("X-CSL-Auth: ").append(mAuthToken).append("\r\n")
                .append("Connection: close\r\n")
                .append("Accept: application/json\r\n");
        if (body != null) {
            head.append("Content-Type: application/json; charset=utf-8\r\n")
                    .append("Content-Length: ").append(payload.length).append("\r\n");
        }
        head.append("\r\n");
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(mTimeoutMs);
            socket.connect(new InetSocketAddress("127.0.0.1", port), mTimeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(payload);
            out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            String statusLine = readLine(in);
            if (statusLine == null || !statusLine.startsWith("HTTP/")) throw new IOException("bad directory status line");
            String[] parts = statusLine.split(" ", 3);
            int code;
            try { code = Integer.parseInt(parts[1].trim()); } catch (Exception e) { throw new IOException("bad directory status: " + statusLine); }
            int contentLength = -1;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try { contentLength = Integer.parseInt(line.substring(colon + 1).trim()); } catch (NumberFormatException ignored) {}
                }
            }
            String text = readBody(in, contentLength);
            if (code >= 400) {
                String reason = "";
                try { reason = new JSONObject(text).optString("error", ""); } catch (Exception ignored) {}
                throw new RejectedException(code, reason);
            }
            return new JSONObject(text);
        } catch (org.json.JSONException e) {
            throw new IOException("bad directory response", e);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    @Nullable
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
            if (bos.size() > 16 * 1024) throw new IOException("directory header too long");
        }
        if (!any) return null;
        return new String(bos.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String readBody(InputStream in, int contentLength) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        if (contentLength >= 0) {
            int remaining = contentLength;
            while (remaining > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) break;
                bos.write(buf, 0, n);
                remaining -= n;
            }
        } else {
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

}
