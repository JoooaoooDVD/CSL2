package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Server List Ping client. It is the launcher's ground truth for "is there really a Minecraft
 * world listening on this port": the host uses it to confirm a detected LAN port and to read the
 * world name / player count, the guest uses it through the tunnel to prove the host is reachable
 * before the UI is allowed to say "Connected".
 * <p>
 * Speaks the modern handshake (1.7+) and falls back to the legacy 0xFE ping for older worlds.
 * Nothing here blocks the UI thread: callers run it on a worker thread.
 */
public final class MinecraftPinger {
    private MinecraftPinger() {}

    public static final class Status {
        /** Plain-text MOTD. For LAN worlds this is "<player> - <world name>". */
        public final String motd;
        public final String versionName;
        public final int protocol;
        public final int playersOnline;
        public final int playersMax;
        public final long latencyMs;

        Status(String motd, String versionName, int protocol, int playersOnline, int playersMax, long latencyMs) {
            this.motd = motd;
            this.versionName = versionName;
            this.protocol = protocol;
            this.playersOnline = playersOnline;
            this.playersMax = playersMax;
            this.latencyMs = latencyMs;
        }

        /** Best effort world name out of the LAN MOTD "<player> - <world>" */
        @NonNull
        public String worldName() {
            if (motd == null) return "";
            int idx = motd.indexOf(" - ");
            return idx > 0 && idx + 3 < motd.length() ? motd.substring(idx + 3).trim() : motd.trim();
        }

        @NonNull
        public String hostPlayer() {
            if (motd == null) return "";
            int idx = motd.indexOf(" - ");
            return idx > 0 ? motd.substring(0, idx).trim() : "";
        }

        @Override public String toString() {
            return "Status{" + motd + ", " + versionName + "/" + protocol + ", " + playersOnline + "/" + playersMax + ", " + latencyMs + "ms}";
        }
    }

    /**
     * @param host    address to connect to (127.0.0.1 for the local LAN world / local tunnel port)
     * @param port    port
     * @param timeout connect + read timeout in ms
     * @return the status, or null when nothing Minecraft-like answered
     */
    @Nullable
    public static Status ping(@NonNull String host, int port, int timeout) {
        return ping(host, port, timeout, null);
    }

    /** Same, optionally through a SOCKS proxy (the harness verifies the VPN bridge path this way) */
    @Nullable
    public static Status ping(@NonNull String host, int port, int timeout, @Nullable java.net.Proxy proxy) {
        Status status = pingModern(host, port, timeout, proxy);
        if (status != null) return status;
        return pingLegacy(host, port, timeout);
    }

    @Nullable
    static Status pingModern(String host, int port, int timeout) { return pingModern(host, port, timeout, null); }

    @Nullable
    static Status pingModern(String host, int port, int timeout, @Nullable java.net.Proxy proxy) {
        long start = System.nanoTime();
        try (Socket socket = proxy == null ? new Socket() : new Socket(proxy)) {
            socket.setSoTimeout(timeout);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeout);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Handshake: protocol -1 asks "whatever you are", host/port are informational.
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            DataOutputStream hsOut = new DataOutputStream(hs);
            writeVarInt(hsOut, 0x00);
            writeVarInt(hsOut, -1);
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(hsOut, hostBytes.length);
            hsOut.write(hostBytes);
            hsOut.writeShort(port);
            writeVarInt(hsOut, 1);
            writePacket(out, hs.toByteArray());
            // Status request
            writePacket(out, new byte[]{0x00});
            out.flush();

            int length = readVarInt(in);
            if (length <= 0 || length > 1 << 20) return null;
            int packetId = readVarInt(in);
            if (packetId != 0x00) return null;
            int jsonLength = readVarInt(in);
            if (jsonLength <= 0 || jsonLength > 1 << 20) return null;
            byte[] json = new byte[jsonLength];
            in.readFully(json);
            long latency = (System.nanoTime() - start) / 1_000_000L;
            return parseJson(new String(json, StandardCharsets.UTF_8), latency);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    static Status parseJson(String text, long latency) {
        try {
            JSONObject root = new JSONObject(text);
            String motd = flattenDescription(root.opt("description"));
            JSONObject version = root.optJSONObject("version");
            String versionName = version != null ? version.optString("name", "") : "";
            int protocol = version != null ? version.optInt("protocol", -1) : -1;
            JSONObject players = root.optJSONObject("players");
            int online = players != null ? players.optInt("online", 0) : 0;
            int max = players != null ? players.optInt("max", 0) : 0;
            return new Status(motd, versionName, protocol, online, max, latency);
        } catch (Exception e) {
            return null;
        }
    }

    /** Chat components can be plain strings, {text, extra[]} objects or arrays */
    private static String flattenDescription(Object description) {
        StringBuilder sb = new StringBuilder();
        flatten(description, sb);
        return stripFormatting(sb.toString());
    }

    private static void flatten(Object node, StringBuilder sb) {
        if (node == null) return;
        if (node instanceof String) {
            sb.append((String) node);
        } else if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has("text")) sb.append(obj.optString("text", ""));
            else if (obj.has("translate")) sb.append(obj.optString("translate", ""));
            JSONArray extra = obj.optJSONArray("extra");
            if (extra != null) for (int i = 0; i < extra.length(); i++) flatten(extra.opt(i), sb);
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) flatten(arr.opt(i), sb);
        }
    }

    /** Removes §-codes */
    static String stripFormatting(String s) {
        if (s == null || s.indexOf('\u00a7') < 0) return s == null ? "" : s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7') { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Legacy (1.4 - 1.6) ping: FE 01, answer FF + UTF-16BE "§1\0protocol\0version\0motd\0online\0max" */
    @Nullable
    static Status pingLegacy(String host, int port, int timeout) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(timeout);
            socket.connect(new InetSocketAddress(host, port), timeout);
            OutputStream out = socket.getOutputStream();
            out.write(new byte[]{(byte) 0xFE, 0x01});
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int id = in.read();
            if (id != 0xFF) return null;
            int chars = in.readUnsignedShort();
            if (chars <= 0 || chars > 4096) return null;
            byte[] data = new byte[chars * 2];
            in.readFully(data);
            String text = new String(data, StandardCharsets.UTF_16BE);
            long latency = (System.nanoTime() - start) / 1_000_000L;
            if (text.startsWith("\u00a71")) {
                String[] parts = text.split("\0");
                if (parts.length >= 6) {
                    return new Status(stripFormatting(parts[3]), parts[2], parseIntSafe(parts[1]),
                            parseIntSafe(parts[4]), parseIntSafe(parts[5]), latency);
                }
            }
            String[] parts = text.split("\u00a7");
            if (parts.length >= 3) {
                return new Status(stripFormatting(parts[0]), "", -1, parseIntSafe(parts[1]), parseIntSafe(parts[2]), latency);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private static void writePacket(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 5);
        writeVarInt(new DataOutputStream(frame), payload.length);
        frame.write(payload);
        out.write(frame.toByteArray());
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(InputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("eof");
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 35) throw new IOException("varint too long");
        }
    }
}
