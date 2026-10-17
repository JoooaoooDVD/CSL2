package com.craftstudio.cslauncher.multiplayer;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.multiplayer.core.SessionCode;
import com.craftstudio.cslauncher.multiplayer.core.VirtualLanSession;
import com.craftstudio.cslauncher.multiplayer.core.VirtualLanState;
import com.craftstudio.cslauncher.utils.NotificationUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that owns the Virtual LAN session. It lives in the {@code :game} process on
 * purpose: the tunnel's lifetime is bound to the game's. When Minecraft exits (or is force-closed,
 * or the system kills the process) the session disappears with it, the shell watchdog kills the
 * engine, and no orphan tunnel or stale "active session" can remain.
 * <p>
 * UI (launcher process or the in-game popup) talks to it through a Messenger; the service pushes
 * immutable state snapshots to every registered client.
 */
public final class VirtualLanService extends Service {
    private static final String TAG = AndroidTunnelEnvironment.TAG;
    public static final String ACTION_END = "com.craftstudio.cslauncher.multiplayer.END";

    // client -> service
    public static final int MSG_REGISTER = 1;
    public static final int MSG_UNREGISTER = 2;
    public static final int MSG_CREATE = 3;
    public static final int MSG_JOIN = 4;
    public static final int MSG_END = 5;
    public static final int MSG_KICK = 6;
    public static final int MSG_GET_STATE = 7;
    public static final int MSG_GET_LOG = 8;
    public static final int MSG_DISMISS = 9;
    // service -> client
    public static final int MSG_STATE = 100;
    public static final int MSG_LOG = 101;

    public static final String KEY_CODE = "code";
    public static final String KEY_PLAYER = "player";
    public static final String KEY_RELAYS = "relays";
    public static final String KEY_MEMBER = "member";
    public static final String KEY_LOG = "log";

    /** The session running in this process, for the game-exit hook */
    @Nullable private static volatile VirtualLanSession sActive;

    /**
     * Called right before the game process kills itself: ends the session so guests learn the
     * host left (instead of timing out) and the engine is stopped cleanly. Blocks briefly.
     */
    public static void shutdownForProcessExit(long waitMs) {
        VirtualLanSession s = sActive;
        if (s == null || s.isFinished()) return;
        s.end(VirtualLanState.Reason.GAME_CLOSED);
        long deadline = System.currentTimeMillis() + waitMs;
        while (!s.isFinished() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Messenger> mClients = new ArrayList<>();
    private Messenger mMessenger;
    @Nullable private VirtualLanSession mSession;
    @NonNull private VirtualLanState mState = VirtualLanState.IDLE;
    private boolean mForeground;
    @Nullable private ConnectivityManager.NetworkCallback mNetworkCallback;
    @Nullable private WifiManager.MulticastLock mMulticastLock;
    @Nullable private final com.craftstudio.cslauncher.Logger.eventLogListener mLogSink = text -> {
        VirtualLanSession s = mSession;
        if (s != null) s.onLogLine(text);
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Tools.buildNotificationChannel(getApplicationContext());
        mMessenger = new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));
        // sweep tunnels a crashed earlier process may have left behind
        new Thread(() -> {
            for (String role : new String[]{"host", "guest"}) {
                try {
                    new com.craftstudio.cslauncher.multiplayer.core.TunnelManager(new AndroidTunnelEnvironment(this, role)).killStale();
                } catch (Throwable ignored) {
                }
            }
        }, "vlan-sweep").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_END.equals(intent.getAction())) {
            endSession(VirtualLanState.Reason.LEFT);
            return START_NOT_STICKY;
        }
        // Started ahead of a create/join request: promote right away (Android requires
        // startForeground() shortly after startForegroundService()), drop it if nothing follows.
        startForegroundCompat();
        mMain.postDelayed(() -> {
            if (mSession == null) {
                stopForegroundCompat();
                if (mClients.isEmpty()) stopSelf();
            }
        }, 10_000);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Stop when nobody is interested and no session is running
        mMain.postDelayed(() -> { if (mSession == null && mClients.isEmpty()) stopSelf(); }, 1500);
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    @Override
    public void onDestroy() {
        VirtualLanSession s = mSession;
        if (s != null) s.end(VirtualLanState.Reason.LEFT);
        releaseNetworkWatch();
        releaseMulticast();
        LogTap.removeSink(mLogSink);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Launcher swiped away with no game: do not leave a tunnel behind
        VirtualLanSession s = mSession;
        if (s != null && !isGameRunning()) s.end(VirtualLanState.Reason.LEFT);
        super.onTaskRemoved(rootIntent);
    }

