package hev.htproxy;

/**
 * JNI surface of hev-socks5-tunnel (MIT, https://github.com/heiher/hev-socks5-tunnel), the
 * TUN <-> SOCKS5 bridge used by the Virtual LAN VpnService. The native library registers its
 * methods against this exact class name, which is why it lives in the {@code hev.htproxy}
 * package. The library is built by scripts/build_hev_tunnel.sh (CI) into jniLibs.
 */
public final class TProxyService {
    private static Throwable sLoadError;

    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
        } catch (Throwable t) {
            sLoadError = t;
        }
    }

    private TProxyService() {}

    /** Null when the native bridge loaded, otherwise the reason it is unavailable */
    public static Throwable loadError() { return sLoadError; }

    /** Starts the bridge on the given TUN fd; returns once its worker thread is running */
    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    /** [tx packets, tx bytes, rx packets, rx bytes] */
    public static native long[] TProxyGetStats();
}
