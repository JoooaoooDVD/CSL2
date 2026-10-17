package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything EasyTier needs for one session, rendered as its TOML config file. A config file is
 * used instead of command line flags so the network secret never shows up in a process list.
 * <p>
 * The flags match what the launcher validated end to end against EasyTier 2.6.4:
 * no TUN device (no VPN permission, no root), the smoltcp user-space stack so inbound traffic can
 * reach services bound on 0.0.0.0, KCP proxying for TCP streams over lossy mobile links, zstd
 * compression, latency-first routing and private mode (the node relays/accepts only peers that
 * prove the same network secret).
 */
public final class TunnelConfig {
    public static final String HOST_VIP = "10.144.144.1";
    public static final String HOST_NAME_PREFIX = "cslp-host-";
    /** The virtual subnet routed into the VPN TUN device (and nothing else) */
    public static final String SUBNET_CIDR = "10.144.144.0/24";
    public static final String GUEST_NAME_PREFIX = "cslp-guest-";

    public static final class PortForward {
        public final String proto, bind, dst;
        public PortForward(String proto, String bind, String dst) { this.proto = proto; this.bind = bind; this.dst = dst; }
    }

    public String hostname = "cslp";
    /** Fixed virtual IP (host) or null for DHCP (guest) */
    public String ipv4;
    public boolean dhcp;
    public final List<String> listeners = new ArrayList<>();
    public final List<String> peers = new ArrayList<>();
    public final List<String> stunServers = new ArrayList<>();
    public final List<Integer> tcpWhitelist = new ArrayList<>();
    public final List<Integer> udpWhitelist = new ArrayList<>();
    public final List<PortForward> portForwards = new ArrayList<>();
    public String networkName;
    public String networkSecret;
    public boolean multiThread = true;
    /** Diagnostics / restrictive networks: never try direct paths, go through relay nodes only */
    public boolean disableP2p;
    /** >0: expose the engine's SOCKS5 portal on 127.0.0.1:port (the TUN bridge dials into the virtual network through it) */
    public int socks5Port;

    @NonNull
    public String toToml() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("hostname = ").append(quote(hostname)).append('\n');
        if (dhcp || ipv4 == null) sb.append("dhcp = true\n");
        else sb.append("ipv4 = ").append(quote(ipv4)).append('\n');
        sb.append("listeners = ").append(array(listeners)).append('\n');
        List<String> tcp = new ArrayList<>();
        for (Integer p : tcpWhitelist) tcp.add(String.valueOf(p));
        List<String> udp = new ArrayList<>();
        for (Integer p : udpWhitelist) udp.add(String.valueOf(p));
        if (tcp.isEmpty()) tcp.add("0");
        if (udp.isEmpty()) udp.add("0");
        sb.append("tcp_whitelist = ").append(array(tcp)).append('\n');
        sb.append("udp_whitelist = ").append(array(udp)).append('\n');
        if (!stunServers.isEmpty()) sb.append("stun_servers = ").append(array(stunServers)).append('\n');
        if (socks5Port > 0) sb.append("socks5_proxy = ").append(quote("socks5://127.0.0.1:" + socks5Port)).append('\n');
        sb.append("\n[network_identity]\n");
        sb.append("network_name = ").append(quote(networkName)).append('\n');
        sb.append("network_secret = ").append(quote(networkSecret)).append('\n');
        for (String peer : peers) {
            sb.append("\n[[peer]]\nuri = ").append(quote(peer)).append('\n');
        }
        for (PortForward pf : portForwards) {
            sb.append("\n[[port_forward]]\n")
                    .append("bind_addr = ").append(quote(pf.bind)).append('\n')
                    .append("dst_addr = ").append(quote(pf.dst)).append('\n')
                    .append("proto = ").append(quote(pf.proto)).append('\n');
        }
        sb.append("\n[flags]\n");
        sb.append("no_tun = true\n");
        sb.append("use_smoltcp = true\n");
        sb.append("enable_kcp_proxy = true\n");
        sb.append("data_compress_algo = 2\n");
        sb.append("latency_first = true\n");
        sb.append("private_mode = true\n");
        if (multiThread) sb.append("multi_thread = true\n");
        if (disableP2p) sb.append("disable_p2p = true\n");
        return sb.toString();
    }

    private static String array(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(values.get(i)));
        }
        return sb.append(']').toString();
    }

    static String quote(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            if (c < 0x20) continue;
            sb.append(c);
        }
        return sb.append('"').toString();
    }
}
