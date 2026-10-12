package com.craftstudio.cslauncher.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.progresskeeper.ProgressState;
import com.craftstudio.cslauncher.progresskeeper.TaskCountListener;
import com.craftstudio.cslauncher.utils.NotificationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Foreground service that keeps the process alive while launcher tasks run and
 * posts the download notification: a determinate bar for the overall progress,
 * the active task count as subtext and one line per running task.
 */
public class ProgressService extends Service implements TaskCountListener {

    private NotificationManagerCompat notificationManagerCompat;
    private NotificationCompat.Builder mNotificationBuilder;

    /** Simple wrapper to start the service */
    public static void startService(Context context){
        Intent intent = new Intent(context, ProgressService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
        notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
        Intent killIntent = new Intent(getApplicationContext(), ProgressService.class);
        killIntent.putExtra("kill", true);
        PendingIntent pendingKillIntent = PendingIntent.getService(this, NotificationUtils.PENDINGINTENT_CODE_KILL_PROGRESS_SERVICE
                , killIntent, Build.VERSION.SDK_INT >=23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        mNotificationBuilder = new NotificationCompat.Builder(this, "channel_id")
                .setContentTitle(getString(R.string.notification_progress_title))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,  getString(R.string.notification_terminate), pendingKillIntent)
                .setSmallIcon(R.drawable.notif_icon)
                .setOnlyAlertOnce(true)
                .setNotificationSilent();
        ProgressKeeper.addGlobalListener((taskCount, percent) -> Tools.MAIN_HANDLER.post(this::refreshNotification));
    }

    @SuppressLint("StringFormatInvalid")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null) {
            if(intent.getBooleanExtra("kill", false)) {
                stopSelf(); // otherwise Android tries to restart the service since it "crashed"
                Process.killProcess(Process.myPid());
                return START_NOT_STICKY;
            }
        }
        Log.d("ProgressService", "Started!");
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, notification);
        }
        if(ProgressKeeper.getTaskCount() < 1) stopSelf();
        else ProgressKeeper.addTaskCountListener(this, false);

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        ProgressKeeper.removeTaskCountListener(this);
    }

    /** Rebuilds the notification from the current task snapshot. */
    private void refreshNotification() {
        if (!ProgressKeeper.hasOngoingTasks()) {
            stopSelf();
            return;
        }
        notificationManagerCompat.notify(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, buildNotification());
    }

    private Notification buildNotification() {
        int taskCount = ProgressKeeper.getTaskCount();
        int percent = ProgressKeeper.getAggregateProgress();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, ProgressState> entry : ProgressKeeper.snapshotStates().entrySet()) {
            ProgressState state = entry.getValue();
            String detail;
            if (state.getResid() != -1) {
                detail = getString(state.getResid(), state.getVarArg());
            } else if (state.getVarArg().length > 0 && state.getVarArg()[0] != null) {
                detail = state.getVarArg()[0].toString();
            } else {
                detail = getString(R.string.progress_task_unknown);
            }
            lines.add(detail);
        }

        mNotificationBuilder
                .setContentText(taskCount == 1
                        ? lines.isEmpty() ? getString(R.string.progress_task_unknown) : lines.get(0)
                        : getString(R.string.progresslayout_tasks_in_progress, taskCount))
                .setProgress(100, percent >= 0 ? percent : 0, percent < 0)
                .setSubText(percent >= 0
                        ? getString(R.string.notification_progress_sub, percent, taskCount)
                        : getString(R.string.progress_console_subtitle_unknown, taskCount));

        if (!lines.isEmpty()) {
            NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
            int shown = 0;
            for (String line : lines) {
                if (shown >= 5) break;
                style.addLine(line);
                shown++;
            }
            if (lines.size() > shown) style.setSummaryText("+" + (lines.size() - shown));
            mNotificationBuilder.setStyle(style);
        } else {
            mNotificationBuilder.setStyle(null);
        }
        return mNotificationBuilder.build();
    }

    @Override
    public boolean onUpdateTaskCount(int taskCount) {
        Tools.MAIN_HANDLER.post(()->{
            if(taskCount > 0) refreshNotification();
            else stopSelf();
        });
        return false;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        super.onTimeout(startId, fgsType);
        stopForeground(true);
        stopSelf();
    }
}
