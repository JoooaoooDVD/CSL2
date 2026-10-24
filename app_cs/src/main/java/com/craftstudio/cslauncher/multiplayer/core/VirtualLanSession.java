package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.craftstudio.cslauncher.multiplayer.core.VirtualLanState.Event;
import com.craftstudio.cslauncher.multiplayer.core.VirtualLanState.Phase;
import com.craftstudio.cslauncher.multiplayer.core.VirtualLanState.Reason;
import com.craftstudio.cslauncher.multiplayer.core.VirtualLanState.Role;

/**
 * One Virtual LAN session (host or guest): the state machine that drives the tunnel, the LAN
 * detector, the port proxy and the session directory, and publishes {@link VirtualLanState}
 * snapshots. Platform independent; {@code VirtualLanService} wraps it on Android.
 * <p>
 * Everything runs on a single worker thread; public methods only enqueue work, so callers
 * (binder threads, UI) never block on networking.
 */
public final class VirtualLanSession {
    public interface Listener {
        /** Worker thread. Snapshots are immutable, hand them to the UI thread freely. */
        void onStateChanged(@NonNull VirtualLanState state);
        /** Worker thread. The session is fully torn down, resources released. */
        void onFinished(@NonNull VirtualLanState finalState);
    }

    public static final class Params {
        public Role role = Role.GUEST;
        public SessionCode code;
        public String playerName = "Player";
        public List<String> userRelayUris = new ArrayList<>();
        public boolean allowRemoteNodeList = true;
        /** Preferred local ports; the session moves on when they are taken */
        public int proxyPort = 25565;
        public int directoryPort = 25560;
        public int guestGamePort = 25565;
        public long maxDurationMs = 12L * 60 * 60 * 1000;
        /** Force the relay path (used by the harness to prove relays carry traffic; exposed as an advanced option) */
        public boolean relayOnly;
        /** Platform TUN device (Android VpnService); null = loopback-forward only (desktop harness) */
        @Nullable public VpnController vpn;
        /** Harness: open the SOCKS5 portal even without a VPN so the TUN-bridge path can be verified */
        public boolean alwaysSocks5;
    }

    private static final long VPN_TIMEOUT_MS = 30_000;
    private int mSocks5Port;
    private String mVpnVip = "";
    private long mVpnRequestedAt;
    private boolean mVpnWasUp;

