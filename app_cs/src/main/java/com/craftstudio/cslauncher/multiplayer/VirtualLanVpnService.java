package com.craftstudio.cslauncher.multiplayer;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.multiplayer.core.TunnelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import hev.htproxy.TProxyService;

/**
 * The real Android VPN of Virtual LAN Multiplayer.
 * <p>
 * Establishes a TUN device that owns our virtual IP and routes <b>only</b> the virtual subnet
 * {@link TunnelConfig#SUBNET_CIDR} (nothing else - no DNS, no default route, no other apps'
 * traffic: the VPN is limited to this app with {@code addAllowedApplication}). Packets entering
 * the TUN are handed to hev-socks5-tunnel, which turns them into SOCKS5 connections to the
 * EasyTier engine's portal on 127.0.0.1; the engine carries them through the encrypted tunnel
 * (direct or relayed) to the host. So Minecraft can simply connect to {@code 10.144.144.1:port}.
 * <p>
 * State is exposed statically to the session running in this same {@code :game} process via
 * {@link AndroidVpnController}. Consent is obtained beforehand by {@link VpnConsentActivity}.
 */
public final class VirtualLanVpnService extends VpnService {
    private static final String TAG = AndroidTunnelEnvironment.TAG;
    public static final String ACTION_CONNECT = "com.craftstudio.cslauncher.multiplayer.VPN_CONNECT";
    public static final String ACTION_DISCONNECT = "com.craftstudio.cslauncher.multiplayer.VPN_DISCONNECT";
    public static final String EXTRA_VIP = "vip";
    public static final String EXTRA_SOCKS5_PORT = "socks5";
    /** pid of the process that owns the session; when it disappears the TUN is released (no orphan VPN). */
    public static final String EXTRA_OWNER_PID = "owner_pid";
    private int mOwnerPid;
    private static final int MTU = 1380; // EasyTier's default virtual MTU

    public enum State { DOWN, STARTING, UP, REVOKED, FAILED }

    // ── process-wide state (session and service share the :game process) ──
    private static volatile State sState = State.DOWN;
    private static volatile String sError = "";
    private static volatile String sVip = "";
    @Nullable private static volatile VirtualLanVpnService sInstance;

    @NonNull public static State state() { return sState; }
    @NonNull public static String error() { return sError; }
    @NonNull public static String currentVip() { return sVip; }

    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mHeartbeat = new Runnable() {
        @Override public void run() {
            if (mOwnerPid > 0 && !new File("/proc/" + mOwnerPid).exists()) {
                disconnect(State.DOWN, "owner process " + mOwnerPid + " is gone");
                stopSelf();
                return;
            }
            if (sState == State.UP && mBridgeRunning && !TProxyService.TProxyIsRunning()) {
                // The native bridge thread ended on its own (socks5 side gone, fatal config): report it
                publish(State.FAILED, "bridge stopped by itself (see vpn_bridge.log)");
                teardownQuietly();
                return;
            }
            publish(sState, sError);
            if (sState == State.UP || sState == State.STARTING) mHandler.postDelayed(this, VpnBridgeState.HEARTBEAT_MS);
        }
    };

    /** Mirrors the in-process state to the cross-process file (the session lives in :game, we live in :vpn). */
    private void publish(State state, String error) {
        sState = state;
        sError = error == null ? "" : error;
        VpnBridgeState.write(this, state.name(), sError, sVip);
    }

