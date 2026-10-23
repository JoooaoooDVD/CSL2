package com.craftstudio.cslauncher.discord;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Minimal reader for the game's {@code servers.dat} (NBT): gives the name the player typed for a
 * server and its cached icon, so the Discord card can say "Hypixel" instead of "mc.hypixel.net"
 * and show the server's own icon.
 */
final class MinecraftServerList {

    static final class Entry {
        @NonNull final String name;
        @NonNull final String address;
        /** PNG bytes of the server icon (64×64) or null */
        @Nullable final byte[] iconPng;

        Entry(@NonNull String name, @NonNull String address, @Nullable byte[] iconPng) {
            this.name = name;
            this.address = address;
            this.iconPng = iconPng;
        }
    }

    private MinecraftServerList() {}

    /** Find the saved server matching the host (and port) the game is connecting to */
    @Nullable
    static Entry find(@Nullable File gameDir, @NonNull String host, int port) {
        if (gameDir == null || host.isEmpty()) return null;
        List<Entry> entries;
        try {
            entries = read(new File(gameDir, "servers.dat"));
        } catch (Throwable t) {
            return null;
        }
        String wantHost = host.toLowerCase(Locale.ROOT);
        Entry hostOnly = null;
        for (Entry entry : entries) {
            String address = entry.address.toLowerCase(Locale.ROOT).trim();
            String addrHost = address;
            int addrPort = 25565;
            int colon = address.lastIndexOf(':');
            if (colon > 0 && !address.contains("]") && address.indexOf(':') == colon) {
                addrHost = address.substring(0, colon);
                try { addrPort = Integer.parseInt(address.substring(colon + 1)); } catch (NumberFormatException ignored) {}
            }
            if (!addrHost.equals(wantHost)) continue;
            if (port <= 0 || addrPort == port) return entry;
            if (hostOnly == null) hostOnly = entry;
        }
        return hostOnly;
    }

    @NonNull
    static List<Entry> read(@NonNull File file) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (!file.isFile() || file.length() > 4L * 1024 * 1024) return out;
        try (InputStream raw = open(file); DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {
            int rootType = in.readUnsignedByte();
            if (rootType != 10) return out;
            readUtf(in); // root name
            readCompound(in, out, 0);
        }
        return out;
    }

    private static InputStream open(@NonNull File file) throws IOException {
        PushbackInputStream in = new PushbackInputStream(new FileInputStream(file), 2);
        byte[] magic = new byte[2];
        int n = in.read(magic);
        if (n > 0) in.unread(magic, 0, n);
        if (n == 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b) return new GZIPInputStream(in);
        return in;
    }

    /** Walks a compound; collects "servers" list entries (name / ip / icon) */
    private static void readCompound(@NonNull DataInputStream in, @NonNull List<Entry> out, int depth) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return;
            String name = readUtf(in);
            if (type == 9 && "servers".equals(name) && depth == 0) {
                int itemType = in.readUnsignedByte();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    if (itemType == 10) {
                        Entry entry = readServer(in);
                        if (entry != null) out.add(entry);
                    } else {
                        skipPayload(in, itemType);
                    }
                }
            } else {
                skipPayload(in, type);
            }
        }
    }

    @Nullable
    private static Entry readServer(@NonNull DataInputStream in) throws IOException {
        String name = "", ip = "";
        byte[] icon = null;
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) break;
            String key = readUtf(in);
            if (type == 8) {
                String value = readUtf(in);
                if ("name".equals(key)) name = value;
                else if ("ip".equals(key)) ip = value;
                else if ("icon".equals(key) && !value.isEmpty()) {
                    try {
                        String b64 = value.startsWith("data:") && value.contains(",") ? value.substring(value.indexOf(',') + 1) : value;
                        icon = Base64.decode(b64, Base64.DEFAULT);
                    } catch (Throwable ignored) {}
                }
            } else if (type == 7 && "icon".equals(key)) {
                // Newer versions store the icon as a raw byte array
                int len = in.readInt();
                byte[] bytes = new byte[Math.max(0, len)];
                in.readFully(bytes);
                icon = bytes;
            } else {
                skipPayload(in, type);
            }
        }
        if (ip.isEmpty()) return null;
        return new Entry(name.trim().isEmpty() ? ip : name.trim(), ip, icon);
    }

    private static void skipPayload(@NonNull DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: in.skipBytes(1); break;
            case 2: in.skipBytes(2); break;
            case 3: case 5: in.skipBytes(4); break;
            case 4: case 6: in.skipBytes(8); break;
            case 7: { int len = in.readInt(); skipFully(in, len); break; }
            case 8: { int len = in.readUnsignedShort(); skipFully(in, len); break; }
            case 9: {
                int itemType = in.readUnsignedByte();
                int count = in.readInt();
                for (int i = 0; i < count; i++) skipPayload(in, itemType);
                break;
            }
            case 10: {
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    readUtf(in);
                    skipPayload(in, t);
                }
                break;
            }
            case 11: { int len = in.readInt(); skipFully(in, 4L * len); break; }
            case 12: { int len = in.readInt(); skipFully(in, 8L * len); break; }
            default: throw new IOException("Unknown NBT tag " + type);
        }
    }

    private static void skipFully(@NonNull DataInputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() < 0) throw new IOException("EOF");
                skipped = 1;
            }
            n -= skipped;
        }
    }

    @NonNull
    private static String readUtf(@NonNull DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, "UTF-8");
    }
}
