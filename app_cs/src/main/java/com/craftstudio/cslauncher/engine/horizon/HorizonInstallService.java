package com.craftstudio.cslauncher.engine.horizon;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;

import java.io.File;

/**
 * Foreground service that installs the Horizon engine's Minecraft resources through
 * {@link HorizonNative#nativeInstall}. Runs in the {@code :horizon} process (the engine library is
 * loaded only there). Progress is persisted in {@link HorizonInstallState} and broadcast as
 * {@link #ACTION_PROGRESS}; the launcher UI observes it from any process.
 *
 * <p>Completion rules: the engine returning {@code null} is not enough. The service re-checks
 * {@link HorizonNative#nativeReady} (VERIFY stage), writes {@link HorizonEngine#readyMarker()} and only
 * then reports {@link HorizonInstallState#STATUS_DONE}. Downloads that were verified before an interruption
 * are kept in the cache; a retry re-uses them (the engine checks their SHA-1) instead of downloading again.
 */
public class HorizonInstallService extends Service implements HorizonNative.InstallListener {
    private static final String TAG = "HorizonInstall";
    public static final String ACTION_START = "com.craftstudio.cslauncher.horizon.INSTALL";
    public static final String ACTION_CANCEL = "com.craftstudio.cslauncher.horizon.CANCEL";
    public static final String ACTION_PROGRESS = "com.craftstudio.cslauncher.horizon.PROGRESS";
    public static final int NOTIFICATION_ID = 41;
    private static final long HEARTBEAT_MS = 3000;

    private volatile boolean mCancelled;
    private Thread mWorker;
    private NotificationCompat.Builder mNotification;
    private long mLastNotify;
    private Handler mHandler;
    private final Runnable mHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (mWorker == null) return;
            HorizonInstallState.touch(HorizonInstallService.this);
            broadcast();
            mHandler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, HorizonInstallService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void cancel(Context context) {
        Intent intent = new Intent(context, HorizonInstallService.class).setAction(ACTION_CANCEL);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Tools.DIR_DATA == null) Tools.initEarlyConstants(this);
        if (Tools.DIR_GAME_HOME == null && Tools.checkStorageRoot(this)) Tools.initStorageConstants(this);
        mHandler = new Handler(getMainLooper());
        Tools.buildNotificationChannel(getApplicationContext());
        Intent open = new Intent(this, LauncherActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mNotification = new NotificationCompat.Builder(this, "channel_id")
                .setContentTitle(getString(R.string.horizon_install_title))
                .setContentText(getString(R.string.horizon_install_preparing))
                .setSmallIcon(R.drawable.notif_icon)
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, 0, true);
        startInForeground(mNotification.build());
    }

    private void startInForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A null intent = the system restarted a sticky service that was killed mid-install: resume.
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            mCancelled = true;
            if (mWorker == null) {
                HorizonInstallState.set(this, HorizonInstallState.STATUS_CANCELLED, getString(R.string.horizon_install_cancelled), 0, 0, null);
                broadcast();
                stopForeground(true);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (mWorker != null) return START_STICKY;
        if (!HorizonNative.load()) {
            finish(getString(R.string.horizon_not_available));
            return START_NOT_STICKY;
        }
        mCancelled = false;
        HorizonInstallState.set(this, HorizonInstallState.STATUS_RUNNING, getString(R.string.horizon_install_preparing), 0, 0, null);
        broadcast();
        mWorker = new Thread(this::run, "horizon-install");
        mWorker.start();
        mHandler.removeCallbacks(mHeartbeat);
        mHandler.postDelayed(mHeartbeat, HEARTBEAT_MS);
        return START_STICKY;
    }

    private void run() {
        String error;
        try {
            HorizonEngine.clearReadyMarker();
            removeLegacyHome();
            File engineData = HorizonEngine.stageEngineData(this);
            File cache = HorizonEngine.cacheDir(this);
            //noinspection ResultOfMethodCallIgnored
            cache.mkdirs();
            String home = HorizonEngine.home().getAbsolutePath();
            error = HorizonNative.nativeInstall(home, cache.getAbsolutePath(), engineData.getAbsolutePath(), this);
            if (error == null && !mCancelled) {
                // 100% downloaded is not installed: the engine must agree that the prepared resources are complete.
                onProgress("Verifying installation", 0, 0);
                if (HorizonNative.nativeReady(home) && HorizonEngine.resourcesLookComplete()) {
                    HorizonEngine.writeReadyMarker(this);
                } else {
                    error = getString(R.string.horizon_install_incomplete);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Install failed", t);
            error = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        final String result = error;
        mHandler.post(() -> finish(result));
    }

    /** The first test builds kept the engine home on the shared storage; it is not reused. */
    private void removeLegacyHome() {
        try {
            if (Tools.DIR_GAME_HOME == null) return;
            File legacy = HorizonEngine.legacyHome();
            if (legacy.isDirectory() && !legacy.getCanonicalPath().equals(HorizonEngine.home().getCanonicalPath())) {
                org.apache.commons.io.FileUtils.deleteQuietly(legacy);
            }
        } catch (Exception e) {
            Log.w(TAG, "Legacy engine home not removed", e);
        }
    }

    private void finish(@Nullable String error) {
        mWorker = null;
        mHandler.removeCallbacks(mHeartbeat);
        if (mCancelled || (error != null && error.toLowerCase(java.util.Locale.ROOT).contains("cancelled"))) {
            HorizonInstallState.set(this, HorizonInstallState.STATUS_CANCELLED, getString(R.string.horizon_install_cancelled), 0, 0, null);
        } else if (error == null && HorizonEngine.readyMarker().isFile()) {
            HorizonInstallState.set(this, HorizonInstallState.STATUS_DONE, getString(R.string.horizon_install_done), 1, 1, null);
        } else {
            HorizonInstallState.set(this, HorizonInstallState.STATUS_FAILED, getString(R.string.horizon_install_failed), 0, 0,
                    error == null ? getString(R.string.horizon_install_incomplete) : error);
        }
        broadcast();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onProgress(String stage, long done, long total) {
        HorizonInstallState.set(this, HorizonInstallState.STATUS_RUNNING, stage, done, total, null);
        long now = System.currentTimeMillis();
        if (now - mLastNotify > 700) {
            mLastNotify = now;
            broadcast();
            HorizonInstallState state = HorizonInstallState.get(this);
            int percent = state.overallPercent();
            mNotification.setContentText(state.stageLabel(this) + " · " + percent + "%");
            mNotification.setProgress(100, percent, false);
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, mNotification.build());
            } catch (SecurityException ignored) {
                // No notification permission: the in-app progress is enough.
            }
        }
    }

    @Override
    public boolean isCancelled() {
        return mCancelled;
    }

    private void broadcast() {
        Intent intent = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        if (mHandler != null) mHandler.removeCallbacks(mHeartbeat);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
