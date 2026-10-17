package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The session directory the host serves over the tunnel. It replaces LAN broadcast discovery
 * (which cannot cross the tunnel): guests ask it where the world is, whether it is still open,
 * and announce themselves so the host sees who is in the session.
 * <p>
 * It only ever listens on the tunnel-facing port that is whitelisted in EasyTier, every request
 * must carry the per-session auth token (derived from the code, never logged), and members get a
 * random per-join token so a kicked member cannot keep heart-beating. Tiny HTTP/1.1 subset, one
 * short-lived thread per request, JSON bodies, "Connection: close".
 */
public final class SessionDirectoryServer {
    public static final int PROTOCOL_VERSION = 1;
    private static final int MAX_BODY = 16 * 1024;
    private static final long MEMBER_TIMEOUT_MS = 15_000L;

    public interface Listener {
        void onMembersChanged();
    }

    /** What the host currently knows about its world; replaced atomically by the session */
    public static final class WorldInfo {
        public final boolean open;
        public final int lanPort;
        public final String motd;
        public final String worldName;
        public final String versionName;
        public final int protocol;
        public final int online;
        public final int max;

        public WorldInfo(boolean open, int lanPort, String motd, String worldName, String versionName, int protocol, int online, int max) {
            this.open = open;
            this.lanPort = lanPort;
            this.motd = motd == null ? "" : motd;
            this.worldName = worldName == null ? "" : worldName;
            this.versionName = versionName == null ? "" : versionName;
            this.protocol = protocol;
            this.online = online;
            this.max = max;
        }

        public static WorldInfo closed() { return new WorldInfo(false, 0, "", "", "", -1, 0, 0); }
    }

    public static final class Member {
        public final String id;
        public final String name;
        public volatile String vip;
        public final long joinedAt;
        final String token;
        volatile long lastSeen;

        Member(String id, String name, String vip, String token) {
            this.id = id;
            this.name = name;
            this.vip = vip;
            this.token = token;
            this.joinedAt = System.currentTimeMillis();
            this.lastSeen = joinedAt;
        }

        public long lastSeen() { return lastSeen; }
    }

    private final String mAuthToken;
    private final String mSessionId;
    private final String mHostName;
    private final long mCreatedAt;
    private final long mExpiresAt;
    private final Listener mListener;
    private final SecureRandom mRandom = new SecureRandom();
    private final Map<String, Member> mMembers = new LinkedHashMap<>();
    private final Set<String> mKicked = new HashSet<>();

    private volatile WorldInfo mWorld = WorldInfo.closed();
    private volatile int mProxyPort;
    private volatile int mInGame;
    private volatile boolean mEnding;
    private volatile boolean mRunning;
    private ServerSocket mServer;
    private int mPort;

    public SessionDirectoryServer(@NonNull String authToken, @NonNull String sessionId, @NonNull String hostName,
                                  long expiresAt, int proxyPort, @Nullable Listener listener) {
        mAuthToken = authToken;
        mSessionId = sessionId;
        mHostName = hostName;
        mCreatedAt = System.currentTimeMillis();
        mExpiresAt = expiresAt;
        mProxyPort = proxyPort;
        mListener = listener;
    }

    public int port() { return mPort; }
    public boolean isRunning() { return mRunning; }

    public void setWorld(@NonNull WorldInfo world) { mWorld = world; }
    public void setInGame(int count) { mInGame = count; }
    public void setProxyPort(int port) { mProxyPort = port; }
    /** Flag guests see on their next heartbeat so they can show "host ended the session" at once */
    public void setEnding(boolean ending) { mEnding = ending; }

    @NonNull
    public List<Member> members() {
        pruneLocked();
        synchronized (mMembers) { return new ArrayList<>(mMembers.values()); }
    }

    /** Cooperative kick: the member's token dies, its next heartbeat gets 403 and the guest leaves */
    public boolean kick(String memberId) {
        boolean removed;
        synchronized (mMembers) {
            removed = mMembers.remove(memberId) != null;
            mKicked.add(memberId);
        }
        if (removed && mListener != null) mListener.onMembersChanged();
        return removed;
    }

