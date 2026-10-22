package com.craftstudio.cslauncher.multiplayer;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Cross-process state of {@link VirtualLanVpnService}.
 * <p>
 * The VPN service (and the native tun2socks bridge it hosts) lives in its own {@code :vpn}
 * process so a native fault in the bridge can never take the game down. The session, which runs
 * in {@code :game}, learns about the bridge through this small JSON file: the service rewrites it
 * atomically on every state change and refreshes the timestamp as a heartbeat while it is up.
 * A reader that sees {@code UP} with a stale heartbeat knows the bridge process died.
 */
public final class VpnBridgeState {
    private VpnBridgeState() {}

    public static final long HEARTBEAT_MS = 1500;
    /** Heartbeat older than this means the :vpn process is gone (crashed or killed). */
    public static final long STALE_MS = 6000;

    public static final class Snapshot {
        @NonNull public final String state;   // DOWN, STARTING, UP, REVOKED, FAILED, or "" when no file
        @NonNull public final String error;
        @NonNull public final String vip;
        public final int pid;
        public final long timestamp;
        public final boolean stale;

        Snapshot(@NonNull String state, @NonNull String error, @NonNull String vip, int pid, long timestamp, boolean stale) {
            this.state = state; this.error = error; this.vip = vip; this.pid = pid; this.timestamp = timestamp; this.stale = stale;
        }

        public boolean isUp() { return "UP".equals(state) && !stale; }
        public boolean isRevoked() { return "REVOKED".equals(state); }
        public boolean isFailed() { return "FAILED".equals(state); }
        /** True when the service said UP/STARTING but stopped heart-beating: the bridge process is dead. */
        public boolean processDied() { return stale && ("UP".equals(state) || "STARTING".equals(state)); }
    }

    @NonNull
    public static File dir(@NonNull Context ctx) {
        File d = new File(ctx.getFilesDir(), "virtual_lan");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    @NonNull public static File stateFile(@NonNull Context ctx) { return new File(dir(ctx), "vpn_state.json"); }
    /** Log written by the VPN service itself (establish/teardown decisions, errors). */
    @NonNull public static File serviceLog(@NonNull Context ctx) { return new File(dir(ctx), "vpn_service.log"); }
    /** Log written by the native tun2socks bridge (hev-socks5-tunnel {@code misc.log-file}). */
    @NonNull public static File bridgeLog(@NonNull Context ctx) { return new File(dir(ctx), "vpn_bridge.log"); }

    static void write(@NonNull Context ctx, @NonNull String state, @NonNull String error, @NonNull String vip) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("error", error);
            o.put("vip", vip);
            o.put("pid", android.os.Process.myPid());
            o.put("ts", System.currentTimeMillis());
            File target = stateFile(ctx);
            File tmp = new File(target.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(target);
            }
        } catch (Throwable ignored) {
        }
    }

    static void clear(@NonNull Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        stateFile(ctx).delete();
    }

    @NonNull
    public static Snapshot read(@NonNull Context ctx) {
        File f = stateFile(ctx);
        if (!f.isFile()) return new Snapshot("", "", "", 0, 0, false);
        try {
            String text = readAll(f, 8192);
            JSONObject o = new JSONObject(text);
            long ts = o.optLong("ts", 0);
            boolean stale = System.currentTimeMillis() - ts > STALE_MS;
            return new Snapshot(o.optString("state", ""), o.optString("error", ""), o.optString("vip", ""), o.optInt("pid", 0), ts, stale);
        } catch (Throwable t) {
            return new Snapshot("", "", "", 0, 0, false);
        }
    }

    /** Appends one timestamped line to the service log, keeping the file small. */
    static void log(@NonNull Context ctx, @NonNull String line) {
        try {
            File f = serviceLog(ctx);
            if (f.length() > 256 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            String stamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date());
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write((stamp + " [pid " + android.os.Process.myPid() + "] " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Last {@code maxBytes} of a file as text, "" when missing. */
    @NonNull
    public static String tail(@Nullable File f, int maxBytes) {
        if (f == null || !f.isFile()) return "";
        try {
            return readAll(f, maxBytes);
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    private static String readAll(File f, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}