    private boolean isGameRunning() {
        try {
            return com.craftstudio.cslauncher.game.GameActivity.isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    // ───────────────────────────── messages ─────────────────────────────

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_REGISTER:
                if (msg.replyTo != null && !mClients.contains(msg.replyTo)) mClients.add(msg.replyTo);
                send(msg.replyTo, MSG_STATE, VirtualLanStateBundler.toBundle(mState));
                return true;
            case MSG_UNREGISTER:
                if (msg.replyTo != null) mClients.remove(msg.replyTo);
                return true;
            case MSG_GET_STATE:
                send(msg.replyTo, MSG_STATE, VirtualLanStateBundler.toBundle(mState));
                return true;
            case MSG_CREATE:
                startSession(VirtualLanState.Role.HOST, SessionCode.generate(), msg.getData());
                return true;
            case MSG_JOIN: {
                SessionCode code = SessionCode.extract(msg.getData().getString(KEY_CODE));
                if (code == null) return true;
                startSession(VirtualLanState.Role.GUEST, code, msg.getData());
                return true;
            }
            case MSG_END:
                endSession(VirtualLanState.Reason.LEFT);
                return true;
            case MSG_KICK: {
                VirtualLanSession s = mSession;
                String member = msg.getData().getString(KEY_MEMBER);
                if (s != null && member != null) s.kick(member);
                return true;
            }
            case MSG_DISMISS:
                // The UI acknowledged a final state; forget it so a fresh open shows the home screen
                if (mSession == null && !mState.isActive()) {
                    mState = VirtualLanState.IDLE;
                    broadcast();
                }
                return true;
            case MSG_GET_LOG: {
                Bundle b = new Bundle();
                VirtualLanSession s = mSession;
                b.putStringArrayList(KEY_LOG, new ArrayList<>(s != null ? s.engineLog() : mLastLog));
                send(msg.replyTo, MSG_LOG, b);
                return true;
            }
            default:
                return false;
        }
    }

    private List<String> mLastLog = new ArrayList<>();

    private void send(@Nullable Messenger to, int what, Bundle data) {
        if (to == null) return;
        Message m = Message.obtain(null, what);
        m.setData(data);
        try {
            to.send(m);
        } catch (RemoteException e) {
            mClients.remove(to);
        }
    }

    private void broadcast() {
        Bundle data = VirtualLanStateBundler.toBundle(mState);
        for (Messenger client : new ArrayList<>(mClients)) send(client, MSG_STATE, data);
        updateNotification();
    }

    // ───────────────────────────── session control ─────────────────────────────

    private void startSession(VirtualLanState.Role role, SessionCode code, Bundle args) {
        if (mSession != null) {
            if (mSession.isFinished()) {
                mSession = null;
            } else {
                // One session at a time: UI shows the running one
                broadcast();
                return;
            }
        }
        VirtualLanSession.Params params = new VirtualLanSession.Params();
        params.role = role;
        params.code = code;
        String player = args != null ? args.getString(KEY_PLAYER) : null;
        params.playerName = player == null || player.trim().isEmpty() ? "Player" : player.trim();
        ArrayList<String> relays = args != null ? args.getStringArrayList(KEY_RELAYS) : null;
        if (relays != null) params.userRelayUris = relays;

        AndroidTunnelEnvironment env = new AndroidTunnelEnvironment(this, role == VirtualLanState.Role.HOST ? "host" : "guest");
        final VirtualLanSession[] ref = new VirtualLanSession[1];
        VirtualLanSession session = new VirtualLanSession(env, new java.io.File(getCacheDir(), "virtual_lan"), params, new VirtualLanSession.Listener() {
            @Override
            public void onStateChanged(@NonNull VirtualLanState state) {
                mMain.post(() -> {
                    VirtualLanSession current = ref[0];
                    if (current == null || mSession != current) return;
                    mState = state;
                    broadcast();
                });
            }

            @Override
            public void onFinished(@NonNull VirtualLanState finalState) {
                mMain.post(() -> {
                    VirtualLanSession current = ref[0];
                    if (current == null || mSession != current) return;
                    mLastLog = current.engineLog();
                    mSession = null;
                    if (sActive == current) sActive = null;
                    mState = finalState;
                    LogTap.removeSink(mLogSink);
                    releaseNetworkWatch();
                    stopForegroundCompat();
                    broadcast();
                    if (mClients.isEmpty()) stopSelf();
                });
            }
        });
        ref[0] = session;
        mSession = session;
        sActive = session;
        mState = session.state();
        startForegroundCompat();
        watchNetwork();
        if (role == VirtualLanState.Role.HOST) {
            acquireMulticast();
            LogTap.addSink(mLogSink);
        }
        session.start();
        broadcast();
    }

