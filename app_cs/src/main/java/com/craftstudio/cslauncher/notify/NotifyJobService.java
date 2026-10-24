package com.craftstudio.cslauncher.notify;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import androidx.annotation.NonNull;

/** Periodic background poll for new notifications (JobScheduler, battery friendly, survives reboot) */
public class NotifyJobService extends JobService {
    private Thread mWorker;

    /** Idempotent: re-scheduling an existing periodic job with the same id keeps its cadence */
    public static void schedule(@NonNull Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == NotifyConfig.JOB_ID) return;
            JobInfo.Builder b = new JobInfo.Builder(NotifyConfig.JOB_ID, new ComponentName(context, NotifyJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(NotifyConfig.POLL_INTERVAL_MS);
            js.schedule(b.build());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        mWorker = new Thread(() -> {
            boolean ok = false;
            try { ok = NotificationSync.sync(getApplicationContext()); } catch (Throwable ignored) {}
            jobFinished(params, !ok);
        }, "cs-notify-job");
        mWorker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mWorker != null) mWorker.interrupt();
        return true;
    }
}
