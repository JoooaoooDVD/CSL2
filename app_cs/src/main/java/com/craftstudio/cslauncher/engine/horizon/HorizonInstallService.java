package com.craftstudio.cslauncher.engine.horizon;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
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
 */
public class HorizonInstallService extends Service implements HorizonNative.InstallListener {
    private static final String TAG = "HorizonInstall";
    public static final String ACTION_START = "com.craftstudio.cslauncher.horizon.INSTALL";
    public static final String ACTION_CANCEL = "com.craftstudio.cslauncher.horizon.CANCEL";
    public static final String ACTION_PROGRESS = "com.craftstudio.cslauncher.horizon.PROGRESS";
    public static final int NOTIFICATION_ID = 41;

    private volatile boolean mCancelled;
    private Thread mWorker;
    private NotificationCompat.Builder mNotification;
    private long mLastNotify;

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
        if (Tools.DIR_GAME_HOME == null && Tools.checkStorageRoot(this)) Tools.initStorageConstants(this);
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
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            mCancelled = true;
            HorizonInstallState.set(this, HorizonInstallState.STATUS_CANCELLED, getString(R.string.horizon_install_cancelled), 0, 0, null);
            broadcast();
            if (mWorker == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (mWorker != null) return START_NOT_STICKY;
        if (!HorizonNative.load()) {
            finish(getString(R.string.horizon_not_available));
            return START_NOT_STICKY;
        }
        mCancelled = false;
        HorizonInstallState.set(this, HorizonInstallState.STATUS_RUNNING, getString(R.string.horizon_install_preparing), 0, 0, null);
        broadcast();
        mWorker = new Thread(this::run, "horizon-install");
        mWorker.start();
        return START_NOT_STICKY;
    }

    private void run() {
        String error;
        try {
            File engineData = HorizonEngine.stageEngineData(this);
            File cache = HorizonEngine.cacheDir(this);
            //noinspection ResultOfMethodCallIgnored
            cache.mkdirs();
            error = HorizonNative.nativeInstall(HorizonEngine.home().getAbsolutePath(),
                    cache.getAbsolutePath(), engineData.getAbsolutePath(), this);
        } catch (Throwable t) {
            Log.e(TAG, "Install failed", t);
            error = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        final String result = error;
        new android.os.Handler(getMainLooper()).post(() -> finish(result));
    }

    private void finish(@Nullable String error) {
        mWorker = null;
        if (mCancelled) {
            HorizonInstallState.set(this, HorizonInstallState.STATUS_CANCELLED, getString(R.string.horizon_install_cancelled), 0, 0, null);
        } else if (error == null && HorizonNative.load() && HorizonNative.nativeReady(HorizonEngine.home().getAbsolutePath())) {
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
            String text = total > 0 ? stage + " · " + HorizonInstallState.percent(done, total) + "%" : stage;
            mNotification.setContentText(text);
            if (total > 0) mNotification.setProgress(100, HorizonInstallState.percent(done, total), false);
            else mNotification.setProgress(100, 0, true);
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, mNotification.build());
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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
