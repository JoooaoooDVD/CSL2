package com.craftstudio.cslauncher.multiplayer.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Small helpers around local TCP ports */
public final class PortUtils {
    private PortUtils() {}

    /** @return whether the port can be bound on the given address right now */
    public static boolean isFree(String bindAddress, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindAddress == null ? null : InetAddress.getByName(bindAddress), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** First free port of [preferred, preferred + range), or an ephemeral one when all are taken */
    public static int pick(String bindAddress, int preferred, int range) {
        for (int p = preferred; p < preferred + range && p < 65536; p++) {
            if (isFree(bindAddress, p)) return p;
        }
        return ephemeral(bindAddress);
    }

    public static int ephemeral(String bindAddress) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindAddress == null ? null : InetAddress.getByName(bindAddress), 0));
            return socket.getLocalPort();
        } catch (IOException e) {
            return -1;
        }
    }
}
