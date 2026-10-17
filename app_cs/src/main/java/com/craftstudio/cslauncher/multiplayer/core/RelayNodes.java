package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The public EasyTier nodes a session uses for peer discovery, NAT hole punching and, when a
 * direct path cannot be established, relaying.
 * <p>
 * Facts worth knowing (see docs/VIRTUAL_LAN_MULTIPLAYER.md): the official public.easytier.*
 * nodes were shut down in early 2026; the ecosystem now runs on community nodes. The built-in
 * list below was verified reachable (full EasyTier handshake, not just a TCP connect) when this
 * feature was built, the launcher refreshes it from the repository so dead nodes can be replaced
 * without an app update, and users can add their own node (any VPS running
 * {@code easytier-core} without arguments is a shared node).
 * <p>
 * Host names are resolved here, in Java: the static musl binary cannot use Android's DNS.
 * Every reachable node is handed to EasyTier (capped), so host and guest always share at least one
 * meeting point even if their individual orderings differ.
 */
public final class RelayNodes {
    public static final class Node {
        public final String uri;      // tcp://host:port or udp://host:port
        public final String name;
        public final String region;
        public final boolean relay;   // whether the operator allows relaying traffic
        public Node(String uri, String name, String region, boolean relay) {
            this.uri = uri; this.name = name; this.region = region; this.relay = relay;
        }
    }

    /** A probed node, with the URI rewritten to its IP so EasyTier never needs DNS */
    public static final class Probed {
        public final Node node;
        public final String resolvedUri;
        public final long rttMs;      // -1 for UDP nodes (not probed, only resolved)
        Probed(Node node, String resolvedUri, long rttMs) { this.node = node; this.resolvedUri = resolvedUri; this.rttMs = rttMs; }
    }

    public static final String REMOTE_LIST_URL =
            "https://raw.githubusercontent.com/JoooaoooDVD/CSL2/main/multiplayer/relay_nodes.json";
    private static final long REMOTE_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final int PROBE_TIMEOUT_MS = 2500;
    public static final int MAX_NODES = 6;

    /** Verified 2026-10: full EasyTier handshake succeeded against each of these. */
    public static final List<Node> BUILT_IN = Collections.unmodifiableList(java.util.Arrays.asList(
            new Node("tcp://225284.xyz:11010", "Haibo (Shanghai Telecom)", "CN", true),
            new Node("udp://us01.225284.xyz:11010", "Haibo (United States)", "US", true),
            new Node("tcp://183.230.36.171:11010", "Murphy (Chongqing Mobile)", "CN", true),
            new Node("tcp://easytier.weiai.org.cn:11010", "Weiai (Xiamen Telecom)", "CN", true),
            new Node("tcp://et.sumnight.top:11010", "Sumnight (Shanghai BGP)", "CN", false),
            new Node("udp://et.basd1.de:11010", "Basd1 (Zaozhuang BGP)", "CN", true)
    ));

    /** STUN servers EasyTier uses to learn the NAT type / public mapping, resolved in Java */
    private static final String[] STUN_HOSTS = {
            "stun.l.google.com:19302", "stun.cloudflare.com:3478", "stun.miwifi.com:3478",
            "stun.chat.bilibili.com:3478", "stun1.l.google.com:19302"
    };

    private RelayNodes() {}

    /**
     * Loads the node list: user nodes first, then the remote list (cached, refreshed at most
     * every 6 h, falls back to the built-in list on any failure).
     */
    @NonNull
    public static List<Node> load(@NonNull File cacheDir, @NonNull List<String> userUris, boolean allowNetwork) {
        Set<String> seen = new LinkedHashSet<>();
        List<Node> result = new ArrayList<>();
        for (String uri : userUris) {
            String clean = normalize(uri);
            if (clean != null && seen.add(clean)) result.add(new Node(clean, "Custom node", "", true));
        }
        List<Node> remote = allowNetwork ? remoteList(cacheDir) : cachedList(cacheDir);
        for (Node n : remote != null ? remote : BUILT_IN) if (seen.add(n.uri)) result.add(n);
        if (remote != null) for (Node n : BUILT_IN) if (seen.add(n.uri)) result.add(n);
        return result;
    }

