package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host side: a fixed, well-known port (bound on all interfaces so the no-TUN tunnel can deliver
 * traffic to it) that forwards into the dynamic port Minecraft picked for "Open to LAN".
 * <p>
 * Keeping the public side fixed means guests never need to learn the LAN port, and the host can
 * re-open the world (new port) without anybody reconnecting the tunnel. The proxy also knows how
 * many game connections are currently flowing, which is real data for the "in game" counter.
 * <p>
 * Plain blocking I/O with two small daemon threads per connection: Minecraft traffic is a few
 * KB/s per player, so this costs nothing measurable, even on low-end phones.
 */
public final class LanPortProxy {
    public interface Listener {
        void onConnectionCountChanged(int count);
    }

    private static final int BUFFER = 16 * 1024;

    private final int mListenPort;
    private final Listener mListener;
    private volatile int mTargetPort;
    private volatile boolean mRunning;
    private ServerSocket mServer;
    private Thread mAcceptThread;
    private final Set<Socket> mOpen = java.util.Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final AtomicInteger mCount = new AtomicInteger();

    public LanPortProxy(int listenPort, int targetPort, @Nullable Listener listener) {
        mListenPort = listenPort;
        mTargetPort = targetPort;
        mListener = listener;
    }

    public int listenPort() { return mListenPort; }
    public int targetPort() { return mTargetPort; }
    public int connectionCount() { return mCount.get(); }

    /** The LAN port changed (world re-opened): new connections go to the new port */
    public void retarget(int targetPort) {
        if (targetPort == mTargetPort) return;
        mTargetPort = targetPort;
        // Existing sockets point to a dead server anyway, cut them so clients reconnect cleanly
        closeAllClients();
    }

    public synchronized void start() throws IOException {
        if (mRunning) return;
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress((InetAddress) null, mListenPort), 16);
        mServer = server;
        mRunning = true;
        mAcceptThread = new Thread(this::acceptLoop, "vlan-proxy-accept");
        mAcceptThread.setDaemon(true);
        mAcceptThread.start();
    }

    public synchronized void stop() {
        mRunning = false;
        ServerSocket server = mServer;
        mServer = null;
        if (server != null) try { server.close(); } catch (IOException ignored) {}
        closeAllClients();
    }

    public boolean isRunning() { return mRunning; }

    private void closeAllClients() {
        for (Socket s : mOpen) try { s.close(); } catch (IOException ignored) {}
    }

    private void acceptLoop() {
        ServerSocket server = mServer;
        while (mRunning && server != null && !server.isClosed()) {
            try {
                Socket client = server.accept();
                handle(client);
            } catch (SocketException e) {
                if (mRunning) sleepQuietly(100);
            } catch (IOException e) {
                sleepQuietly(100);
            }
        }
    }

    private void handle(Socket client) {
        int target = mTargetPort;
        if (target <= 0) {
            try { client.close(); } catch (IOException ignored) {}
            return;
        }
        Thread t = new Thread(() -> {
            Socket upstream = new Socket();
            try {
                client.setTcpNoDelay(true);
                upstream.setTcpNoDelay(true);
                upstream.connect(new InetSocketAddress("127.0.0.1", target), 4000);
            } catch (IOException e) {
                try { client.close(); } catch (IOException ignored) {}
                try { upstream.close(); } catch (IOException ignored) {}
                return;
            }
            mOpen.add(client);
            mOpen.add(upstream);
            notifyCount(mCount.incrementAndGet());
            Thread back = new Thread(() -> pump(upstream, client), "vlan-proxy-down");
            back.setDaemon(true);
            back.start();
            pump(client, upstream);
            try { back.join(2000); } catch (InterruptedException ignored) {}
            mOpen.remove(client);
            mOpen.remove(upstream);
            notifyCount(mCount.decrementAndGet());
        }, "vlan-proxy-up");
        t.setDaemon(true);
        t.start();
    }

    private static void pump(Socket from, Socket to) {
        byte[] buf = new byte[BUFFER];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
            try { to.shutdownOutput(); } catch (IOException ignored) {}
        } catch (IOException ignored) {
        } finally {
            try { from.close(); } catch (IOException ignored) {}
            try { to.close(); } catch (IOException ignored) {}
        }
    }

    private void notifyCount(int count) {
        if (mListener != null) mListener.onConnectionCountChanged(Math.max(0, count));
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