    /**
     * Keeps the platform VPN in sync with our virtual IP. Returns true when the TUN device is up
     * (or when this platform has none). Fails the session on denial / revoke / timeout.
     */
    private boolean vpnReady(String localVip) {
        VpnController vpn = mParams.vpn;
        if (vpn == null) return true;
        if (localVip == null || localVip.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (vpn.isRevoked()) {
            fail(Reason.VPN_REVOKED, vpn.error());
            return false;
        }
        if (!localVip.equals(mVpnVip)) {
            mVpnVip = localVip;
            mVpnRequestedAt = now;
            mVpnWasUp = false;
            stage("VPN: establishing TUN device for " + localVip + " (bridge -> SOCKS5 127.0.0.1:" + mSocks5Port + ")");
            vpn.bringUp(localVip, mSocks5Port);
            return false;
        }
        if (vpn.isUp()) {
            if (!mVpnWasUp) { mVpnWasUp = true; stage("VPN up: " + localVip + "/24, route " + TunnelConfig.SUBNET_CIDR + " only"); }
            return true;
        }
        String err = vpn.error();
        if (!err.isEmpty() && now - mVpnRequestedAt > 2000) {
            fail(err.toLowerCase(Locale.ROOT).contains("denied") || err.toLowerCase(Locale.ROOT).contains("consent") ? Reason.VPN_DENIED : Reason.VPN_FAILED, err);
            return false;
        }
        if (now - mVpnRequestedAt > VPN_TIMEOUT_MS) {
            fail(Reason.VPN_FAILED, "TUN device did not come up in " + (VPN_TIMEOUT_MS / 1000) + " s");
            return false;
        }
        if (mVpnWasUp) {
            mVpnWasUp = false;
            mVpnRequestedAt = now; // give the bridge a full window to come back
            stage("VPN: TUN device went down, waiting for it to return");
        }
        return false;
    }

    private boolean vpnUp() { return mParams.vpn == null ? false : mParams.vpn.isUp(); }

    private static final long TICK_MS = 2500;
    private static final long RELAY_TIMEOUT_MS = 30_000;
    private static final long HOST_FIND_TIMEOUT_MS = 60_000;
    private static final long ESTABLISH_TIMEOUT_MS = 45_000;
    private static final long RECONNECT_GIVE_UP_MS = 90_000;
    private static final long END_GRACE_MS = 5000;
    private static final int MAX_ENGINE_RESTARTS = 3;

    private final TunnelEnvironment mEnv;
    private final File mCacheDir;
    private final Listener mListener;
    private final Params mParams;
    private final TunnelManager mTunnel;
    private final LinkedBlockingQueue<Runnable> mCommands = new LinkedBlockingQueue<>();
    private final Thread mWorker;
    private final String mMemberId = UUID.randomUUID().toString();
    private final long mCreatedAt = System.currentTimeMillis();
    private final String mSessionId = UUID.randomUUID().toString();

    private volatile VirtualLanState mState;
    private volatile boolean mRunning = true;
    private volatile boolean mFinished;

    // host
    private LanPortProxy mProxy;
    private SessionDirectoryServer mDirectory;
    private LanHostDetector mDetector;
    private volatile SessionDirectoryServer.WorldInfo mWorld = SessionDirectoryServer.WorldInfo.closed();
    private volatile boolean mWorldDirty = true;
    private volatile int mInGame;
    private final Set<String> mKnownMemberIds = new HashSet<>();
    private final Map<String, String> mMemberNames = new HashMap<>();

    // guest
    private SessionDirectoryClient mClient;
    private String mMemberToken;
    private int mLocalGamePort;
    private int mLocalDirectoryPort;
    private String mHostVip = "";
    private int mHostProxyPort, mHostDirectoryPort;
    private boolean mForwardsAdded;
    private boolean mJoined;
    // guest: verification of the real game path (status ping through the tunnel)
    private static final long GAME_PING_INTERVAL_MS = 8000;
    private static final long GAME_PING_FAST_MS = 1500;
    private volatile boolean mGamePingRunning;
    private volatile boolean mGameReachable;
    private volatile double mGameLatencyMs = -1;
    private volatile long mGamePingAt;
    private volatile int mGamePingFailures;
    private boolean mGameStageLogged;
    private int mHeartbeatFailures;
    private long mLastHeartbeatOk;
    private boolean mLastWorldOpen;
    private Boolean mLastDirect;

    // shared
    private final List<Event> mEvents = new ArrayList<>();
    private long mEventSeq;
    private int mEngineRestarts;
    private long mPhaseStarted;
    private long mLostSince;
    private long mNetworkChangedAt;
    private String mEngineVersion = "";
    private List<String> mRelayNames = new ArrayList<>();
    private boolean mEndRequested;
    private Reason mEndReason = Reason.LEFT;

    public VirtualLanSession(@NonNull TunnelEnvironment env, @NonNull File cacheDir, @NonNull Params params, @NonNull Listener listener) {
        mEnv = env;
        mCacheDir = cacheDir;
        mParams = params;
        mListener = listener;
        mTunnel = new TunnelManager(env);
        mState = new VirtualLanState.Builder().role(params.role).phase(Phase.STARTING)
                .sessionCode(params.code != null ? params.code.display : "")
                .expiresAt(mCreatedAt + params.maxDurationMs).build();
        mWorker = new Thread(this::run, "vlan-session");
        mWorker.setDaemon(true);
    }

    @NonNull public VirtualLanState state() { return mState; }
    @NonNull public List<String> engineLog() { return mTunnel.recentLog(); }
    public boolean isFinished() { return mFinished; }

    public void start() { mWorker.start(); }

    /** Ends the session: guests are told first (host), then everything is torn down */
    public void end(@NonNull Reason reason) {
        mCommands.offer(() -> requestEnd(reason, ""));
    }

    public void kick(@NonNull String memberId) {
        mCommands.offer(() -> {
            if (mDirectory != null && mDirectory.kick(memberId)) {
                String name = mMemberNames.get(memberId);
                mKnownMemberIds.remove(memberId);
                addEvent(Event.Type.PLAYER_LEFT, name != null ? name : "");
                publish(mState.buildUpon());
            }
        });
    }

    /** Wi-Fi <-> mobile data switch: the engine reconnects by itself, the UI shows it honestly */
    public void onNetworkChanged() {
        mCommands.offer(() -> {
            mNetworkChangedAt = System.currentTimeMillis();
            if (mState.phase.isConnected()) {
                mLostSince = System.currentTimeMillis();
                addEvent(Event.Type.RECONNECTING, "");
                publish(mState.buildUpon().phase(Phase.RECONNECTING));
            }
        });
    }

    /** Game log line (host side LAN port detection) */
    public void onLogLine(@Nullable String line) {
        LanHostDetector detector = mDetector;
        if (detector != null) detector.onLogLine(line);
    }

    // ───────────────────────────── worker ─────────────────────────────

    private void run() {
        try {
            if (mParams.role == Role.HOST) startHost(); else startGuest();
            long nextTick = 0;
            while (mRunning) {
                long now = System.currentTimeMillis();
                long wait = Math.max(0, nextTick - now);
                Runnable cmd = mCommands.poll(wait, TimeUnit.MILLISECONDS);
                if (cmd != null) {
                    cmd.run();
                    continue;
                }
                nextTick = System.currentTimeMillis() + TICK_MS;
                if (mEndRequested) {
                    // grace period so guests can read the "ending" flag, then tear down
                    if (System.currentTimeMillis() - mPhaseStarted >= END_GRACE_MS) break;
                    continue;
                }
                if (mParams.role == Role.HOST) tickHost(); else tickGuest();
                if (System.currentTimeMillis() > mState.expiresAt && mState.expiresAt > 0) {
                    requestEnd(Reason.EXPIRED, "");
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            mEnv.log("session error: " + t);
            fail(Reason.INTERNAL, String.valueOf(t.getMessage()));
        } finally {
            teardown();
        }
    }

    private void requestEnd(Reason reason, String detail) {
        if (mEndRequested) return;
        mEndRequested = true;
        mEndReason = reason;
        mRunning = true; // the loop drains the grace period and exits by itself
        if (mDirectory != null) mDirectory.setEnding(true);
        if (mClient != null && mJoined && mMemberToken != null && reason != Reason.KICKED && reason != Reason.HOST_ENDED) {
            mClient.leave(mMemberId, mMemberToken); // worker thread, bounded by the client timeout
        }
        publish(mState.buildUpon().phase(Phase.ENDED).reason(reason, detail));
        mPhaseStarted = System.currentTimeMillis();
        if (mParams.role != Role.HOST || mDirectory == null || mDirectory.members().isEmpty()) {
            // nobody to notify: stop right away
            mRunning = false;
        }
    }

    private void fail(Reason reason, String detail) {
        if (mEndRequested) return;
        stage("FAILED " + reason + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
        mEndRequested = true;
        mEndReason = reason;
        publish(mState.buildUpon().phase(Phase.FAILED).reason(reason, detail));
        mRunning = false;
    }

    private void teardown() {
        mRunning = false;
        try { if (mDetector != null) mDetector.stop(); } catch (Exception ignored) {}
        try { if (mDirectory != null) mDirectory.stop(); } catch (Exception ignored) {}
        try { if (mProxy != null) mProxy.stop(); } catch (Exception ignored) {}
        try { if (mParams.vpn != null) mParams.vpn.takeDown(); } catch (Exception ignored) {}
        try { mTunnel.stop(); } catch (Exception ignored) {}
        mFinished = true;
        VirtualLanState s = mState;
        if (s.phase != Phase.FAILED && s.phase != Phase.ENDED) {
            s = s.buildUpon().phase(Phase.ENDED).reason(mEndReason, "").build();
            mState = s;
        }
        try { mListener.onFinished(s); } catch (Exception ignored) {}
    }

    private final List<String> mStages = new ArrayList<>();
    private final long mStartedAt = System.currentTimeMillis();
    private String mLastStage = "";

    /** Secret-free, timestamped stage trail surfaced in Diagnostics. Consecutive duplicates collapse. */
    private void stage(String msg) {
        if (msg == null || msg.equals(mLastStage)) return;
        mLastStage = msg;
        String line = String.format(Locale.US, "+%.1fs %s", (System.currentTimeMillis() - mStartedAt) / 1000.0, msg);
        mStages.add(line);
        while (mStages.size() > 40) mStages.remove(0);
        mEnv.log("stage: " + msg);
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    private void publish(VirtualLanState.Builder b) {
        VirtualLanState s = b.events(mEvents).stages(mStages).build();
        mState = s;
        try { mListener.onStateChanged(s); } catch (Exception ignored) {}
    }

    private void addEvent(Event.Type type, String arg) {
        mEvents.add(new Event(++mEventSeq, type, arg));
        while (mEvents.size() > 12) mEvents.remove(0);
    }

    private void setPhase(Phase phase) {
        if (mState.phase == phase) return;
        mPhaseStarted = System.currentTimeMillis();
        publish(mState.buildUpon().phase(phase));
    }

    // ───────────────────────────── common start ─────────────────────────────

    /** Engine self test + relay probing; returns the resolved relay URIs or fails the session */
    @Nullable
    private List<String> prepareEngine() {
        if (!mTunnel.isSupported()) {
            fail(Reason.UNSUPPORTED_DEVICE, "");
            return null;
        }
        try {
            mEngineVersion = mTunnel.selfTest();
        } catch (IOException e) {
            mEnv.log("engine self test failed: " + e.getMessage());
            fail(Reason.ENGINE_FAILED, e.getMessage());
            return null;
        }
        List<RelayNodes.Node> nodes = RelayNodes.load(mCacheDir, mParams.userRelayUris, mParams.allowRemoteNodeList);
        List<RelayNodes.Probed> probed = RelayNodes.probe(nodes);
        if (probed.isEmpty()) {
            fail(Reason.NO_RELAY_REACHABLE, "");
            return null;
        }
        List<String> uris = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (RelayNodes.Probed p : probed) {
            uris.add(p.resolvedUri);
            names.add(p.node.name + (p.rttMs >= 0 ? " (" + p.rttMs + " ms)" : ""));
        }
        mRelayNames = names;
        mEnv.log("relay nodes: " + names);
        return uris;
    }

    private TunnelConfig baseConfig(List<String> relayUris) {
        TunnelConfig c = new TunnelConfig();
        c.networkName = mParams.code.networkName;
        c.networkSecret = mParams.code.networkSecret;
        c.disableP2p = mParams.relayOnly;
        if (mParams.vpn != null || mParams.alwaysSocks5) {
            if (mSocks5Port <= 0) mSocks5Port = PortUtils.ephemeral("127.0.0.1");
            c.socks5Port = mSocks5Port;
        }
        c.peers.addAll(relayUris);
        c.stunServers.addAll(RelayNodes.resolveStun());
        c.listeners.add("tcp://0.0.0.0:0");
        c.listeners.add("udp://0.0.0.0:0");
        return c;
    }

    private boolean startEngine(TunnelConfig config) {
        try {
            mTunnel.start(config);
            stage("engine started (rpc 127.0.0.1:" + mTunnel.rpcPort() + (config.disableP2p ? ", relay-only" : "") + ")");
            return true;
        } catch (IOException e) {
            stage("engine start failed: " + e.getMessage());
            mEnv.log("engine start failed: " + e.getMessage());
            fail(Reason.ENGINE_FAILED, e.getMessage());
            return false;
        }
    }

    /** Restarts a crashed engine a few times before giving up */
    private boolean ensureEngineAlive(TunnelConfig config) {
        if (mTunnel.isAlive()) return true;
        Integer exit = mTunnel.exitCode();
        if (mEngineRestarts >= MAX_ENGINE_RESTARTS) {
            fail(Reason.ENGINE_CRASHED, engineDeathDetail(exit));
            return false;
        }
        mEngineRestarts++;
        stage("engine died (exit " + exit + "), restart " + mEngineRestarts + "/" + MAX_ENGINE_RESTARTS);
        mEnv.log("engine died (exit " + exit + "), restart " + mEngineRestarts);
        mTunnel.stop();
        try { Thread.sleep(1000L * mEngineRestarts); } catch (InterruptedException ignored) {}
        if (!mState.phase.isBusy()) setPhase(Phase.RECONNECTING);
        mForwardsAdded = false;
        return startEngine(config);
    }

    /** Exit code + the last meaningful engine line, so the failure page says what actually happened */
    private String engineDeathDetail(@Nullable Integer exit) {
        StringBuilder sb = new StringBuilder();
        if (exit != null) {
            sb.append("exit ").append(exit);
            if (exit == 137 || exit == 9 || exit == 143) sb.append(" \u2013 killed by the system");
            else if (exit == 134 || exit == 139 || exit == 132) sb.append(" \u2013 engine crashed");
            else if (exit == 1 || exit == 2) sb.append(" \u2013 engine rejected its configuration");
        }
        String last = null;
        List<String> log = mTunnel.recentLog();
        for (int i = log.size() - 1; i >= 0 && i >= log.size() - 40; i--) {
            String line = log.get(i);
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("error") || lower.contains("panic") || lower.contains("fatal") || lower.contains("failed")
                    || lower.contains("invalid") || lower.contains("denied") || lower.contains("not found")) {
                last = line.trim();
                break;
            }
        }
        if (last == null && !log.isEmpty()) last = log.get(log.size() - 1).trim();
        if (last != null && !last.isEmpty()) {
            if (last.length() > 220) last = last.substring(0, 220) + "\u2026";
            if (sb.length() > 0) sb.append('\n');
            sb.append(last);
        }
        return sb.toString();
    }

    // ───────────────────────────── host ─────────────────────────────

    private TunnelConfig mHostConfig;
    private boolean mRelayStageLogged;
    private boolean mHostRelayLogged;

    private void startHost() {
        setPhase(Phase.STARTING);
        List<String> relays = prepareEngine();
        if (relays == null) return;

        mProxy = new LanPortProxy(PortUtils.pick(null, mParams.proxyPort, 10), 0, count -> {
            mInGame = count;
            if (mDirectory != null) mDirectory.setInGame(count);
            mWorldDirty = true;
        });
        try {
            mProxy.start();
        } catch (IOException e) {
            fail(Reason.PORT_BUSY, "proxy " + e.getMessage());
            return;
        }
        mDirectory = new SessionDirectoryServer(mParams.code.authToken, mSessionId, mParams.playerName,
                mCreatedAt + mParams.maxDurationMs, mProxy.listenPort(), () -> mWorldDirty = true);
        try {
            mDirectory.start(mParams.directoryPort);
        } catch (IOException e) {
            fail(Reason.PORT_BUSY, "directory " + e.getMessage());
            return;
        }
        mDetector = new LanHostDetector((info, status) -> {
            boolean wasOpen = mWorld.open;
            mWorld = info;
            mDirectory.setWorld(info);
            if (info.open) mProxy.retarget(info.lanPort);
            if (info.open && !wasOpen) { addEvent(Event.Type.WORLD_OPENED, info.worldName); stage("LAN world detected on port " + info.lanPort + " -> proxy retargeted"); }
            if (!info.open && wasOpen) { addEvent(Event.Type.WORLD_CLOSED, ""); stage("LAN world closed"); }
            mWorldDirty = true;
            mCommands.offer(this::publishHost);
        });
        mDetector.start();

        mHostConfig = baseConfig(relays);
        mHostConfig.hostname = TunnelConfig.HOST_NAME_PREFIX + mProxy.listenPort() + "-" + mDirectory.port();
        mHostConfig.ipv4 = TunnelConfig.HOST_VIP;
        mHostConfig.tcpWhitelist.add(mProxy.listenPort());
        mHostConfig.tcpWhitelist.add(mDirectory.port());
        stage("host services up: proxy 0.0.0.0:" + mProxy.listenPort() + ", directory 0.0.0.0:" + mDirectory.port() + ", hostname " + mHostConfig.hostname);
        if (!startEngine(mHostConfig)) return;

        publish(mState.buildUpon().phase(Phase.DISCOVERING).hostVip(TunnelConfig.HOST_VIP)
                .ports(mProxy.listenPort(), mDirectory.port(), 0).engineVersion(mEngineVersion)
                .relay(0, -1, mRelayNames));
        mPhaseStarted = System.currentTimeMillis();
    }

    private List<TunnelManager.PeerInfo> mLastPeers = new ArrayList<>();

    private void tickHost() {
        if (!ensureEngineAlive(mHostConfig)) return;
        List<TunnelManager.PeerInfo> peers = mTunnel.queryPeers();
        mLastPeers = peers;
        int relays = 0;
        double bestRelay = -1;
        for (TunnelManager.PeerInfo p : peers) {
            if (p.isPublicServer()) {
                relays++;
                if (p.latencyMs >= 0 && (bestRelay < 0 || p.latencyMs < bestRelay)) bestRelay = p.latencyMs;
            }
        }
        Phase phase = mState.phase;
        long now = System.currentTimeMillis();
        if (relays > 0) {
            if (!mHostRelayLogged) { mHostRelayLogged = true; stage("relay network joined (" + relays + " node" + (relays == 1 ? "" : "s") + ", vip " + mTunnel.queryLocalIp(peers) + ")"); }
            boolean vpnOk = vpnReady(TunnelConfig.HOST_VIP);
            if (mEndRequested) return;
            if (!vpnOk && !phase.isConnected()) {
                publishHost(phase, peers, relays, bestRelay);
                return;
            }
            if (phase == Phase.DISCOVERING || phase == Phase.RECONNECTING) {
                if (phase == Phase.RECONNECTING) addEvent(Event.Type.RECOVERED, "");
                phase = Phase.CONNECTED_DIRECT; // host is online on the relay network
                mPhaseStarted = now;
            }
            mLostSince = 0;
        } else {
            if (phase == Phase.DISCOVERING && now - mPhaseStarted > RELAY_TIMEOUT_MS) {
                fail(Reason.NO_RELAY_REACHABLE, "");
                return;
            }
            if (phase.isConnected()) {
                phase = Phase.RECONNECTING;
                mLostSince = now;
                stage("all relay nodes lost, reconnecting");
                addEvent(Event.Type.RECONNECTING, "");
            } else if (phase == Phase.RECONNECTING && mLostSince > 0 && now - mLostSince > RECONNECT_GIVE_UP_MS) {
                fail(Reason.RELAY_LOST, "");
                return;
            }
        }
        publishHost(phase, peers, relays, bestRelay);
    }

    private void publishHost() {
        if (mEndRequested) return;
        int relays = 0;
        double best = -1;
        for (TunnelManager.PeerInfo p : mLastPeers) if (p.isPublicServer()) { relays++; if (p.latencyMs >= 0 && (best < 0 || p.latencyMs < best)) best = p.latencyMs; }
        publishHost(mState.phase, mLastPeers, relays, best);
    }

    private void publishHost(Phase phase, List<TunnelManager.PeerInfo> peers, int relays, double bestRelay) {
        // members with live path info from the peer table
        List<VirtualLanState.Member> members = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();
        String nat = "";
        for (TunnelManager.PeerInfo p : peers) if (p.isLocal()) nat = p.natType;
        for (SessionDirectoryServer.Member m : mDirectory.members()) {
            double latency = -1;
            Boolean direct = null;
            for (TunnelManager.PeerInfo p : peers) {
                if (!p.ipv4.isEmpty() && p.ipv4.equals(m.vip)) {
                    latency = p.latencyMs;
                    direct = p.isDirect();
                }
            }
            members.add(new VirtualLanState.Member(m.id, m.name, m.vip, m.joinedAt, latency, direct));
            currentIds.add(m.id);
            mMemberNames.put(m.id, m.name);
            if (mKnownMemberIds.add(m.id)) addEvent(Event.Type.PLAYER_JOINED, m.name);
        }
        for (String id : new ArrayList<>(mKnownMemberIds)) {
            if (!currentIds.contains(id)) {
                mKnownMemberIds.remove(id);
                String name = mMemberNames.remove(id);
                addEvent(Event.Type.PLAYER_LEFT, name != null ? name : "");
            }
        }
        SessionDirectoryServer.WorldInfo w = mWorld;
        publish(mState.buildUpon().phase(phase)
                .relay(relays, bestRelay, mRelayNames).nat(nat).localVip(TunnelConfig.HOST_VIP).vpn(vpnUp(), mSocks5Port)
                .world(w.open, w.worldName, w.motd, w.versionName, w.lanPort, w.online, w.max)
                .inGame(mInGame).members(members));
        mWorldDirty = false;
    }

    // ───────────────────────────── guest ─────────────────────────────

    private TunnelConfig mGuestConfig;

    private void startGuest() {
        setPhase(Phase.STARTING);
        List<String> relays = prepareEngine();
        if (relays == null) return;
        mGuestConfig = baseConfig(relays);
        mGuestConfig.hostname = TunnelConfig.GUEST_NAME_PREFIX + Integer.toHexString(mMemberId.hashCode() & 0xffff);
        mGuestConfig.dhcp = true;
        if (!startEngine(mGuestConfig)) return;
        publish(mState.buildUpon().phase(Phase.DISCOVERING).engineVersion(mEngineVersion).relay(0, -1, mRelayNames));
        mPhaseStarted = System.currentTimeMillis();
    }

    private void tickGuest() {
        if (!ensureEngineAlive(mGuestConfig)) return;
        List<TunnelManager.PeerInfo> peers = mTunnel.queryPeers();
        int relays = 0;
        double bestRelay = -1;
        String nat = "";
        TunnelManager.PeerInfo host = null;
        String localVip = "";
        for (TunnelManager.PeerInfo p : peers) {
            if (p.isLocal()) { nat = p.natType; localVip = p.ipv4; continue; }
            if (p.isPublicServer()) {
                relays++;
                if (p.latencyMs >= 0 && (bestRelay < 0 || p.latencyMs < bestRelay)) bestRelay = p.latencyMs;
            } else if (p.hostname != null && p.hostname.startsWith(TunnelConfig.HOST_NAME_PREFIX) && !p.ipv4.isEmpty()) {
                host = p;
            }
        }
        long now = System.currentTimeMillis();
        Phase phase = mState.phase;
        VirtualLanState.Builder b = mState.buildUpon().relay(relays, bestRelay, mRelayNames).nat(nat).localVip(localVip);

        if (phase == Phase.DISCOVERING) {
            if (relays > 0 && !mRelayStageLogged) { mRelayStageLogged = true; stage("relay network joined (" + relays + " node" + (relays == 1 ? "" : "s") + ", vip " + localVip + ", nat " + nat + ")"); }
            if (relays == 0 && now - mPhaseStarted > RELAY_TIMEOUT_MS) { fail(Reason.NO_RELAY_REACHABLE, ""); return; }
            if (host == null) {
                if (now - mPhaseStarted > HOST_FIND_TIMEOUT_MS) { fail(Reason.HOST_NOT_FOUND, ""); return; }
                publish(b);
                return;
            }
            if (!parseHostPorts(host.hostname)) { fail(Reason.HOST_NOT_FOUND, "bad host name"); return; }
            mHostVip = host.ipv4;
            stage("host peer found: " + host.ipv4 + " path=" + host.cost + " lat=" + (host.latencyMs >= 0 ? Math.round(host.latencyMs) + "ms" : "?")
                    + (host.tunnelProto.isEmpty() ? "" : " via " + host.tunnelProto) + " (proxy " + mHostProxyPort + ", directory " + mHostDirectoryPort + ")");
            phase = Phase.ESTABLISHING;
            mPhaseStarted = now;
            b.phase(phase).hostVip(mHostVip);
            publish(b);
        }

        if (phase == Phase.ESTABLISHING) {
            if (host != null && !host.ipv4.equals(mHostVip)) { mHostVip = host.ipv4; mForwardsAdded = false; }
            if (!mForwardsAdded && !addForwards()) {
                String why = mTunnel.lastCliError();
                stage("port-forward add failed" + (why.isEmpty() ? "" : ": " + why));
                if (now - mPhaseStarted > ESTABLISH_TIMEOUT_MS) { fail(Reason.HOST_UNREACHABLE, "port-forward: " + (why.isEmpty() ? "no local port" : why)); return; }
                publish(b);
                return;
            }
            if (!vpnReady(localVip)) {
                if (mEndRequested) return;
                mPhaseStarted = now; // the VPN has its own timeout; don't let it eat the establish budget
                publish(b.vpn(false, mSocks5Port));
                return;
            }
            try {
                SessionDirectoryClient.Snapshot snap = mClient.join(mMemberId, mParams.playerName, localVip);
                stage("directory join OK through tunnel (" + (host != null && host.isRelayed() ? "relay" : "direct") + ")");
                mMemberToken = snap.token;
                mJoined = true;
                mHeartbeatFailures = 0;
                mLastHeartbeatOk = now;
                phase = host != null && host.isRelayed() ? Phase.CONNECTED_RELAY : Phase.CONNECTED_DIRECT;
                mPhaseStarted = now;
                mLastDirect = phase == Phase.CONNECTED_DIRECT;
                addEvent(mLastDirect ? Event.Type.PATH_DIRECT : Event.Type.PATH_RELAY, "");
                applySnapshot(b, snap, host, phase);
                publish(b);
            } catch (SessionDirectoryClient.RejectedException e) {
                if (e.code == 403) { requestEnd(Reason.KICKED, ""); return; }
                stage("directory rejected join: HTTP " + e.code + " " + e.getMessage());
                if (now - mPhaseStarted > ESTABLISH_TIMEOUT_MS) { fail(Reason.HOST_UNREACHABLE, "directory HTTP " + e.code + " " + e.getMessage()); return; }
                publish(b);
            } catch (IOException e) {
                stage("directory join failed via 127.0.0.1:" + mLocalDirectoryPort + " -> " + mHostVip + ":" + mHostDirectoryPort + " \u2014 " + describe(e));
                if (now - mPhaseStarted > ESTABLISH_TIMEOUT_MS) { fail(Reason.HOST_UNREACHABLE, "directory: " + describe(e)); return; }
                publish(b);
            }
            return;
        }

        if (phase.isConnected() || phase == Phase.RECONNECTING) {
            vpnReady(localVip);
            if (mEndRequested) return;
            try {
                SessionDirectoryClient.Snapshot snap = mClient.heartbeat(mMemberId, mMemberToken, localVip);
                mHeartbeatFailures = 0;
                mLastHeartbeatOk = now;
                if (snap.ending) { requestEnd(Reason.HOST_ENDED, ""); return; }
                boolean direct = host == null ? (mLastDirect == null || mLastDirect) : !host.isRelayed();
                Phase next = direct ? Phase.CONNECTED_DIRECT : Phase.CONNECTED_RELAY;
                if (phase == Phase.RECONNECTING) addEvent(Event.Type.RECOVERED, "");
                if (mLastDirect != null && mLastDirect != direct) addEvent(direct ? Event.Type.PATH_DIRECT : Event.Type.PATH_RELAY, "");
                mLastDirect = direct;
                mLostSince = 0;
                applySnapshot(b, snap, host, next);
                publish(b);
            } catch (SessionDirectoryClient.RejectedException e) {
                if (e.code == 403) { requestEnd(Reason.KICKED, ""); return; }
                if (e.code == 404) {
                    // directory restarted (host restarted session): re-join
                    mJoined = false;
                    setPhase(Phase.ESTABLISHING);
                }
            } catch (IOException e) {
                mHeartbeatFailures++;
                if (mHeartbeatFailures >= 3 && phase.isConnected()) {
                    mLostSince = now;
                    stage("heartbeat lost x" + mHeartbeatFailures + " (" + describe(e) + "), reconnecting");
                    addEvent(Event.Type.RECONNECTING, "");
                    publish(b.phase(Phase.RECONNECTING));
                } else if (phase == Phase.RECONNECTING && mLostSince > 0 && now - mLostSince > RECONNECT_GIVE_UP_MS) {
                    fail(Reason.HOST_LOST, "");
                } else {
                    publish(b);
                }
            }
        }
    }

    private boolean parseHostPorts(String hostname) {
        try {
            String rest = hostname.substring(TunnelConfig.HOST_NAME_PREFIX.length());
            int dash = rest.indexOf('-');
            mHostProxyPort = Integer.parseInt(rest.substring(0, dash));
            mHostDirectoryPort = Integer.parseInt(rest.substring(dash + 1));
            return mHostProxyPort > 0 && mHostDirectoryPort > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean addForwards() {
        if (mLocalGamePort <= 0) mLocalGamePort = PortUtils.pick("127.0.0.1", mParams.guestGamePort, 10);
        if (mLocalDirectoryPort <= 0) mLocalDirectoryPort = PortUtils.ephemeral("127.0.0.1");
        if (mLocalGamePort <= 0 || mLocalDirectoryPort <= 0) return false;
        boolean ok = mTunnel.addPortForward("tcp", "127.0.0.1:" + mLocalGamePort, mHostVip + ":" + mHostProxyPort)
                && mTunnel.addPortForward("tcp", "127.0.0.1:" + mLocalDirectoryPort, mHostVip + ":" + mHostDirectoryPort);
        if (!ok) return false;
        mClient = new SessionDirectoryClient(mParams.code.authToken, mLocalDirectoryPort, 4000);
        mForwardsAdded = true;
        stage("port-forwards added: game 127.0.0.1:" + mLocalGamePort + " -> " + mHostVip + ":" + mHostProxyPort
                + ", directory 127.0.0.1:" + mLocalDirectoryPort + " -> " + mHostVip + ":" + mHostDirectoryPort);
        mEnv.log("forwards: game 127.0.0.1:" + mLocalGamePort + " -> " + mHostVip + ":" + mHostProxyPort
                + ", directory 127.0.0.1:" + mLocalDirectoryPort + " -> " + mHostVip + ":" + mHostDirectoryPort);
        return true;
    }

    private void applySnapshot(VirtualLanState.Builder b, SessionDirectoryClient.Snapshot snap, @Nullable TunnelManager.PeerInfo host, Phase phase) {
        if (snap.worldOpen && !mLastWorldOpen) addEvent(Event.Type.WORLD_OPENED, snap.worldName);
        if (!snap.worldOpen && mLastWorldOpen) addEvent(Event.Type.WORLD_CLOSED, "");
        mLastWorldOpen = snap.worldOpen;
        List<VirtualLanState.Member> members = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();
        for (SessionDirectoryClient.MemberInfo m : snap.members) {
            members.add(new VirtualLanState.Member(m.id, m.name, m.vip, m.joinedAt, -1, null));
            currentIds.add(m.id);
            mMemberNames.put(m.id, m.name);
            if (mKnownMemberIds.add(m.id) && !m.id.equals(mMemberId)) addEvent(Event.Type.PLAYER_JOINED, m.name);
        }
        for (String id : new ArrayList<>(mKnownMemberIds)) {
            if (!currentIds.contains(id)) {
                mKnownMemberIds.remove(id);
                String name = mMemberNames.remove(id);
                if (!id.equals(mMemberId)) addEvent(Event.Type.PLAYER_LEFT, name != null ? name : "");
            }
        }
        boolean viaVpn = vpnUp();
        String gameHost = viaVpn ? mHostVip : "127.0.0.1";
        int gamePort = viaVpn ? mHostProxyPort : mLocalGamePort;
        verifyGamePath(snap.worldOpen, gameHost, gamePort);
        boolean reachable = snap.worldOpen && mGameReachable;
        b.phase(phase).hostName(snap.hostName).hostVip(mHostVip).expiresAt(snap.expiresAt)
                .world(snap.worldOpen, snap.worldName, snap.motd, snap.versionName, snap.lanPort, snap.online, snap.max)
                .ports(mHostProxyPort, mHostDirectoryPort, mLocalGamePort).inGame(snap.inGame)
                .hostLatency(reachable && mGameLatencyMs >= 0 ? mGameLatencyMs : (host != null ? host.latencyMs : -1))
                .tunnelProto(host != null ? host.tunnelProto : "")
                .game(reachable, reachable ? mGameLatencyMs : -1).gameAddress(gameHost, gamePort).vpn(viaVpn, mSocks5Port)
                .members(members);
    }

    /**
     * The directory says the world is open; prove it from THIS device by pinging the Minecraft
     * server through the forwarded port (guest -> tunnel -> host proxy -> game). Runs off the
     * worker thread so heartbeats never wait on it. Join World unlocks only when this succeeds.
     */
    private String mGameTarget = "";

    private void verifyGamePath(boolean worldOpen, String gameHost, int gamePort) {
        String target = gameHost + ":" + gamePort;
        if (!target.equals(mGameTarget)) {
            // path changed (VPN came up / went down): re-verify from scratch
            mGameTarget = target;
            mGameReachable = false;
            mGameLatencyMs = -1;
            mGamePingFailures = 0;
            mGameStageLogged = false;
            mGamePingAt = 0;
        }
        if (!worldOpen) {
            if (mGameReachable) stage("game path idle: host world closed");
            mGameReachable = false;
            mGameLatencyMs = -1;
            mGamePingFailures = 0;
            mGameStageLogged = false;
            return;
        }
        long now = System.currentTimeMillis();
        long interval = mGameReachable ? GAME_PING_INTERVAL_MS : GAME_PING_FAST_MS;
        if (mGamePingRunning || now - mGamePingAt < interval || gamePort <= 0) return;
        mGamePingRunning = true;
        mGamePingAt = now;
        final int port = gamePort;
        final String hostAddr = gameHost;
        Thread t = new Thread(() -> {
            try {
                MinecraftPinger.Status st = MinecraftPinger.ping(hostAddr, port, 4000);
                if (st == null) throw new IOException("no Minecraft status reply");
                mGameLatencyMs = st.latencyMs;
                mGameReachable = true;
                mGamePingFailures = 0;
                if (!mGameStageLogged) {
                    mGameStageLogged = true;
                    mCommands.offer(() -> { stage("game server reachable via " + hostAddr + ":" + port + (hostAddr.startsWith("127.") ? " (loopback forward)" : " (VPN)") + ": " + st.motd + " " + st.versionName + " (" + st.latencyMs + " ms)"); republish(); });
                } else {
                    mCommands.offer(this::republish);
                }
            } catch (Exception e) {
                int failures = ++mGamePingFailures;
                boolean was = mGameReachable;
                if (failures >= 2) { mGameReachable = false; mGameLatencyMs = -1; }
                if (was && failures >= 2 || failures == 2) {
                    mGameStageLogged = false;
                    mCommands.offer(() -> { stage("game server not answering via " + hostAddr + ":" + port + ": " + describe(e)); republish(); });
                }
            } finally {
                mGamePingRunning = false;
            }
        }, "vlan-game-ping");
        t.setDaemon(true);
        t.start();
    }

    private void republish() {
        if (mEndRequested || mState.role != Role.GUEST || !mState.phase.isConnected()) return;
        boolean reachable = mState.worldOpen && mGameReachable;
        publish(mState.buildUpon().game(reachable, reachable ? mGameLatencyMs : -1)
                .hostLatency(reachable && mGameLatencyMs >= 0 ? mGameLatencyMs : mState.hostLatencyMs));
    }
}
