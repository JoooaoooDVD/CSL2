package com.craftstudio.cslauncher.multiplayer;

import android.os.Bundle;

import androidx.annotation.NonNull;

import com.craftstudio.cslauncher.multiplayer.core.VirtualLanState;

import java.util.ArrayList;
import java.util.List;

/** {@link VirtualLanState} <-> Bundle, for the cross-process Messenger channel */
public final class VirtualLanStateBundler {
    private VirtualLanStateBundler() {}

    @NonNull
    public static Bundle toBundle(@NonNull VirtualLanState s) {
        Bundle b = new Bundle();
        b.putString("role", s.role.name());
        b.putString("phase", s.phase.name());
        b.putString("reason", s.reason.name());
        b.putString("detail", s.detail);
        b.putLong("phaseSince", s.phaseSince);
        b.putString("code", s.sessionCode);
        b.putLong("expiresAt", s.expiresAt);
        b.putString("localVip", s.localVip);
        b.putString("hostVip", s.hostVip);
        b.putInt("relays", s.relayNodesConnected);
        b.putDouble("relayLatency", s.relayLatencyMs);
        b.putString("nat", s.natType);
        b.putString("proto", s.tunnelProto);
        b.putString("engine", s.engineVersion);
        b.putStringArrayList("relayNames", new ArrayList<>(s.relayNames));
        b.putStringArrayList("stages", new ArrayList<>(s.stages));
        b.putBoolean("worldOpen", s.worldOpen);
        b.putString("worldName", s.worldName);
        b.putString("motd", s.motd);
        b.putString("version", s.versionName);
        b.putInt("lanPort", s.lanPort);
        b.putInt("proxyPort", s.proxyPort);
        b.putInt("directoryPort", s.directoryPort);
        b.putInt("online", s.playersOnline);
        b.putInt("max", s.playersMax);
        b.putInt("inGame", s.inGame);
        b.putString("hostName", s.hostName);
        b.putDouble("hostLatency", s.hostLatencyMs);
        b.putInt("localGamePort", s.localGamePort);
        b.putBoolean("gameReachable", s.gameReachable);
        b.putDouble("gameLatency", s.gameLatencyMs);
        b.putString("gameHost", s.gameHost);
        b.putInt("gamePort", s.gamePort);
        b.putBoolean("vpnUp", s.vpnUp);
        b.putInt("socks5Port", s.socks5Port);
        ArrayList<Bundle> members = new ArrayList<>();
        for (VirtualLanState.Member m : s.members) {
            Bundle mb = new Bundle();
            mb.putString("id", m.id);
            mb.putString("name", m.name);
            mb.putString("vip", m.vip);
            mb.putLong("joinedAt", m.joinedAt);
            mb.putDouble("latency", m.latencyMs);
            mb.putInt("direct", m.direct == null ? -1 : m.direct ? 1 : 0);
            members.add(mb);
        }
        b.putParcelableArrayList("members", members);
        ArrayList<Bundle> events = new ArrayList<>();
        for (VirtualLanState.Event e : s.events) {
            Bundle eb = new Bundle();
            eb.putLong("seq", e.seq);
            eb.putString("type", e.type.name());
            eb.putString("arg", e.arg);
            eb.putLong("time", e.time);
            events.add(eb);
        }
        b.putParcelableArrayList("events", events);
        return b;
    }

    @NonNull
    public static VirtualLanState fromBundle(@NonNull Bundle b) {
        VirtualLanState.Builder sb = new VirtualLanState.Builder()
                .role(enumOf(VirtualLanState.Role.class, b.getString("role"), VirtualLanState.Role.NONE))
                .phase(enumOf(VirtualLanState.Phase.class, b.getString("phase"), VirtualLanState.Phase.IDLE))
                .reason(enumOf(VirtualLanState.Reason.class, b.getString("reason"), VirtualLanState.Reason.NONE), b.getString("detail", ""))
                .phaseSince(b.getLong("phaseSince", System.currentTimeMillis()))
                .sessionCode(b.getString("code", ""))
                .expiresAt(b.getLong("expiresAt"))
                .localVip(b.getString("localVip", ""))
                .hostVip(b.getString("hostVip", ""))
                .nat(b.getString("nat", ""))
                .tunnelProto(b.getString("proto", ""))
                .engineVersion(b.getString("engine", ""))
                .world(b.getBoolean("worldOpen"), b.getString("worldName", ""), b.getString("motd", ""), b.getString("version", ""),
                        b.getInt("lanPort"), b.getInt("online"), b.getInt("max"))
                .ports(b.getInt("proxyPort"), b.getInt("directoryPort"), b.getInt("localGamePort"))
                .inGame(b.getInt("inGame"))
                .hostName(b.getString("hostName", ""))
                .hostLatency(b.getDouble("hostLatency", -1));
        ArrayList<String> relayNames = b.getStringArrayList("relayNames");
        sb.relay(b.getInt("relays"), b.getDouble("relayLatency", -1), relayNames != null ? relayNames : new ArrayList<String>());
        List<VirtualLanState.Member> members = new ArrayList<>();
        ArrayList<Bundle> mbs = b.getParcelableArrayList("members");
        if (mbs != null) for (Bundle mb : mbs) {
            int d = mb.getInt("direct", -1);
            members.add(new VirtualLanState.Member(mb.getString("id", ""), mb.getString("name", ""), mb.getString("vip", ""),
                    mb.getLong("joinedAt"), mb.getDouble("latency", -1), d < 0 ? null : d == 1));
        }
        sb.members(members);
        List<VirtualLanState.Event> events = new ArrayList<>();
        ArrayList<Bundle> ebs = b.getParcelableArrayList("events");
        if (ebs != null) for (Bundle eb : ebs) {
            VirtualLanState.Event.Type type = enumOf(VirtualLanState.Event.Type.class, eb.getString("type"), null);
            if (type != null) events.add(new VirtualLanState.Event(eb.getLong("seq"), type, eb.getString("arg", "")));
        }
        sb.game(b.getBoolean("gameReachable", false), b.getDouble("gameLatency", -1));
        sb.gameAddress(b.getString("gameHost", ""), b.getInt("gamePort", 0));
        sb.vpn(b.getBoolean("vpnUp", false), b.getInt("socks5Port", 0));
        sb.events(events);
        ArrayList<String> stages = b.getStringArrayList("stages");
        if (stages != null) sb.stages(stages);
        VirtualLanState state = sb.build();
        return state;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try { return Enum.valueOf(type, name); } catch (IllegalArgumentException e) { return fallback; }
    }
}
