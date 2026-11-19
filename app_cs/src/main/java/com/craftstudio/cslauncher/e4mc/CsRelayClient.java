package com.craftstudio.cslauncher.e4mc;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.DataInputStream;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TEMPORARY: the Craft Studio relay ({@code play.craftstudios.site:25210}) as an alternative
 * tunnel to e4mc. Zero dependencies: one TCP connection to the relay, {@code HOST <player>\n}
 * handshake answered by {@code OK <joinAddress>\n} (e.g. {@code rohit.play.craftstudios.site:25210}),
 * then yamux v0 frames; every stream the relay opens is bridged to the local LAN world. No door policy (access,
 * player limit, kick) is enforced here — that is e4mc's job and stays with e4mc. Will move
 * under the e4mc settings once the relay has domains and token authentication.
 *
 * <p>All socket writes go through {@link SafeOut}: {@code sendto(MSG_NOSIGNAL)} instead of
 * {@code write()}. The game runs in this very process and a plain write into a socket the peer
 * already closed raises SIGPIPE, which kills the whole process without a Java trace (the old
 * virtual-LAN tunnel died exactly like that on the first guest). With MSG_NOSIGNAL it is an
 * ordinary EPIPE and the stream is simply closed.
 */
public final class CsRelayClient {
    private static final String TAG = "CsRelay";
    public static final String DEFAULT_HOST = "play.craftstudios.site";
    public static final int DEFAULT_PORT = 25210;

    private static final int TYPE_DATA = 0, TYPE_WINDOW_UPDATE = 1, TYPE_PING = 2, TYPE_GO_AWAY = 3;
    private static final int FLAG_SYN = 0x1, FLAG_ACK = 0x2, FLAG_FIN = 0x4, FLAG_RST = 0x8;
    private static final int INITIAL_WINDOW = 256 * 1024;
    private static final byte[] EOF_SENTINEL = new byte[0];

    public interface Listener {
        /** Connected and registered as host; {@code joinAddress} is what guests type in. Relay thread. */
        void onConnected(@NonNull String joinAddress);
        /** Tunnel ended (any reason, including {@link #stop}). Relay thread. */
        void onDisconnected(@NonNull String reason);
        /** A guest stream opened or closed. Relay thread. */
        void onStreams(int open);
    }

    private static final class RelayStream {
        final int id;
        final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        final AtomicBoolean closed = new AtomicBoolean(false);
        final Object windowLock = new Object();
        int sendWindow = INITIAL_WINDOW;
        volatile Socket localSocket;
        volatile SafeOut localOut;
        RelayStream(int id) { this.id = id; }
    }

    private final String mHost; private final int mPort; private final int mLocalPort;
    private final Listener mListener;
    private final String mPlayerName;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final Object mWriteLock = new Object();
    private final ConcurrentHashMap<Integer, RelayStream> mStreams = new ConcurrentHashMap<>();
    private volatile Socket mRelaySocket;
    private volatile SafeOut mRelayOut;

