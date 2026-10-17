package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;

/**
 * The platform's TUN device for the virtual network. On Android this is a {@code VpnService}
 * whose TUN fd is bridged to the engine's SOCKS5 portal; the desktop harness runs without one.
 * <p>
 * The session calls {@link #bringUp} as soon as its virtual IP is known, waits for {@link #isUp}
 * before reporting Connected, and calls {@link #takeDown} on teardown. All calls come from the
 * session worker thread; implementations must not block for long.
 */
public interface VpnController {
    /** Ask the platform to establish (or re-establish with a new address) the TUN device */
    void bringUp(@NonNull String localVip, int socks5Port);

    /** Release the TUN device and its bridge; must be idempotent */
    void takeDown();

    /** True while packets to the virtual subnet are actually being carried */
    boolean isUp();

    /** True once the user or the system revoked the VPN (another VPN took over, user turned it off) */
    boolean isRevoked();

    /** Short human-readable error for the last failure, "" when none */
    @NonNull String error();
}
