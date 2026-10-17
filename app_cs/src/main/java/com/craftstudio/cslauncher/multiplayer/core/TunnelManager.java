package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Owns the EasyTier process of one session.
 * <p>
 * The core is never started directly: a tiny shell watchdog starts it, records its pid and kills
 * it the moment our own process disappears (game exit, crash, "force close" from the
 * notification, Android low-memory kill). Together with the pid file sweep in {@link #killStale()}
 * this guarantees no orphan tunnel ever survives the launcher.
 * <p>
 * Diagnostics (peers, latency, direct vs. relayed, NAT type) come from {@code easytier-cli -o json}
 * through the loopback-only RPC portal.
 */
public final class TunnelManager {
    private static final String PID_FILE = "easytier.pid";
    private static final String CONFIG_FILE = "easytier.toml";
    private static final int LOG_LINES = 160;

    /** One row of {@code easytier-cli peer} */
    public static final class PeerInfo {
        public final String ipv4;
        public final String hostname;
        /** "Local", "p2p", "relay(...)" or a hop count */
        public final String cost;
        public final double latencyMs;
        public final double lossRate;
        public final String tunnelProto;
        public final String natType;
        public final String version;

        PeerInfo(String ipv4, String hostname, String cost, double latencyMs, double lossRate, String tunnelProto, String natType, String version) {
            this.ipv4 = ipv4;
            this.hostname = hostname;
            this.cost = cost;
            this.latencyMs = latencyMs;
            this.lossRate = lossRate;
            this.tunnelProto = tunnelProto;
            this.natType = natType;
            this.version = version;
        }

        public boolean isLocal() { return "Local".equalsIgnoreCase(cost); }
        public boolean isPublicServer() { return hostname != null && hostname.startsWith("PublicServer"); }
        public boolean isDirect() { return "p2p".equalsIgnoreCase(cost); }
        public boolean isRelayed() { return cost != null && !isLocal() && !isDirect(); }

        @Override public String toString() {
            return hostname + "@" + ipv4 + " " + cost + " " + latencyMs + "ms " + tunnelProto + " " + natType;
        }
    }

    private final TunnelEnvironment mEnv;
    private final ArrayDeque<String> mLog = new ArrayDeque<>(LOG_LINES);
    private final List<String> mScrub = new ArrayList<>();
    private Process mProcess;
    private Thread mReader;
    private volatile int mRpcPort;
    private volatile boolean mStarted;
    private volatile Integer mExitCode;

    public TunnelManager(@NonNull TunnelEnvironment env) {
        mEnv = env;
    }

    public boolean isSupported() {
        File core = mEnv.coreBinary();
        File cli = mEnv.cliBinary();
        return core != null && core.canExecute() && cli != null && cli.canExecute();
    }

    public int rpcPort() { return mRpcPort; }
    public boolean isStarted() { return mStarted; }

    public boolean isAlive() {
        Process p = mProcess;
        if (p == null) return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** The exit code when the watchdog ended, null while running */
    @Nullable public Integer exitCode() { return mExitCode; }

    /** Last diagnostics lines with session secrets removed; safe to show in a help screen */
    @NonNull
    public List<String> recentLog() {
        synchronized (mLog) { return new ArrayList<>(mLog); }
    }

    /**
     * Runs {@code easytier-core --version}. The binary is a static musl build executed from
     * nativeLibraryDir; this proves the device can exec it before a session is attempted.
     */
    @NonNull
    public String selfTest() throws IOException {
        File core = mEnv.coreBinary();
        if (core == null || !core.canExecute()) throw new IOException("tunnel engine missing for this device ABI");
        ProcessBuilder pb = new ProcessBuilder(core.getAbsolutePath(), "--version");
        pb.redirectErrorStream(true);
        pb.directory(mEnv.workDir());
        Process p = pb.start();
        String out = readAll(p.getInputStream(), 8000, p);
        int code;
        try { code = waitFor(p, 8000); } catch (InterruptedException e) { throw new IOException("interrupted"); }
        if (code != 0 || !out.contains("easytier")) throw new IOException("tunnel engine failed to start (exit " + code + "): " + out.trim());
        return out.trim();
    }

    /** Kills a tunnel left behind by a previous (crashed) process, if any */
    public void killStale() {
        File pidFile = new File(mEnv.workDir(), PID_FILE);
        if (!pidFile.exists()) return;
        try {
            String text = readFile(pidFile).trim();
            int pid = Integer.parseInt(text);
            if (pid > 1 && pid != mEnv.myPid()) {
                File cmdline = new File("/proc/" + pid + "/cmdline");
                String cmd = cmdline.exists() ? readFile(cmdline) : "";
                if (cmd.contains("easytier")) {
                    mEnv.log("killing stale tunnel pid " + pid);
                    mEnv.killProcess(pid);
                }
            }
        } catch (Exception ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        pidFile.delete();
    }

    public synchronized void start(@NonNull TunnelConfig config) throws IOException {
        if (mStarted) throw new IOException("tunnel already started");
        File core = mEnv.coreBinary();
        if (core == null || !core.canExecute()) throw new IOException("tunnel engine missing for this device ABI");
        killStale();
        mExitCode = null;
        mRpcPort = PortUtils.pick("127.0.0.1", 15888 + (int) (System.nanoTime() % 2000), 50);
        if (mRpcPort <= 0) throw new IOException("no free local port for the tunnel control channel");
        synchronized (mScrub) {
            mScrub.clear();
            if (config.networkSecret != null && config.networkSecret.length() > 4) mScrub.add(config.networkSecret);
            if (config.networkName != null && config.networkName.length() > 4) mScrub.add(config.networkName);
        }

        File workDir = mEnv.workDir();
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        File configFile = new File(workDir, CONFIG_FILE);
        writePrivate(configFile, config.toToml());
        File pidFile = new File(workDir, PID_FILE);

        // The watchdog: start the core, remember its pid, kill it when our process is gone or on SIGTERM.
        String script = "CORE=\"$1\"; CFG=\"$2\"; PARENT=\"$3\"; PIDF=\"$4\"; RPC=\"$5\"; "
                + "\"$CORE\" -c \"$CFG\" -r \"$RPC\" & C=$!; echo $C > \"$PIDF\"; "
                + "trap 'kill $C 2>/dev/null' TERM INT HUP; "
                + "while [ -e /proc/$PARENT ] && kill -0 $C 2>/dev/null; do sleep 1; done; "
                + "kill $C 2>/dev/null; sleep 1; kill -9 $C 2>/dev/null; wait $C 2>/dev/null; rm -f \"$PIDF\"";
        ProcessBuilder pb = new ProcessBuilder(mEnv.shell(), "-c", script, "cslp-tunnel",
                core.getAbsolutePath(), configFile.getAbsolutePath(), String.valueOf(mEnv.myPid()), pidFile.getAbsolutePath(),
                "127.0.0.1:" + mRpcPort);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", workDir.getAbsolutePath());
        pb.environment().put("TMPDIR", workDir.getAbsolutePath());
        pb.environment().put("RUST_BACKTRACE", "0");
        mProcess = pb.start();
        mStarted = true;
        mReader = new Thread(() -> drain(mProcess), "vlan-tunnel-log");
        mReader.setDaemon(true);
        mReader.start();
        mEnv.log("tunnel started, rpc port " + mRpcPort);
    }

    public synchronized void stop() {
        Process p = mProcess;
        mProcess = null;
        mStarted = false;
        if (p != null) {
            p.destroy(); // SIGTERM -> the watchdog trap kills the core
            try {
                if (waitFor(p, 2500) == Integer.MIN_VALUE) {
                    killByPidFile();
                    p.destroy();
                }
            } catch (InterruptedException ignored) {
            }
        }
        killByPidFile();
        File config = new File(mEnv.workDir(), CONFIG_FILE);
        //noinspection ResultOfMethodCallIgnored
        config.delete();
        mEnv.log("tunnel stopped");
    }

    private void killByPidFile() {
        File pidFile = new File(mEnv.workDir(), PID_FILE);
        try {
            if (pidFile.exists()) {
                int pid = Integer.parseInt(readFile(pidFile).trim());
                if (pid > 1) mEnv.killProcess(pid);
            }
        } catch (Exception ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        pidFile.delete();
    }

    // ───────────────────────────── diagnostics via cli ─────────────────────────────

    @NonNull
    public List<PeerInfo> queryPeers() {
        String out = cli(4000, "-o", "json", "peer");
        if (out == null) return Collections.emptyList();
        List<PeerInfo> peers = new ArrayList<>();
        try {
            int start = out.indexOf('[');
            if (start < 0) return peers;
            JSONArray arr = new JSONArray(out.substring(start));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String ipv4 = o.optString("ipv4", "");
                int slash = ipv4.indexOf('/');
                if (slash > 0) ipv4 = ipv4.substring(0, slash);
                peers.add(new PeerInfo(ipv4, o.optString("hostname", ""), o.optString("cost", ""),
                        parseDouble(o.optString("lat_ms", "")), parseDouble(o.optString("loss_rate", "")),
                        o.optString("tunnel_proto", ""), o.optString("nat_type", ""), o.optString("version", "")));
            }
        } catch (Exception e) {
            mEnv.log("peer list parse failed: " + e.getMessage());
        }
        return peers;
    }

    /** Our own virtual IP once DHCP / fixed assignment settled, "" otherwise */
    @NonNull
    public String queryLocalIp(@NonNull List<PeerInfo> peers) {
        for (PeerInfo p : peers) if (p.isLocal()) return p.ipv4;
        return "";
    }

    public boolean addPortForward(@NonNull String proto, @NonNull String bind, @NonNull String dst) {
        String out = cli(5000, "port-forward", "add", proto, bind, dst);
        if (out == null) return false;
        if (out.toLowerCase(Locale.ROOT).contains("error")) {
            mLastCliError = "cli: " + oneLine(out);
            return false;
        }
        return true;
    }

    /** Human-readable reason for the last failed CLI call (no secrets: only CLI output / exit codes) */
    @NonNull
    public String lastCliError() { String e = mLastCliError; return e == null ? "" : e; }

    private volatile String mLastCliError;

    private static String oneLine(String s) {
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) + "\u2026" : t;
    }

    @Nullable
    private String cli(long timeoutMs, String... args) {
        File cli = mEnv.cliBinary();
        if (cli == null) { mLastCliError = "cli binary missing"; return null; }
        if (mRpcPort <= 0 || !mStarted) { mLastCliError = "engine not running"; return null; }
        List<String> cmd = new ArrayList<>();
        cmd.add(cli.getAbsolutePath());
        cmd.add("-p");
        cmd.add("127.0.0.1:" + mRpcPort);
        Collections.addAll(cmd, args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(mEnv.workDir());
            Process p = pb.start();
            String out = readAll(p.getInputStream(), timeoutMs, p);
            int code = waitFor(p, 500);
            if (code != 0) {
                mLastCliError = "cli " + args[0] + (args.length > 1 ? " " + args[1] : "") + " exit " + code + (out.isEmpty() ? "" : ": " + oneLine(out));
                return null;
            }
            mLastCliError = null;
            return out;
        } catch (Exception e) {
            mLastCliError = "cli exec failed: " + e.getClass().getSimpleName() + " " + e.getMessage();
            return null;
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void drain(Process p) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = scrub(line);
                synchronized (mLog) {
                    if (mLog.size() >= LOG_LINES) mLog.pollFirst();
                    mLog.addLast(line);
                }
            }
        } catch (IOException ignored) {
        }
        try {
            mExitCode = p.waitFor();
        } catch (InterruptedException ignored) {
        }
        if (mProcess == p) mStarted = false;
        mEnv.log("tunnel process ended" + (mExitCode != null ? " (exit " + mExitCode + ")" : ""));
    }

    private String scrub(String line) {
        synchronized (mScrub) {
            for (String s : mScrub) if (line.contains(s)) line = line.replace(s, "\u2022\u2022\u2022");
        }
        return line;
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.replace("%", "").trim()); } catch (Exception e) { return -1; }
    }

    private static String readAll(InputStream in, long timeoutMs, Process p) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, available));
                if (n < 0) break;
                bos.write(buf, 0, n);
                continue;
            }
            try {
                p.exitValue();
                // process ended: drain the rest
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                break;
            } catch (IllegalThreadStateException running) {
                try { Thread.sleep(20); } catch (InterruptedException e) { break; }
            }
        }
        if (System.currentTimeMillis() >= deadline) p.destroy();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** @return the exit code, or Integer.MIN_VALUE on timeout */
    private static int waitFor(Process p, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                return p.exitValue();
            } catch (IllegalThreadStateException running) {
                if (System.currentTimeMillis() >= deadline) return Integer.MIN_VALUE;
                TimeUnit.MILLISECONDS.sleep(25);
            }
        }
    }

    private static String readFile(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writePrivate(File f, String content) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }
        //noinspection ResultOfMethodCallIgnored
        f.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        f.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        f.setWritable(false, false);
        //noinspection ResultOfMethodCallIgnored
        f.setWritable(true, true);
    }
}
