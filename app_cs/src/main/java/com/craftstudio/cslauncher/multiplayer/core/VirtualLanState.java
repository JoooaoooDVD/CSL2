package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of a session as the UI sees it. Every field is real data read from the
 * tunnel engine, the session directory or Minecraft itself; nothing in here is estimated.
 */
public final class VirtualLanState {
    public enum Role { NONE, HOST, GUEST }

    public enum Phase {
        IDLE,
        /** Checking the engine, probing relay nodes */
        STARTING,
        /** Tunnel running, waiting to see the relay network / the host */
        DISCOVERING,
        /** Host found, opening the forwarded ports and talking to the session directory */
        ESTABLISHING,
        /** Host reachable through a direct peer-to-peer path */
        CONNECTED_DIRECT,
        /** Host reachable, traffic goes through a relay node */
        CONNECTED_RELAY,
        /** Was connected, path lost, engine is re-establishing */
        RECONNECTING,
        FAILED,
        /** Finished cleanly (left, host ended, kicked, expired) */
        ENDED;

        public boolean isConnected() { return this == CONNECTED_DIRECT || this == CONNECTED_RELAY; }
        public boolean isActive() { return this != IDLE && this != FAILED && this != ENDED; }
        public boolean isBusy() { return this == STARTING || this == DISCOVERING || this == ESTABLISHING || this == RECONNECTING; }
    }

    /** Why a session failed / ended; the UI maps these to localized text */
    public enum Reason {
        NONE,
        UNSUPPORTED_DEVICE,
        ENGINE_FAILED,
        ENGINE_CRASHED,
        NO_NETWORK,
        NO_RELAY_REACHABLE,
        RELAY_LOST,
        HOST_NOT_FOUND,
        HOST_UNREACHABLE,
        HOST_LOST,
        HOST_ENDED,
        KICKED,
        EXPIRED,
        LEFT,
        GAME_CLOSED,
        PORT_BUSY,
        INTERNAL
    }

    public static final class Member {
        public final String id;
        public final String name;
        public final String vip;
        public final long joinedAt;
        /** -1 when unknown */
        public final double latencyMs;
        /** null = unknown, true = direct, false = relayed */
        @Nullable public final Boolean direct;

        public Member(String id, String name, String vip, long joinedAt, double latencyMs, @Nullable Boolean direct) {
            this.id = id; this.name = name; this.vip = vip; this.joinedAt = joinedAt; this.latencyMs = latencyMs; this.direct = direct;
        }
    }

    public static final class Event {
        public enum Type { PLAYER_JOINED, PLAYER_LEFT, WORLD_OPENED, WORLD_CLOSED, PATH_DIRECT, PATH_RELAY, RECONNECTING, RECOVERED }
        public final long seq;
        public final Type type;
        public final String arg;
        public final long time;
        public Event(long seq, Type type, String arg) { this.seq = seq; this.type = type; this.arg = arg == null ? "" : arg; this.time = System.currentTimeMillis(); }
    }

    public final Role role;
    public final Phase phase;
    public final Reason reason;
    public final String detail;
    public final long phaseSince;
    public final String sessionCode;
    public final long expiresAt;

    // tunnel
    public final String localVip;
    public final String hostVip;
    public final int relayNodesConnected;
    public final double relayLatencyMs;
    public final String natType;
    public final String tunnelProto;
    public final String engineVersion;
    public final List<String> relayNames;

    // host side
    public final boolean worldOpen;
    public final String worldName;
    public final String motd;
    public final String versionName;
    public final int lanPort;
    public final int proxyPort;
    public final int directoryPort;
    public final int playersOnline;
    public final int playersMax;
    public final int inGame;

    // guest side
    public final String hostName;
    public final double hostLatencyMs;
    public final int localGamePort;

    public final List<Member> members;
    public final List<Event> events;

    private VirtualLanState(Builder b) {
        role = b.role; phase = b.phase; reason = b.reason; detail = b.detail; phaseSince = b.phaseSince;
        sessionCode = b.sessionCode; expiresAt = b.expiresAt;
        localVip = b.localVip; hostVip = b.hostVip; relayNodesConnected = b.relayNodesConnected; relayLatencyMs = b.relayLatencyMs;
        natType = b.natType; tunnelProto = b.tunnelProto; engineVersion = b.engineVersion;
        relayNames = Collections.unmodifiableList(new ArrayList<>(b.relayNames));
        worldOpen = b.worldOpen; worldName = b.worldName; motd = b.motd; versionName = b.versionName; lanPort = b.lanPort;
        proxyPort = b.proxyPort; directoryPort = b.directoryPort; playersOnline = b.playersOnline; playersMax = b.playersMax; inGame = b.inGame;
        hostName = b.hostName; hostLatencyMs = b.hostLatencyMs; localGamePort = b.localGamePort;
        members = Collections.unmodifiableList(new ArrayList<>(b.members));
        events = Collections.unmodifiableList(new ArrayList<>(b.events));
    }