    public synchronized void start(int preferredPort) throws IOException {
        if (mRunning) return;
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        IOException last = null;
        for (int p = preferredPort; p < preferredPort + 10; p++) {
            try {
                server.bind(new InetSocketAddress((InetAddress) null, p), 8);
                mPort = p;
                last = null;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) {
            server.bind(new InetSocketAddress((InetAddress) null, 0), 8);
            mPort = server.getLocalPort();
        }
        mServer = server;
        mRunning = true;
        Thread t = new Thread(this::acceptLoop, "vlan-directory");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        mRunning = false;
        ServerSocket server = mServer;
        mServer = null;
        if (server != null) try { server.close(); } catch (IOException ignored) {}
    }

    private void acceptLoop() {
        ServerSocket server = mServer;
        while (mRunning && server != null && !server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread t = new Thread(() -> serve(socket), "vlan-directory-req");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (mRunning) try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { respond(socket, 400, error("bad request")); return; }
            String method = parts[0];
            String path = parts[1];
            int queryIdx = path.indexOf('?');
            if (queryIdx >= 0) path = path.substring(0, queryIdx);
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            int contentLength = 0;
            String lengthHeader = headers.get("content-length");
            try { if (lengthHeader != null) contentLength = Integer.parseInt(lengthHeader); } catch (NumberFormatException ignored) {}
            if (contentLength < 0 || contentLength > MAX_BODY) { respond(socket, 413, error("too large")); return; }
            char[] bodyChars = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = reader.read(bodyChars, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            // Request bodies are ISO-8859-1 decoded above; re-encode to the real UTF-8 text
            String body = new String(new String(bodyChars, 0, read).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);

            if (!mAuthToken.equals(headers.get("x-csl-auth"))) {
                respond(socket, 401, error("unauthorized"));
                return;
            }
            JSONObject response = route(method, path, body);
            if (response == null) respond(socket, 404, error("not found"));
            else respond(socket, response.optInt("_status", 200), response);
        } catch (Exception e) {
            try { respond(socket, 500, error("internal")); } catch (Exception ignored) {}
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    @Nullable
    private JSONObject route(String method, String path, String body) throws JSONException {
        switch (path) {
            case "/v1/session":
                return snapshot();
            case "/v1/join": {
                if (!"POST".equals(method)) return null;
                JSONObject req = new JSONObject(body);
                String id = req.optString("id", "");
                String name = req.optString("name", "").trim();
                String vip = req.optString("vip", "");
                if (id.isEmpty() || id.length() > 64) return status(400, error("bad id"));
                if (name.isEmpty()) name = "Player";
                if (name.length() > 32) name = name.substring(0, 32);
                synchronized (mMembers) {
                    if (mKicked.contains(id)) return status(403, error("kicked"));
                    if (mMembers.size() >= 64 && !mMembers.containsKey(id)) return status(429, error("full"));
                    Member member = new Member(id, name, vip, randomToken());
                    mMembers.put(id, member);
                    JSONObject res = snapshot();
                    res.put("token", member.token);
                    if (mListener != null) mListener.onMembersChanged();
                    return res;
                }
            }
            case "/v1/heartbeat": {
                if (!"POST".equals(method)) return null;
                JSONObject req = new JSONObject(body);
                String id = req.optString("id", "");
                String token = req.optString("token", "");
                synchronized (mMembers) {
                    if (mKicked.contains(id)) return status(403, error("kicked"));
                    Member member = mMembers.get(id);
                    if (member == null) return status(404, error("unknown member"));
                    if (!member.token.equals(token)) return status(403, error("bad token"));
                    member.lastSeen = System.currentTimeMillis();
                    String vip = req.optString("vip", "");
                    if (!vip.isEmpty() && !vip.equals(member.vip)) member.vip = vip;
                }
                return snapshot();
            }
            case "/v1/leave": {
                if (!"POST".equals(method)) return null;
                JSONObject req = new JSONObject(body);
                String id = req.optString("id", "");
                String token = req.optString("token", "");
                boolean removed = false;
                synchronized (mMembers) {
                    Member member = mMembers.get(id);
                    if (member != null && member.token.equals(token)) {
                        mMembers.remove(id);
                        removed = true;
                    }
                }
                if (removed && mListener != null) mListener.onMembersChanged();
                return new JSONObject().put("ok", removed);
            }
            default:
                return null;
        }
    }

    private void pruneLocked() {
        boolean changed = false;
        long now = System.currentTimeMillis();
        synchronized (mMembers) {
            for (java.util.Iterator<Member> it = mMembers.values().iterator(); it.hasNext(); ) {
                Member m = it.next();
                if (now - m.lastSeen > MEMBER_TIMEOUT_MS) {
                    it.remove();
                    changed = true;
                }
            }
        }
        if (changed && mListener != null) mListener.onMembersChanged();
    }

    /** The document guests poll; also what the host UI shows, so both sides agree */
    @NonNull
    public JSONObject snapshot() throws JSONException {
        pruneLocked();
        WorldInfo world = mWorld;
        JSONObject root = new JSONObject();
        root.put("version", PROTOCOL_VERSION);
        root.put("sessionId", mSessionId);
        root.put("hostName", mHostName);
        root.put("createdAt", mCreatedAt);
        root.put("expiresAt", mExpiresAt);
        root.put("serverTime", System.currentTimeMillis());
        root.put("ending", mEnding);
        root.put("proxyPort", mProxyPort);
        root.put("inGame", mInGame);
        JSONObject w = new JSONObject();
        w.put("open", world.open);
        w.put("lanPort", world.lanPort);
        w.put("motd", world.motd);
        w.put("name", world.worldName);
        w.put("versionName", world.versionName);
        w.put("protocol", world.protocol);
        w.put("online", world.online);
        w.put("max", world.max);
        root.put("world", w);
        JSONArray members = new JSONArray();
        synchronized (mMembers) {
            for (Member m : mMembers.values()) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("name", m.name);
                o.put("vip", m.vip);
                o.put("joinedAt", m.joinedAt);
                o.put("lastSeen", m.lastSeen);
                members.put(o);
            }
        }
        root.put("members", members);
        return root;
    }

    private String randomToken() {
        byte[] b = new byte[16];
        mRandom.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static JSONObject error(String message) {
        try { return new JSONObject().put("error", message); } catch (JSONException e) { return new JSONObject(); }
    }

    private static JSONObject status(int code, JSONObject body) throws JSONException {
        return body.put("_status", code);
    }

    private static void respond(Socket socket, int code, JSONObject body) throws IOException {
        body.remove("_status");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.flush();
    }
}
