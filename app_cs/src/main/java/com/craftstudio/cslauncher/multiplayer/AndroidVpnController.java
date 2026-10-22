package com.craftstudio.cslauncher.multiplayer;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.craftstudio.cslauncher.multiplayer.core.VpnController;

/**
 * Bridges the session (worker thread, {@code :game}) to {@link VirtualLanVpnService}, which runs
 * in its own {@code :vpn} process. State comes back through {@link VpnBridgeState} (heart-beaten
 * file). If the bridge process dies (native fault) the controller restarts it a few times on its
 * own; the session only sees a short "TUN device went down" and then carries on.
 */
final class AndroidVpnController implements VpnController {
    private static final String TAG = "VirtualLanVpnCtl";
    private static final int MAX_RESTARTS = 4;
    private static final long RESTART_GAP_MS = 1500;

    private final Context mContext;
    private boolean mRequested;
    private String mVip = "";
    private int mSocks5Port;
    private int mRestarts;
    private long mLastStartAt;
    private volatile String mStartError = "";
    private volatile String mDeathNote = "";

    AndroidVpnController(@NonNull Context context) {
        mContext = context.getApplicationContext();
        // A stale file from an earlier session must not leak into this one; a still-running
        // service re-creates it within one heartbeat.
        VpnBridgeState.clear(mContext);
    }

    @Override
    public void bringUp(@NonNull String localVip, int socks5Port) {
        mRequested = true;
        mVip = localVip;
        mSocks5Port = socks5Port;
        mRestarts = 0;
        mDeathNote = "";
        start();
    }

    private void start() {
        mLastStartAt = System.currentTimeMillis();
        mStartError = "";
        Intent i = new Intent(mContext, VirtualLanVpnService.class)
                .setAction(VirtualLanVpnService.ACTION_CONNECT)
                .putExtra(VirtualLanVpnService.EXTRA_VIP, mVip)
                .putExtra(VirtualLanVpnService.EXTRA_SOCKS5_PORT, mSocks5Port)
                .putExtra(VirtualLanVpnService.EXTRA_OWNER_PID, android.os.Process.myPid());
        try {
            mContext.startService(i);
        } catch (Throwable t) {
            // Background start restriction: surfaces as VPN_FAILED through error()
            mStartError = "could not start VPN service: " + t.getMessage();
        }
    }

    @Override
    public void takeDown() {
        if (!mRequested) return;
        mRequested = false;
        try {
            mContext.startService(new Intent(mContext, VirtualLanVpnService.class).setAction(VirtualLanVpnService.ACTION_DISCONNECT));
        } catch (Throwable ignored) {
        }
    }

    /** Reads the shared state; restarts the bridge when its process vanished while we still want it. */
    private VpnBridgeState.Snapshot poll() {
        VpnBridgeState.Snapshot s = VpnBridgeState.read(mContext);
        if (mRequested && s.processDied() && System.currentTimeMillis() - mLastStartAt > RESTART_GAP_MS) {
            if (mRestarts < MAX_RESTARTS) {
                mRestarts++;
                mDeathNote = "VPN bridge process died (restart " + mRestarts + "/" + MAX_RESTARTS + ")";
                Log.w(TAG, mDeathNote + "; last state " + s.state + " pid " + s.pid);
                VpnBridgeState.clear(mContext);
                start();
                return VpnBridgeState.read(mContext);
            }
            mDeathNote = "VPN bridge process keeps dying (" + MAX_RESTARTS + " restarts) - send the Virtual LAN logs";
        }
        return s;
    }

    @Override
    public boolean isUp() { return poll().isUp(); }

    @Override
    public boolean isRevoked() { return poll().isRevoked(); }

    @NonNull
    @Override
    public String error() {
        if (!mStartError.isEmpty()) return mStartError;
        VpnBridgeState.Snapshot s = poll();
        if (s.isFailed()) return s.error;
        if (s.processDied() && mRestarts >= MAX_RESTARTS) return mDeathNote;
        return "";
    }

    /** One line for diagnostics: where the bridge is right now. */
    @NonNull
    public String describe() {
        VpnBridgeState.Snapshot s = VpnBridgeState.read(mContext);
        return "vpn=" + (s.state.isEmpty() ? "none" : s.state) + (s.stale ? "(stale)" : "") + " pid=" + s.pid
                + (s.error.isEmpty() ? "" : " err=" + s.error) + (mDeathNote.isEmpty() ? "" : " note=" + mDeathNote);
    }
}
