package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the port of the host's "Open to LAN" world and keeps watching it.
 * <p>
 * Three independent signals feed candidate ports:
 * <ul>
 *   <li>the game log ("Started serving on 12345" / "Local game hosted on port 12345"),</li>
 *   <li>Minecraft's own LAN announcement (UDP multicast 224.0.2.60:4445, "[AD]12345[/AD]"),
 *       which the game keeps sending every 1.5 s while the world is open,</li>
 *   <li>a Server List Ping against the candidate every few seconds.</li>
 * </ul>
 * Only a successful ping turns a candidate into an open world: this is what makes the detected
 * port trustworthy, lets us read the real world name / player count, and notices when the host
 * closes the world (pings start failing) or re-opens it on a new port.
 */
public final class LanHostDetector {
    public interface Listener {
        /** Called on the detector thread whenever the open/closed state, port or status changes */
        void onWorldChanged(@NonNull SessionDirectoryServer.WorldInfo info, @Nullable MinecraftPinger.Status status);
    }

    private static final Pattern LOG_PORT = Pattern.compile("(?:Started serving on|Local game hosted on port)\\s+(\\d{2,5})");
    private static final Pattern AD_PORT = Pattern.compile("\\[AD\\]\\s*(?:[^\\[\\]]*?:)?(\\d{2,5})\\s*\\[/AD\\]");
    private static final String MC_GROUP = "224.0.2.60";
    private static final int MC_PORT = 4445;
    private static final long VERIFY_INTERVAL_MS = 4000L;
    private static final long FAST_VERIFY_MS = 800L;
    private static final int PING_TIMEOUT_MS = 1500;
    private static final int FAILURES_TO_CLOSE = 3;

    private final Listener mListener;
    private volatile boolean mRunning;
    private volatile int mCandidatePort;
    private volatile long mCandidateSeenAt;
    private volatile int mOpenPort;
    private volatile int mFailures;
    @Nullable private volatile MinecraftPinger.Status mLastStatus;
    private Thread mVerifyThread;
    private Thread mMulticastThread;
    private MulticastSocket mSocket;
    private final Object mWake = new Object();

    public LanHostDetector(@NonNull Listener listener) {
        mListener = listener;
    }

    public int openPort() { return mOpenPort; }
    public boolean isWorldOpen() { return mOpenPort > 0; }
    @Nullable public MinecraftPinger.Status lastStatus() { return mLastStatus; }

    public synchronized void start() {
        if (mRunning) return;
        mRunning = true;
        mVerifyThread = new Thread(this::verifyLoop, "vlan-lan-verify");
        mVerifyThread.setDaemon(true);
        mVerifyThread.start();
        mMulticastThread = new Thread(this::multicastLoop, "vlan-lan-multicast");
        mMulticastThread.setDaemon(true);
        mMulticastThread.start();
    }

    public synchronized void stop() {
        mRunning = false;
        MulticastSocket socket = mSocket;
        if (socket != null) socket.close();
        wake();
    }

    /** Feed from the game log tap (any thread) */
    public void onLogLine(@Nullable String line) {
        if (line == null || !mRunning) return;
        if (line.indexOf("serving on") < 0 && line.indexOf("hosted on port") < 0) return;
        Matcher m = LOG_PORT.matcher(line);
        if (m.find()) offerCandidate(parsePort(m.group(1)), "log");
    }

    /** A port the user typed / a port known from elsewhere */
    public void offerCandidate(int port, String source) {
        if (port <= 0 || port > 65535) return;
        mCandidateSeenAt = System.currentTimeMillis();
        if (port != mCandidatePort) {
            mCandidatePort = port;
            mFailures = 0;
            wake();
        }
    }

    private void wake() {
        synchronized (mWake) { mWake.notifyAll(); }
    }

    // ───────────────────────────── verification ─────────────────────────────

    private void verifyLoop() {
        while (mRunning) {
            int port = mCandidatePort;
            long wait = VERIFY_INTERVAL_MS;
            if (port > 0) {
                MinecraftPinger.Status status = MinecraftPinger.ping("127.0.0.1", port, PING_TIMEOUT_MS);
                if (!mRunning) break;
                if (status != null) {
                    mFailures = 0;
                    boolean changed = mOpenPort != port || statusChanged(mLastStatus, status);
                    mOpenPort = port;
                    mLastStatus = status;
                    if (changed) publish(status);
                } else {
                    int failures = ++mFailures;
                    if (failures == 1 && mOpenPort == port) wait = FAST_VERIFY_MS; // recheck quickly before declaring closed
                    if (failures >= FAILURES_TO_CLOSE) {
                        boolean wasOpen = mOpenPort > 0;
                        mOpenPort = 0;
                        mLastStatus = null;
                        // A log/multicast hint older than 30 s and never answering is stale: drop it
                        if (System.currentTimeMillis() - mCandidateSeenAt > 30_000L) mCandidatePort = 0;
                        if (wasOpen) publish(null);
                    } else if (failures < FAILURES_TO_CLOSE && mOpenPort == 0) {
                        wait = FAST_VERIFY_MS; // fresh candidate, Minecraft may still be binding
                    }
                }
            }
            synchronized (mWake) {
                try { mWake.wait(wait); } catch (InterruptedException ignored) {}
            }
        }
    }

    private static boolean statusChanged(@Nullable MinecraftPinger.Status a, @NonNull MinecraftPinger.Status b) {
        if (a == null) return true;
        return a.playersOnline != b.playersOnline || a.playersMax != b.playersMax
                || !eq(a.motd, b.motd) || !eq(a.versionName, b.versionName);
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private void publish(@Nullable MinecraftPinger.Status status) {
        SessionDirectoryServer.WorldInfo info = status == null
                ? SessionDirectoryServer.WorldInfo.closed()
                : new SessionDirectoryServer.WorldInfo(true, mOpenPort, status.motd, status.worldName(),
                status.versionName, status.protocol, status.playersOnline, status.playersMax);
        try {
            mListener.onWorldChanged(info, status);
        } catch (Exception ignored) {
        }
    }

    // ───────────────────────────── multicast ─────────────────────────────

    private void multicastLoop() {
        while (mRunning) {
            MulticastSocket socket = null;
            try {
                socket = new MulticastSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(MC_PORT));
                socket.setSoTimeout(2000);
                mSocket = socket;
                joinEverywhere(socket);
                byte[] buf = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                while (mRunning) {
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    Matcher m = AD_PORT.matcher(text);
                    if (m.find()) offerCandidate(parsePort(m.group(1)), "multicast");
                }
            } catch (IOException e) {
                // Port busy (another listener) or no multicast interface: the log path still works
                if (!mRunning) break;
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            } finally {
                if (socket != null) socket.close();
                if (mSocket == socket) mSocket = null;
            }
        }
    }

    private static void joinEverywhere(MulticastSocket socket) {
        InetAddress group;
        try {
            group = InetAddress.getByName(MC_GROUP);
        } catch (IOException e) {
            return;
        }
        boolean joined = false;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                try {
                    if (!iface.isUp() || !iface.supportsMulticast()) continue;
                    socket.joinGroup(new InetSocketAddress(group, MC_PORT), iface);
                    joined = true;
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        if (!joined) {
            try { socket.joinGroup(group); } catch (IOException ignored) {}
        }
    }

    private static int parsePort(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }
}