    private ParcelFileDescriptor mTun;
    private boolean mBridgeRunning;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        VpnBridgeState.log(this, "service created (process " + android.os.Process.myPid() + ", abi " + Build.SUPPORTED_ABIS[0] + ")");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CONNECT.equals(action)) {
            String vip = intent.getStringExtra(EXTRA_VIP);
            int socks = intent.getIntExtra(EXTRA_SOCKS5_PORT, 0);
            mOwnerPid = intent.getIntExtra(EXTRA_OWNER_PID, 0);
            if (vip == null || vip.isEmpty() || socks <= 0) {
                setFailed("bad VPN parameters");
            } else {
                connect(vip, socks);
            }
        } else if (ACTION_DISCONNECT.equals(action)) {
            disconnect(State.DOWN, "");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void connect(@NonNull String vip, int socks5Port) {
        if (sState == State.UP && vip.equals(sVip) && mBridgeRunning && TProxyService.TProxyIsRunning()) return;
        // (re)establish: tear down the previous device first (vip changed or bridge died)
        teardownQuietly();
        sVip = vip;
        publish(State.STARTING, "");
        VpnBridgeState.log(this, "connect requested: vip=" + vip + " socks5=127.0.0.1:" + socks5Port);

        Throwable load = TProxyService.loadError();
        if (load != null) {
            setFailed("VPN bridge library missing: " + load.getClass().getSimpleName());
            return;
        }
        if (prepare(this) != null) {
            // consent missing or revoked since the UI checked
            setFailed("VPN consent not granted");
            return;
        }
        ParcelFileDescriptor tun;
        try {
            Builder b = new Builder()
                    .setSession(getString(com.craftstudio.cslauncher.R.string.vlan_title))
                    .setMtu(MTU)
                    .addAddress(vip, 24)
                    .addRoute(TunnelConfig.SUBNET_CIDR.substring(0, TunnelConfig.SUBNET_CIDR.indexOf('/')), 24)
                    .setBlocking(false);
            String ownSubnet = vip.substring(0, vip.lastIndexOf('.')) + ".0";
            if (!TunnelConfig.SUBNET_CIDR.startsWith(ownSubnet + "/")) b.addRoute(ownSubnet, 24);
            if (Build.VERSION.SDK_INT >= 21) {
                try { b.addAllowedApplication(getPackageName()); } catch (Exception ignored) {}
            }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
            Intent open = new Intent(this, com.craftstudio.cslauncher.LauncherActivity.class);
            b.setConfigureIntent(android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE));
            tun = b.establish();
        } catch (Throwable t) {
            setFailed("TUN establish failed: " + t.getMessage());
            return;
        }
        if (tun == null) {
            setFailed("TUN establish returned null (consent revoked?)");
            return;
        }
        mTun = tun;
        VpnBridgeState.log(this, "TUN established fd=" + tun.getFd() + " mtu=" + MTU + " route " + TunnelConfig.SUBNET_CIDR);
        File cfg = new File(getFilesDir(), "virtual_lan_tun.yml");
        File bridgeLog = VpnBridgeState.bridgeLog(this);
        //noinspection ResultOfMethodCallIgnored
        bridgeLog.delete(); // one bridge run per log so the report always shows the current attempt
        try (FileOutputStream out = new FileOutputStream(cfg)) {
            out.write(bridgeConfig(vip, socks5Port, bridgeLog).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            teardownQuietly();
            setFailed("bridge config: " + e.getMessage());
            return;
        }
        boolean ok;
        try {
            ok = TProxyService.TProxyStartService(cfg.getAbsolutePath(), tun.getFd());
        } catch (Throwable t) {
            ok = false;
            sError = "bridge start: " + t.getMessage();
        }
        if (!ok) {
            teardownQuietly();
            setFailed(sError.isEmpty() ? "bridge did not start" : sError);
            return;
        }
        mBridgeRunning = true;
        publish(State.UP, "");
        VpnBridgeState.log(this, "bridge running (hev-socks5-tunnel) -> socks5 127.0.0.1:" + socks5Port);
        Log.i(TAG, "VPN up " + vip + "/24 -> socks5 127.0.0.1:" + socks5Port);
        mHandler.removeCallbacks(mHeartbeat);
        mHandler.postDelayed(mHeartbeat, VpnBridgeState.HEARTBEAT_MS);
    }

    private static String bridgeConfig(String vip, int socks5Port, File bridgeLog) {
        return "tunnel:\n"
                + "  name: tun0\n"
                + "  mtu: " + MTU + "\n"
                + "  ipv4: " + vip + "\n"
                + "socks5:\n"
                + "  port: " + socks5Port + "\n"
                + "  address: 127.0.0.1\n"
                + "  udp: 'udp'\n"
                + "misc:\n"
                // The bridge runs INSIDE the game process. Its coroutines run on their own stacks
                // of this size; hev's default is 86016 and anything much smaller overflows a few
                // seconds after the first sessions start (plain SIGSEGV, no Java trace). 128 KiB
                // leaves headroom for bionic's resolver/TLS frames on arm64.
                + "  task-stack-size: 131072\n"
                + "  tcp-buffer-size: 65536\n"
                + "  udp-recv-buffer-size: 524288\n"
                + "  connect-timeout: 8000\n"
                + "  tcp-read-write-timeout: 300000\n"
                + "  udp-read-write-timeout: 60000\n"
                + "  log-file: " + bridgeLog.getAbsolutePath() + "\n"
                + "  log-level: info\n";
    }

    private void setFailed(String why) {
        publish(State.FAILED, why);
        VpnBridgeState.log(this, "FAILED: " + why);
        Log.w(TAG, "VPN failed: " + why);
    }

    private void teardownQuietly() {
        mHandler.removeCallbacks(mHeartbeat);
        if (mBridgeRunning) {
            try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
            mBridgeRunning = false;
            VpnBridgeState.log(this, "bridge stopped");
        }
        if (mTun != null) {
            try { mTun.close(); } catch (IOException ignored) {}
            mTun = null;
        }
    }

    private void disconnect(State next, String why) {
        teardownQuietly();
        if (next == State.DOWN) sVip = "";
        publish(next, why);
        VpnBridgeState.log(this, "disconnect -> " + next + (why.isEmpty() ? "" : ": " + why));
        Log.i(TAG, "VPN " + next + (why.isEmpty() ? "" : ": " + why));
    }

    /** Android calls this when another VPN takes over or the user disables us in settings */
    @Override
    public void onRevoke() {
        disconnect(State.REVOKED, "VPN revoked by the system (another VPN started or it was switched off)");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        teardownQuietly();
        if (sState == State.UP || sState == State.STARTING) { sVip = ""; publish(State.DOWN, ""); }
        VpnBridgeState.log(this, "service destroyed");
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }
}