    /** Socket writer that can never raise SIGPIPE (sendto with MSG_NOSIGNAL). */
    private static final class SafeOut {
        private static final int MSG_NOSIGNAL = 0x4000; // same value on every Linux ABI
        private final ParcelFileDescriptor pfd;
        SafeOut(Socket s) throws IOException { pfd = ParcelFileDescriptor.fromSocket(s); }
        void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n;
                try {
                    n = Os.sendto(pfd.getFileDescriptor(), b, off, len, MSG_NOSIGNAL, null, 0);
                } catch (ErrnoException e) {
                    throw new IOException("send: " + e.getMessage());
                }
                if (n <= 0) throw new IOException("send: no progress");
                off += n; len -= n;
            }
        }
        void close() { try { pfd.close(); } catch (IOException ignored) {} }
    }
    private volatile String mJoinAddress;

    public CsRelayClient(@Nullable String host, int port, int localMcPort, @Nullable String playerName, @NonNull Listener listener) {
        mPlayerName = playerName == null ? "" : playerName.trim().replaceAll("\\s+", "");
        mHost = host == null || host.isEmpty() ? DEFAULT_HOST : host;
        mPort = port <= 0 ? DEFAULT_PORT : port;
        mLocalPort = localMcPort;
        mListener = listener;
    }

    @NonNull public String relayAddress() { return mHost + ":" + mPort; }
    @Nullable public String joinAddress() { return mJoinAddress; }
    public int openStreams() { return mStreams.size(); }
    public boolean isRunning() { return mRunning.get(); }

    public void start() {
        if (!mRunning.compareAndSet(false, true)) return;
        Thread t = new Thread(this::mainLoop, "cs-relay-main");
        t.setDaemon(true);
        t.start();
    }

    public void stop() { stopInternal("Stopped by host"); }

    private void mainLoop() {
        try {
            String hello = mPlayerName.isEmpty() ? "HOST\n" : "HOST " + mPlayerName + "\n";
            Socket sock = handshake(hello);
            if (sock == null && !mPlayerName.isEmpty()) {
                // Relay that does not take a name yet closes the socket: fall back to the anonymous form.
                Log.w(TAG, "relay closed on named handshake; retrying anonymous");
                sock = handshake("HOST\n");
            }
            if (sock == null) throw new EOFException("Relay closed before handshake");
            DataInputStream dis = new DataInputStream(sock.getInputStream());
            mListener.onConnected(mJoinAddress);

            while (mRunning.get()) {
                int version = dis.readUnsignedByte();
                if (version != 0) throw new IllegalStateException("Bad yamux version: " + version);
                int type = dis.readUnsignedByte();
                int flags = dis.readUnsignedShort();
                int streamId = dis.readInt();
                int length = dis.readInt();
                switch (type) {
                    case TYPE_PING:
                        if ((flags & FLAG_SYN) != 0) sendFrame(TYPE_PING, FLAG_ACK, 0, null, 0, length);
                        break;
                    case TYPE_GO_AWAY:
                        stopInternal("Relay closed the tunnel");
                        return;
                    case TYPE_DATA:
                    case TYPE_WINDOW_UPDATE: {
                        byte[] payload = null;
                        if (type == TYPE_DATA && length > 0) {
                            if (length > 4 * 1024 * 1024) throw new IllegalStateException("Frame too large: " + length);
                            payload = new byte[length];
                            dis.readFully(payload);
                        }
                        if ((flags & FLAG_SYN) != 0) {
                            RelayStream st = new RelayStream(streamId);
                            mStreams.put(streamId, st);
                            mListener.onStreams(mStreams.size());
                            startStreamBridge(st);
                        }
                        RelayStream st = mStreams.get(streamId);
                        if (st != null) {
                            if (type == TYPE_WINDOW_UPDATE && length > 0) {
                                synchronized (st.windowLock) { st.sendWindow += length; st.windowLock.notifyAll(); }
                            }
                            if (payload != null) st.incoming.offer(payload);
                            if ((flags & FLAG_FIN) != 0) st.incoming.offer(EOF_SENTINEL);
                            if ((flags & FLAG_RST) != 0) closeStream(st, false);
                        }
                        break;
                    }
                    default:
                        // Unknown frame type: skip its payload if any
                        if (type == TYPE_DATA && length > 0) dis.skipBytes(length);
                }
            }
            stopInternal("Tunnel closed");
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (mRunning.get()) Log.w(TAG, "relay: " + why);
            stopInternal(why);
        }
    }

    /**
     * Connects, sends {@code hello}, reads the {@code OK <joinAddress>} line and stores the exact
     * address. Returns null (socket closed) when the relay hung up without answering, so the
     * caller can retry with another greeting; any other failure throws.
     */
    @Nullable
    private Socket handshake(@NonNull String hello) throws IOException {
        Socket sock = new Socket();
        sock.setTcpNoDelay(true);
        sock.connect(new InetSocketAddress(mHost, mPort), 10_000);
        mRelaySocket = sock;
        DataInputStream dis = new DataInputStream(sock.getInputStream());
        SafeOut dos = new SafeOut(sock);
        mRelayOut = dos;
        byte[] h = hello.getBytes(StandardCharsets.US_ASCII);
        dos.write(h, 0, h.length);
        String line;
        try {
            line = readAsciiLine(dis);
        } catch (EOFException e) {
            mRelayOut = null; mRelaySocket = null;
            dos.close();
            try { sock.close(); } catch (IOException ignored) {}
            return null;
        }
        if (!line.startsWith("OK")) {
            mRelayOut = null; mRelaySocket = null;
            dos.close();
            try { sock.close(); } catch (IOException ignored) {}
            throw new IllegalStateException(line.isEmpty() ? "Relay refused the host" : "Relay: " + line);
        }
        String addr = line.length() > 2 ? line.substring(2).trim() : "";
        // An answer without an address means guests join at the relay itself.
        if (addr.isEmpty() || !addr.contains(":")) addr = relayAddress();
        mJoinAddress = addr;
        return sock;
    }

    private void stopInternal(@NonNull String reason) {
        if (!mRunning.compareAndSet(true, false)) return;
        try { sendFrame(TYPE_GO_AWAY, 0, 0, null, 0, 0); } catch (Throwable ignored) {}
        for (RelayStream s : mStreams.values()) closeStream(s, false);
        mStreams.clear();
        Socket s = mRelaySocket;
        SafeOut o = mRelayOut;
        mRelaySocket = null;
        mRelayOut = null;
        if (o != null) o.close();
        if (s != null) try { s.close(); } catch (IOException ignored) {}
        mListener.onDisconnected(reason);
    }

    private void startStreamBridge(final RelayStream stream) {
        Thread in = new Thread(() -> {
            try {
                final Socket local = new Socket();
                local.setTcpNoDelay(true);
                local.connect(new InetSocketAddress("127.0.0.1", mLocalPort), 5_000);
                stream.localSocket = local;
                final SafeOut localOut = new SafeOut(local);
                stream.localOut = localOut;
                sendFrame(TYPE_WINDOW_UPDATE, FLAG_ACK, stream.id, null, 0, 0);

                // Local world -> relay
                Thread out = new Thread(() -> {
                    try {
                        InputStream localIn = local.getInputStream();
                        byte[] buf = new byte[16 * 1024];
                        int read;
                        outer:
                        while (!stream.closed.get() && mRunning.get() && (read = localIn.read(buf)) != -1) {
                            int offset = 0;
                            while (offset < read && !stream.closed.get()) {
                                int toSend;
                                synchronized (stream.windowLock) {
                                    while (stream.sendWindow <= 0 && !stream.closed.get()) stream.windowLock.wait(1000);
                                    if (stream.closed.get()) break outer;
                                    toSend = Math.min(read - offset, stream.sendWindow);
                                    stream.sendWindow -= toSend;
                                }
                                sendFrame(TYPE_DATA, 0, stream.id, buf, offset, toSend);
                                offset += toSend;
                            }
                        }
                        if (!stream.closed.get() && mRunning.get()) sendFrame(TYPE_WINDOW_UPDATE, FLAG_FIN, stream.id, null, 0, 0);
                    } catch (Exception e) {
                        closeStream(stream, true);
                    }
                }, "cs-relay-out-" + stream.id);
                out.setDaemon(true);
                out.start();

                // Relay -> local world
                int unacked = 0;
                while (!stream.closed.get() && mRunning.get()) {
                    byte[] chunk = stream.incoming.take();
                    if (chunk == EOF_SENTINEL) {
                        try { local.shutdownOutput(); } catch (IOException ignored) {}
                        break;
                    }
                    localOut.write(chunk, 0, chunk.length);
                    unacked += chunk.length;
                    if (unacked >= 32 * 1024) {
                        sendFrame(TYPE_WINDOW_UPDATE, 0, stream.id, null, 0, unacked);
                        unacked = 0;
                    }
                }
            } catch (Exception e) {
                closeStream(stream, true);
            } finally {
                if (mStreams.remove(stream.id) != null) mListener.onStreams(mStreams.size());
                SafeOut lo = stream.localOut;
                if (lo != null) lo.close();
                Socket ls = stream.localSocket;
                if (ls != null) try { ls.close(); } catch (IOException ignored) {}
            }
        }, "cs-relay-in-" + stream.id);
        in.setDaemon(true);
        in.start();
    }

    private void closeStream(RelayStream stream, boolean sendRst) {
        if (!stream.closed.compareAndSet(false, true)) return;
        if (mStreams.remove(stream.id) != null) mListener.onStreams(mStreams.size());
        stream.incoming.offer(EOF_SENTINEL);
        synchronized (stream.windowLock) { stream.windowLock.notifyAll(); }
        SafeOut lo = stream.localOut;
        if (lo != null) lo.close();
        Socket ls = stream.localSocket;
        if (ls != null) try { ls.close(); } catch (IOException ignored) {}
        if (sendRst && mRunning.get()) {
            try { sendFrame(TYPE_WINDOW_UPDATE, FLAG_RST, stream.id, null, 0, 0); } catch (Throwable ignored) {}
        }
    }

    private void sendFrame(int type, int flags, int streamId, @Nullable byte[] data, int offset, int length) throws IOException {
        SafeOut out = mRelayOut;
        if (out == null) return;
        int body = data != null && length > 0 ? length : 0;
        byte[] frame = new byte[12 + body];
        frame[0] = 0;
        frame[1] = (byte) type;
        frame[2] = (byte) (flags >>> 8); frame[3] = (byte) flags;
        frame[4] = (byte) (streamId >>> 24); frame[5] = (byte) (streamId >>> 16); frame[6] = (byte) (streamId >>> 8); frame[7] = (byte) streamId;
        frame[8] = (byte) (length >>> 24); frame[9] = (byte) (length >>> 16); frame[10] = (byte) (length >>> 8); frame[11] = (byte) length;
        if (body > 0) System.arraycopy(data, offset, frame, 12, body);
        synchronized (mWriteLock) { out.write(frame, 0, frame.length); }
    }

    private static String readAsciiLine(DataInputStream dis) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 256) {
            int b = dis.read();
            if (b == -1) throw new EOFException("Relay closed before handshake");
            if (b == '\n') return sb.toString().trim();
            sb.append((char) b);
        }
        throw new IllegalStateException("Handshake line too long");
    }
}