    public static final VirtualLanState IDLE = new Builder().build();

    public boolean isActive() { return phase.isActive(); }
    public boolean isHost() { return role == Role.HOST; }
    public boolean isGuest() { return role == Role.GUEST; }

    @NonNull
    public Builder buildUpon() { return new Builder(this); }

    public static final class Builder {
        Role role = Role.NONE;
        Phase phase = Phase.IDLE;
        Reason reason = Reason.NONE;
        String detail = "";
        long phaseSince = System.currentTimeMillis();
        String sessionCode = "";
        long expiresAt;
        String localVip = "", hostVip = "";
        int relayNodesConnected;
        double relayLatencyMs = -1;
        String natType = "", tunnelProto = "", engineVersion = "";
        List<String> relayNames = new ArrayList<>();
        boolean worldOpen;
        String worldName = "", motd = "", versionName = "";
        int lanPort, proxyPort, directoryPort, playersOnline, playersMax, inGame;
        String hostName = "";
        double hostLatencyMs = -1;
        int localGamePort;
        List<Member> members = new ArrayList<>();
        List<Event> events = new ArrayList<>();

        public Builder() {}

        Builder(VirtualLanState s) {
            role = s.role; phase = s.phase; reason = s.reason; detail = s.detail; phaseSince = s.phaseSince;
            sessionCode = s.sessionCode; expiresAt = s.expiresAt;
            localVip = s.localVip; hostVip = s.hostVip; relayNodesConnected = s.relayNodesConnected; relayLatencyMs = s.relayLatencyMs;
            natType = s.natType; tunnelProto = s.tunnelProto; engineVersion = s.engineVersion; relayNames = new ArrayList<>(s.relayNames);
            worldOpen = s.worldOpen; worldName = s.worldName; motd = s.motd; versionName = s.versionName; lanPort = s.lanPort;
            proxyPort = s.proxyPort; directoryPort = s.directoryPort; playersOnline = s.playersOnline; playersMax = s.playersMax; inGame = s.inGame;
            hostName = s.hostName; hostLatencyMs = s.hostLatencyMs; localGamePort = s.localGamePort;
            members = new ArrayList<>(s.members); events = new ArrayList<>(s.events);
        }

        public Builder role(Role v) { role = v; return this; }
        public Builder phase(Phase v) { if (phase != v) phaseSince = System.currentTimeMillis(); phase = v; return this; }
        public Builder phaseSince(long v) { phaseSince = v; return this; }
        public Builder reason(Reason v, String d) { reason = v; detail = d == null ? "" : d; return this; }
        public Builder sessionCode(String v) { sessionCode = v == null ? "" : v; return this; }
        public Builder expiresAt(long v) { expiresAt = v; return this; }
        public Builder localVip(String v) { localVip = v == null ? "" : v; return this; }
        public Builder hostVip(String v) { hostVip = v == null ? "" : v; return this; }
        public Builder relay(int connected, double latency, List<String> names) { relayNodesConnected = connected; relayLatencyMs = latency; relayNames = new ArrayList<>(names); return this; }
        public Builder nat(String v) { natType = v == null ? "" : v; return this; }
        public Builder tunnelProto(String v) { tunnelProto = v == null ? "" : v; return this; }
        public Builder engineVersion(String v) { engineVersion = v == null ? "" : v; return this; }
        public Builder world(boolean open, String name, String motdText, String version, int port, int online, int max) {
            worldOpen = open; worldName = name == null ? "" : name; motd = motdText == null ? "" : motdText; versionName = version == null ? "" : version;
            lanPort = port; playersOnline = online; playersMax = max; return this;
        }
        public Builder ports(int proxy, int directory, int localGame) { proxyPort = proxy; directoryPort = directory; localGamePort = localGame; return this; }
        public Builder inGame(int v) { inGame = v; return this; }
        public Builder hostName(String v) { hostName = v == null ? "" : v; return this; }
        public Builder hostLatency(double v) { hostLatencyMs = v; return this; }
        public Builder members(List<Member> v) { members = new ArrayList<>(v); return this; }
        public Builder events(List<Event> v) { events = new ArrayList<>(v); return this; }
        public VirtualLanState build() { return new VirtualLanState(this); }
    }
}