    private void endSession(VirtualLanState.Reason reason) {
        VirtualLanSession s = mSession;
        if (s != null) s.end(reason);
    }

    // ───────────────────────────── foreground / notification ─────────────────────────────

    private void startForegroundCompat() {
        if (mForeground) return;
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NotificationUtils.NOTIFICATION_ID_VIRTUAL_LAN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
            } else {
                startForeground(NotificationUtils.NOTIFICATION_ID_VIRTUAL_LAN, n);
            }
            mForeground = true;
        } catch (Throwable t) {
            // Background start restrictions: keep running as a bound service, the UI still works
            Log.w(TAG, "foreground start refused: " + t);
        }
    }

    private void stopForegroundCompat() {
        if (!mForeground) return;
        mForeground = false;
        try { stopForeground(true); } catch (Throwable ignored) {}
        releaseMulticast();
    }

    private void updateNotification() {
        if (!mForeground) return;
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NotificationUtils.NOTIFICATION_ID_VIRTUAL_LAN, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification() {
        Intent endIntent = new Intent(this, VirtualLanService.class).setAction(ACTION_END);
        PendingIntent endPending = PendingIntent.getService(this, NotificationUtils.PENDINGINTENT_CODE_VIRTUAL_LAN_END, endIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, LauncherActivity.class).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent openPending = PendingIntent.getActivity(this, NotificationUtils.PENDINGINTENT_CODE_VIRTUAL_LAN_OPEN, open, PendingIntent.FLAG_IMMUTABLE);
        String text = notificationText(this, mState);
        return new NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
                .setContentTitle(getString(R.string.vlan_title))
                .setContentText(text)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(mState.isHost() ? R.string.vlan_end_session : R.string.vlan_disconnect), endPending)
                .setSmallIcon(R.drawable.notif_icon)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
    }

    /** Short human status used by the notification; the full mapping lives in the UI layer */
    static String notificationText(Context c, VirtualLanState s) {
        switch (s.phase) {
            case STARTING: return c.getString(R.string.vlan_phase_starting);
            case DISCOVERING: return c.getString(R.string.vlan_phase_discovering);
            case ESTABLISHING: return c.getString(R.string.vlan_phase_establishing);
            case CONNECTED_DIRECT:
                return s.isHost() ? c.getString(R.string.vlan_notif_hosting, s.sessionCode, s.members.size())
                        : c.getString(R.string.vlan_phase_connected_direct);
            case CONNECTED_RELAY:
                return s.isHost() ? c.getString(R.string.vlan_notif_hosting, s.sessionCode, s.members.size())
                        : c.getString(R.string.vlan_phase_connected_relay);
            case RECONNECTING: return c.getString(R.string.vlan_phase_reconnecting);
            default: return c.getString(R.string.vlan_phase_idle);
        }
    }

    // ───────────────────────────── network / multicast ─────────────────────────────

    private void watchNetwork() {
        if (mNetworkCallback != null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            private Network mLast;

            @Override
            public void onAvailable(@NonNull Network network) {
                if (mLast != null && !mLast.equals(network)) {
                    VirtualLanSession s = mSession;
                    if (s != null) s.onNetworkChanged();
                }
                mLast = network;
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (network.equals(mLast)) {
                    VirtualLanSession s = mSession;
                    if (s != null) s.onNetworkChanged();
                }
            }
        };
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            cm.registerNetworkCallback(request, mNetworkCallback);
        } catch (Throwable t) {
            mNetworkCallback = null;
        }
    }

    private void releaseNetworkWatch() {
        if (mNetworkCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try { if (cm != null) cm.unregisterNetworkCallback(mNetworkCallback); } catch (Throwable ignored) {}
        mNetworkCallback = null;
    }

    /** Lets the host receive Minecraft's own LAN announcement on Wi-Fi */
    private void acquireMulticast() {
        if (mMulticastLock != null) return;
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            mMulticastLock = wifi.createMulticastLock("cslp-virtual-lan");
            mMulticastLock.setReferenceCounted(false);
            mMulticastLock.acquire();
        } catch (Throwable t) {
            mMulticastLock = null;
        }
    }

    private void releaseMulticast() {
        try { if (mMulticastLock != null && mMulticastLock.isHeld()) mMulticastLock.release(); } catch (Throwable ignored) {}
        mMulticastLock = null;
    }
}