    /** Probes all nodes in parallel; returns reachable ones best-first, capped at {@link #MAX_NODES} */
    @NonNull
    public static List<Probed> probe(@NonNull List<Node> nodes) {
        List<Probed> results = Collections.synchronizedList(new ArrayList<Probed>());
        CountDownLatch latch = new CountDownLatch(nodes.size());
        for (Node node : nodes) {
            Thread t = new Thread(() -> {
                try {
                    Probed p = probeOne(node);
                    if (p != null) results.add(p);
                } finally {
                    latch.countDown();
                }
            }, "vlan-probe");
            t.setDaemon(true);
            t.start();
        }
        try { latch.await(PROBE_TIMEOUT_MS + 1500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        List<Probed> sorted = new ArrayList<>(results);
        Collections.sort(sorted, new Comparator<Probed>() {
            @Override public int compare(Probed a, Probed b) {
                // TCP-probed nodes by RTT first, UDP (unprobed) after them, relays before non-relays
                long ra = a.rttMs < 0 ? Long.MAX_VALUE / 2 : a.rttMs;
                long rb = b.rttMs < 0 ? Long.MAX_VALUE / 2 : b.rttMs;
                if (a.node.relay != b.node.relay) return a.node.relay ? -1 : 1;
                return Long.compare(ra, rb);
            }
        });
        if (sorted.size() > MAX_NODES) sorted = new ArrayList<>(sorted.subList(0, MAX_NODES));
        return sorted;
    }

    @Nullable
    private static Probed probeOne(Node node) {
        String uri = node.uri;
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) return null;
        String scheme = uri.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        String rest = uri.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int colon = rest.lastIndexOf(':');
        if (colon <= 0) return null;
        String host = rest.substring(0, colon);
        int port;
        try { port = Integer.parseInt(rest.substring(colon + 1)); } catch (NumberFormatException e) { return null; }
        InetAddress address;
        try {
            address = pickV4(InetAddress.getAllByName(host));
        } catch (Exception e) {
            return null;
        }
        if (address == null) return null;
        String resolved = scheme + "://" + address.getHostAddress() + ":" + port;
        if ("tcp".equals(scheme)) {
            long start = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), PROBE_TIMEOUT_MS);
                long rtt = (System.nanoTime() - start) / 1_000_000L;
                return new Probed(node, resolved, rtt);
            } catch (IOException e) {
                return null;
            }
        }
        if ("udp".equals(scheme) || "ws".equals(scheme) || "wss".equals(scheme) || "quic".equals(scheme)) {
            return new Probed(node, resolved, -1);
        }
        return null;
    }

    /** Resolves the STUN hosts to "ip:port" strings; empty when offline */
    @NonNull
    public static List<String> resolveStun() {
        List<String> out = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(STUN_HOSTS.length);
        List<String> sync = Collections.synchronizedList(out);
        for (String entry : STUN_HOSTS) {
            Thread t = new Thread(() -> {
                try {
                    int colon = entry.lastIndexOf(':');
                    InetAddress a = pickV4(InetAddress.getAllByName(entry.substring(0, colon)));
                    if (a != null) sync.add(a.getHostAddress() + entry.substring(colon));
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            }, "vlan-stun-resolve");
            t.setDaemon(true);
            t.start();
        }
        try { latch.await(3000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return new ArrayList<>(sync);
    }

    @Nullable
    private static InetAddress pickV4(InetAddress[] all) {
        if (all == null) return null;
        for (InetAddress a : all) if (a instanceof java.net.Inet4Address) return a;
        return all.length > 0 ? all[0] : null;
    }

    /** Accepts "host:port" or "scheme://host:port"; returns a canonical URI or null */
    @Nullable
    public static String normalize(@Nullable String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "tcp://" + s;
        int schemeEnd = s.indexOf("://");
        String scheme = s.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        String rest = s.substring(schemeEnd + 3);
        if (!(scheme.equals("tcp") || scheme.equals("udp") || scheme.equals("ws") || scheme.equals("wss") || scheme.equals("quic"))) return null;
        if (rest.isEmpty() || rest.contains(" ")) return null;
        if (rest.lastIndexOf(':') <= 0 && (scheme.equals("tcp") || scheme.equals("udp"))) rest = rest + ":11010";
        return scheme + "://" + rest;
    }

    // ───────────────────────────── remote list ─────────────────────────────

    @Nullable
    private static List<Node> remoteList(File cacheDir) {
        File cache = new File(cacheDir, "relay_nodes.json");
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < REMOTE_TTL_MS) {
            List<Node> cached = parse(readQuietly(cache));
            if (cached != null && !cached.isEmpty()) return cached;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(REMOTE_LIST_URL).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setUseCaches(false);
            try {
                if (c.getResponseCode() == 200) {
                    String text = readAll(c.getInputStream());
                    List<Node> parsed = parse(text);
                    if (parsed != null && !parsed.isEmpty()) {
                        //noinspection ResultOfMethodCallIgnored
                        cacheDir.mkdirs();
                        try (FileOutputStream out = new FileOutputStream(cache)) {
                            out.write(text.getBytes(StandardCharsets.UTF_8));
                        }
                        return parsed;
                    }
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
        }
        List<Node> stale = parse(readQuietly(cache));
        return stale != null && !stale.isEmpty() ? stale : null;
    }

    @Nullable
    private static List<Node> cachedList(File cacheDir) {
        List<Node> cached = parse(readQuietly(new File(cacheDir, "relay_nodes.json")));
        return cached != null && !cached.isEmpty() ? cached : null;
    }

    @Nullable
    static List<Node> parse(@Nullable String text) {
        if (text == null) return null;
        try {
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.optJSONArray("nodes");
            if (arr == null) return null;
            List<Node> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String uri = normalize(o.optString("uri", ""));
                if (uri == null) continue;
                list.add(new Node(uri, o.optString("name", uri), o.optString("region", ""), o.optBoolean("relay", true)));
            }
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String readQuietly(File f) {
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
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
