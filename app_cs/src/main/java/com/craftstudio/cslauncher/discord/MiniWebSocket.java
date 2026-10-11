package com.craftstudio.cslauncher.discord;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal RFC 6455 WebSocket client over TLS (text frames, ping/pong, close, fragmentation).
 * Enough for the Discord gateway without pulling a networking library into the app.
 * {@link #run} blocks the calling thread until the connection ends.
 */
final class MiniWebSocket {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;

    interface Listener {
        void onOpen();
        void onText(@NonNull String text);
        void onClosed(int code, @NonNull String reason);
    }

    private final String mHost;
    private final String mPath;
    private final SecureRandom mRandom = new SecureRandom();
    private volatile SSLSocket mSocket;
    private volatile OutputStream mOut;
    private volatile boolean mClosed;

    MiniWebSocket(@NonNull String host, @NonNull String pathAndQuery) {
        mHost = host;
        mPath = pathAndQuery;
    }

    /** Connects and reads until the connection ends; listener callbacks run on this thread */
    void run(@NonNull Listener listener) throws IOException {
        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(mHost, 443);
        mSocket = socket;
        try {
            enableSni(socket);
            socket.setSoTimeout(0);
            socket.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(mHost, socket.getSession())) {
                throw new IOException("Certificate does not match " + mHost);
            }
            InputStream in = new BufferedInputStream(socket.getInputStream());
            mOut = socket.getOutputStream();
            handshake(in);
            listener.onOpen();
            readLoop(in, listener);
        } finally {
            mClosed = true;
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void enableSni(@NonNull SSLSocket socket) {
        // Conscrypt already sends SNI for createSocket(host, port); this covers old providers
        try {
            socket.getClass().getMethod("setHostname", String.class).invoke(socket, mHost);
        } catch (Exception ignored) {}
    }

    private void handshake(@NonNull InputStream in) throws IOException {
        byte[] nonce = new byte[16];
        mRandom.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String request = "GET " + mPath + " HTTP/1.1\r\n"
                + "Host: " + mHost + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: https://discord.com\r\n"
                + "User-Agent: CSLauncherPlus\r\n\r\n";
        synchronized (this) {
            mOut.write(request.getBytes(StandardCharsets.US_ASCII));
            mOut.flush();
        }
        String status = readLine(in);
        if (status == null || !status.contains(" 101")) throw new IOException("Upgrade refused: " + status);
        String accept = null;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) {
                accept = line.substring(colon + 1).trim();
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            String expected = Base64.encodeToString(digest, Base64.NO_WRAP);
            if (!expected.equals(accept)) throw new IOException("Bad Sec-WebSocket-Accept");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private void readLoop(@NonNull InputStream in, @NonNull Listener listener) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = -1;
        while (!mClosed) {
            int b0 = in.read();
            if (b0 < 0) throw new EOFException("Connection closed");
            int b1 = readByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7F;
            if (length == 126) {
                length = ((long) readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | readByte(in);
            }
            if (length > MAX_MESSAGE) throw new IOException("Frame too large");
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) length];
            readFully(in, payload);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];

            switch (opcode) {
                case 0x0: // continuation
                case 0x1: // text
                case 0x2: // binary
                    if (opcode != 0x0) {
                        message.reset();
                        messageOpcode = opcode;
                    }
                    message.write(payload);
                    if (message.size() > MAX_MESSAGE) throw new IOException("Message too large");
                    if (fin) {
                        if (messageOpcode == 0x1) listener.onText(message.toString("UTF-8"));
                        message.reset();
                        messageOpcode = -1;
                    }
                    break;
                case 0x8: { // close
                    int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
                    String reason = payload.length > 2
                            ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                    try {
                        sendFrame(0x8, payload.length >= 2 ? new byte[]{payload[0], payload[1]} : new byte[0]);
                    } catch (IOException ignored) {}
                    mClosed = true;
                    listener.onClosed(code, reason);
                    return;
                }
                case 0x9: // ping
                    sendFrame(0xA, payload);
                    break;
                default: // pong / reserved
                    break;
            }
        }
    }

    boolean sendText(@NonNull String text) {
        try {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Sends a close frame and drops the connection */
    void close(int code) {
        if (mClosed) return;
        try {
            sendFrame(0x8, new byte[]{(byte) (code >> 8), (byte) code});
        } catch (IOException ignored) {}
        mClosed = true;
        SSLSocket socket = mSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private synchronized void sendFrame(int opcode, @NonNull byte[] payload) throws IOException {
        OutputStream out = mOut;
        if (out == null) throw new IOException("Not connected");
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode);
        int length = payload.length;
        if (length < 126) {
            frame.write(0x80 | length);
        } else if (length <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write(length >> 8);
            frame.write(length);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) frame.write((int) (((long) length) >> (8 * i)));
        }
        byte[] mask = new byte[4];
        mRandom.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < length; i++) frame.write(payload[i] ^ mask[i % 4]);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static int readByte(@NonNull InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("Connection closed");
        return b;
    }

    private static void readFully(@NonNull InputStream in, @NonNull byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) throw new EOFException("Connection closed");
            read += n;
        }
    }

    @Nullable
    private static String readLine(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 8192) throw new IOException("Header too long");
        }
        if (c == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }
}
