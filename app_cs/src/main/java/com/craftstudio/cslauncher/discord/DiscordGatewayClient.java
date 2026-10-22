package com.craftstudio.cslauncher.discord;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * CS Launcher Plus — Lightweight, self-contained RFC 6455 WebSocket client
 * that connects directly to the Discord Gateway (wss://gateway.discord.gg/?v=10&encoding=json).
 *
 * Implements Heartbeat (OP 1), Identify (OP 2), Presence Update (OP 3),
 * and handles Hello (OP 10), Dispatch (OP 0), Heartbeat ACK (OP 11), Reconnect (OP 7).
 *
 * Zero external dependencies: pure standard Java SSLSocket + Android JSONObject.
 */
public class DiscordGatewayClient {
    private static final String TAG = "DiscordGateway";
    private static final String GATEWAY_HOST = "gateway.discord.gg";
    private static final int GATEWAY_PORT = 443;
    private static final String GATEWAY_PATH = "/?v=10&encoding=json";

    public static final int STATUS_OFFLINE = 0;
    public static final int STATUS_CONNECTING = 1;
    public static final int STATUS_LIVE = 2;
    public static final int STATUS_AUTH_ERROR = 3;
    public static final int STATUS_DISCONNECTED = 4;

    public interface GatewayListener {
        void onStatusChanged(int statusCode, String message);
        void onReady(String username, String userId);
    }

    private final SecureRandom mRandom = new SecureRandom();
    private final Object mWriteLock = new Object();

    private Socket mSocket;
    private OutputStream mOutputStream;
    private InputStream mInputStream;

    private Thread mReaderThread;
    private ScheduledExecutorService mHeartbeatExecutor;
    private ScheduledFuture<?> mHeartbeatTask;

    private volatile boolean mRunning = false;
    private volatile boolean mConnected = false;
    private volatile int mStatusCode = STATUS_OFFLINE;
    private volatile String mStatusMessage = "Offline";

    private String mToken;
    private String mAppId;
    private JSONObject mPendingPresence;
    private int mLastSequence = -1;
    private GatewayListener mListener;

    public void setListener(GatewayListener listener) {
        this.mListener = listener;
    }

    public int getStatusCode() {
        return mStatusCode;
    }

    public String getStatusMessage() {
        return mStatusMessage;
    }

    public boolean isConnected() {
        return mConnected;
    }

    private void updateStatus(int code, String message) {
        mStatusCode = code;
        mStatusMessage = message;
        if (mListener != null) {
            try {
                mListener.onStatusChanged(code, message);
            } catch (Throwable t) {
                Log.w(TAG, "Listener error", t);
            }
        }
    }

    public synchronized void connect(String token, String appId, JSONObject initialPresence) {
        if (token == null || token.trim().isEmpty()) {
            updateStatus(STATUS_OFFLINE, "No Discord token configured");
            return;
        }

        disconnect();

        this.mToken = token.trim();
        this.mAppId = appId != null && !appId.trim().isEmpty() ? appId.trim() : null;
        this.mPendingPresence = initialPresence;
        this.mRunning = true;
        this.mLastSequence = -1;

        updateStatus(STATUS_CONNECTING, "Connecting to Discord Gateway...");

        mReaderThread = new Thread(this::connectionLoop, "CS-DiscordGateway-Worker");
        mReaderThread.setDaemon(true);
        mReaderThread.start();
    }

    public synchronized void disconnect() {
        mRunning = false;
        mConnected = false;

        stopHeartbeat();

        if (mSocket != null) {
            try {
                mSocket.close();
            } catch (Throwable ignored) {}
            mSocket = null;
        }

        if (mReaderThread != null && mReaderThread.isAlive()) {
            mReaderThread.interrupt();
            mReaderThread = null;
        }

        updateStatus(STATUS_OFFLINE, "Disconnected");
    }

    public void updatePresence(JSONObject presence) {
        this.mPendingPresence = presence;
        if (mConnected && presence != null) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("op", 3);
                payload.put("d", presence);
                sendJson(payload);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to send presence update", t);
            }
        }
    }

    private void connectionLoop() {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(GATEWAY_HOST, GATEWAY_PORT);
            sslSocket.setSoTimeout(0); // Infinite read timeout; heartbeats manage connection liveness
            sslSocket.startHandshake();

            mSocket = sslSocket;
            mOutputStream = new BufferedOutputStream(sslSocket.getOutputStream());
            mInputStream = new BufferedInputStream(sslSocket.getInputStream());

            // 1. Send WebSocket Upgrade Request
            byte[] nonce = new byte[16];
            mRandom.nextBytes(nonce);
            String secKey = Base64.encodeToString(nonce, Base64.NO_WRAP);

            String request = "GET " + GATEWAY_PATH + " HTTP/1.1\r\n" +
                    "Host: " + GATEWAY_HOST + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + secKey + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "User-Agent: CS-Launcher-Plus/1.4\r\n\r\n";

            mOutputStream.write(request.getBytes(StandardCharsets.UTF_8));
            mOutputStream.flush();

            // 2. Read HTTP 101 Handshake Response
            BufferedReader reader = new BufferedReader(new InputStreamReader(mInputStream, StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            if (statusLine == null || !statusLine.contains("101")) {
                Log.e(TAG, "WebSocket handshake failed: " + statusLine);
                updateStatus(STATUS_DISCONNECTED, "Handshake failed: " + (statusLine != null ? statusLine : "EOF"));
                return;
            }

            // Read past the rest of the HTTP headers until empty line
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Header consumption
            }

            // 3. Read RFC 6455 WebSocket Frames
            DataInputStream dis = new DataInputStream(mInputStream);
            while (mRunning && !Thread.currentThread().isInterrupted()) {
                int b1 = dis.read();
                if (b1 == -1) break;

                int opcode = b1 & 0x0F;
                int b2 = dis.read();
                if (b2 == -1) break;

                boolean masked = (b2 & 0x80) != 0;
                long len = b2 & 0x7F;

                if (len == 126) {
                    len = dis.readUnsignedShort();
                } else if (len == 127) {
                    len = dis.readLong();
                }

                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    dis.readFully(mask);
                }

                if (len > 1_000_000) {
                    Log.w(TAG, "Frame payload too large: " + len);
                    break;
                }

                byte[] payload = new byte[(int) len];
                dis.readFully(payload);

                if (masked && mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }

                if (opcode == 0x1) {
                    // Text Frame
                    String text = new String(payload, StandardCharsets.UTF_8);
                    handleGatewayMessage(text);
                } else if (opcode == 0x8) {
                    // Close Frame
                    int closeCode = 1000;
                    if (payload.length >= 2) {
                        closeCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                    }
                    Log.w(TAG, "Gateway closed connection with code: " + closeCode);
                    if (closeCode == 4004) {
                        updateStatus(STATUS_AUTH_ERROR, "Invalid Discord token (4004)");
                    } else {
                        updateStatus(STATUS_DISCONNECTED, "Gateway closed: " + closeCode);
                    }
                    break;
                } else if (opcode == 0x9) {
                    // Ping Frame -> Send Pong
                    sendFrame(0xA, payload);
                }
            }
        } catch (Throwable t) {
            if (mRunning) {
                Log.e(TAG, "Gateway socket error", t);
                updateStatus(STATUS_DISCONNECTED, "Connection error: " + t.getMessage());
            }
        } finally {
            mConnected = false;
            stopHeartbeat();
        }
    }

    private void handleGatewayMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            int op = json.getInt("op");

            if (json.has("s") && !json.isNull("s")) {
                mLastSequence = json.getInt("s");
            }

            if (op == 10) {
                // OP 10: Hello -> Extract heartbeat interval & Identify
                JSONObject d = json.getJSONObject("d");
                int heartbeatInterval = d.getInt("heartbeat_interval");
                startHeartbeat(heartbeatInterval);
                sendIdentify();
            } else if (op == 0) {
                // OP 0: Dispatch
                String t = json.optString("t", "");
                if ("READY".equals(t)) {
                    mConnected = true;
                    JSONObject d = json.optJSONObject("d");
                    String username = "User";
                    String userId = "";
                    if (d != null) {
                        JSONObject user = d.optJSONObject("user");
                        if (user != null) {
                            username = user.optString("username", "User");
                            userId = user.optString("id", "");
                        }
                    }
                    updateStatus(STATUS_LIVE, "Live on Discord: " + username);
                    if (mListener != null) {
                        mListener.onReady(username, userId);
                    }

                    // Push pending presence immediately if any
                    if (mPendingPresence != null) {
                        updatePresence(mPendingPresence);
                    }
                }
            } else if (op == 1) {
                // OP 1: Heartbeat request from server
                sendHeartbeat();
            } else if (op == 11) {
                // OP 11: Heartbeat ACK (connection healthy)
                Log.d(TAG, "Heartbeat ACK received");
            } else if (op == 7 || op == 9) {
                // OP 7 (Reconnect) or OP 9 (Invalid Session)
                Log.w(TAG, "Received reconnect/invalid session op=" + op);
                if (mRunning) {
                    disconnect();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error parsing message: " + text, t);
        }
    }

    private void sendIdentify() {
        try {
            JSONObject id = new JSONObject();
            id.put("op", 2);

            JSONObject d = new JSONObject();
            d.put("token", mToken);
            d.put("intents", 0);

            JSONObject props = new JSONObject();
            props.put("$os", "android");
            props.put("$browser", "CS Launcher Plus");
            props.put("$device", "CS Launcher Plus");
            d.put("properties", props);

            if (mPendingPresence != null) {
                d.put("presence", mPendingPresence);
            }

            id.put("d", d);
            sendJson(id);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to send Identify", t);
        }
    }

    private void startHeartbeat(int intervalMs) {
        stopHeartbeat();
        mHeartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        long jitter = (long) (intervalMs * mRandom.nextDouble());
        mHeartbeatTask = mHeartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeat,
                jitter,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void stopHeartbeat() {
        if (mHeartbeatTask != null) {
            mHeartbeatTask.cancel(true);
            mHeartbeatTask = null;
        }
        if (mHeartbeatExecutor != null) {
            mHeartbeatExecutor.shutdownNow();
            mHeartbeatExecutor = null;
        }
    }

    private void sendHeartbeat() {
        try {
            JSONObject hb = new JSONObject();
            hb.put("op", 1);
            if (mLastSequence >= 0) {
                hb.put("d", mLastSequence);
            } else {
                hb.put("d", JSONObject.NULL);
            }
            sendJson(hb);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to send heartbeat", t);
        }
    }

    private void sendJson(JSONObject json) throws IOException {
        byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
        sendFrame(0x1, payload);
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (mWriteLock) {
            if (mOutputStream == null) throw new IOException("Not connected");

            int len = payload != null ? payload.length : 0;
            byte b1 = (byte) (0x80 | (opcode & 0x0F));
            mOutputStream.write(b1);

            // Client-to-server frames MUST be masked
            if (len <= 125) {
                mOutputStream.write((byte) (0x80 | len));
            } else if (len <= 65535) {
                mOutputStream.write((byte) (0x80 | 126));
                mOutputStream.write((len >> 8) & 0xFF);
                mOutputStream.write(len & 0xFF);
            } else {
                mOutputStream.write((byte) (0x80 | 127));
                for (int i = 7; i >= 0; i--) {
                    mOutputStream.write((int) ((len >> (8 * i)) & 0xFF));
                }
            }

            byte[] mask = new byte[4];
            mRandom.nextBytes(mask);
            mOutputStream.write(mask);

            if (len > 0 && payload != null) {
                byte[] masked = new byte[len];
                for (int i = 0; i < len; i++) {
                    masked[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
                mOutputStream.write(masked);
            }

            mOutputStream.flush();
        }
    }
}
