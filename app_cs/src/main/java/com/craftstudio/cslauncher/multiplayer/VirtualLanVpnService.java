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

    /** Called by the controller when a new session starts, so a stale REVOKED/FAILED does not leak into it */
    static void resetState() {
        if (sInstance == null) { sState = State.DOWN; sError = ""; sVip = ""; }
    }

    private ParcelFileDescriptor mTun;
    private boolean mBridgeRunning;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CONNECT.equals(action)) {
            String vip = intent.getStringExtra(EXTRA_VIP);
            int socks = intent.getIntExtra(EXTRA_SOCKS5_PORT, 0);
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
        sState = State.STARTING;
        sError = "";
        sVip = vip;

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
        File cfg = new File(getFilesDir(), "virtual_lan_tun.yml");
        try (FileOutputStream out = new FileOutputStream(cfg)) {
            out.write(bridgeConfig(vip, socks5Port).getBytes(StandardCharsets.UTF_8));
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
        sState = State.UP;
        Log.i(TAG, "VPN up " + vip + "/24 -> socks5 127.0.0.1:" + socks5Port);
    }

    private static String bridgeConfig(String vip, int socks5Port) {
        return "tunnel:\n"
                + "  name: tun0\n"
                + "  mtu: " + MTU + "\n"
                + "  ipv4: " + vip + "\n"
                + "socks5:\n"
                + "  port: " + socks5Port + "\n"
                + "  address: 127.0.0.1\n"
                + "  udp: 'udp'\n"
                + "misc:\n"
                + "  task-stack-size: 20480\n"
                + "  tcp-buffer-size: 65536\n"
                + "  connect-timeout: 5000\n"
                + "  read-write-timeout: 120000\n"
                + "  log-level: warn\n";
    }

    private void setFailed(String why) {
        sError = why;
        sState = State.FAILED;
        Log.w(TAG, "VPN failed: " + why);
    }

    private void teardownQuietly() {
        if (mBridgeRunning) {
            try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
            mBridgeRunning = false;
        }
        if (mTun != null) {
            try { mTun.close(); } catch (IOException ignored) {}
            mTun = null;
        }
    }

    private void disconnect(State next, String why) {
        teardownQuietly();
        sState = next;
        sError = why;
        if (next == State.DOWN) sVip = "";
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
        if (sState == State.UP || sState == State.STARTING) { sState = State.DOWN; sVip = ""; }
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }
}
