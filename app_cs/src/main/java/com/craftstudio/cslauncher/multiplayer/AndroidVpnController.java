package com.craftstudio.cslauncher.multiplayer;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.craftstudio.cslauncher.multiplayer.core.VpnController;

/** Bridges the session (worker thread) to {@link VirtualLanVpnService} running in this process */
final class AndroidVpnController implements VpnController {
    private final Context mContext;
    private boolean mRequested;

    AndroidVpnController(@NonNull Context context) {
        mContext = context.getApplicationContext();
        sStartError = "";
        VirtualLanVpnService.resetState();
    }

    @Override
    public void bringUp(@NonNull String localVip, int socks5Port) {
        mRequested = true;
        Intent i = new Intent(mContext, VirtualLanVpnService.class)
                .setAction(VirtualLanVpnService.ACTION_CONNECT)
                .putExtra(VirtualLanVpnService.EXTRA_VIP, localVip)
                .putExtra(VirtualLanVpnService.EXTRA_SOCKS5_PORT, socks5Port);
        try {
            mContext.startService(i);
        } catch (Throwable t) {
            // Background start restriction: surfaces as VPN_FAILED through error()
            sStartError = "could not start VPN service: " + t.getMessage();
        }
    }

    private static volatile String sStartError = "";

    @Override
    public void takeDown() {
        if (!mRequested) return;
        mRequested = false;
        try {
            mContext.startService(new Intent(mContext, VirtualLanVpnService.class).setAction(VirtualLanVpnService.ACTION_DISCONNECT));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isUp() { return VirtualLanVpnService.state() == VirtualLanVpnService.State.UP; }

    @Override
    public boolean isRevoked() { return VirtualLanVpnService.state() == VirtualLanVpnService.State.REVOKED; }

    @NonNull
    @Override
    public String error() {
        if (!sStartError.isEmpty()) return sStartError;
        return VirtualLanVpnService.state() == VirtualLanVpnService.State.FAILED ? VirtualLanVpnService.error() : "";
    }
}
