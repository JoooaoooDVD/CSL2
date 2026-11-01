package com.craftstudio.cslauncher.engine.horizon;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Install progress shared between the {@code :horizon} service and the launcher UI (multi-process prefs). */
public final class HorizonInstallState {
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELLED = 4;
    private static final String PREFS = "cs_horizon_install";

    public final int status;
    @NonNull public final String stage;
    public final long done, total;
    @Nullable public final String error;
    public final long updatedAt;

    private HorizonInstallState(int status, @NonNull String stage, long done, long total, @Nullable String error, long updatedAt) {
        this.status = status;
        this.stage = stage;
        this.done = done;
        this.total = total;
        this.error = error;
        this.updatedAt = updatedAt;
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    static void set(Context context, int status, String stage, long done, long total, @Nullable String error) {
        prefs(context).edit()
                .putInt("status", status)
                .putString("stage", stage == null ? "" : stage)
                .putLong("done", done)
                .putLong("total", total)
                .putString("error", error)
                .putLong("updatedAt", System.currentTimeMillis())
                .commit();
    }

    @NonNull
    public static HorizonInstallState get(Context context) {
        SharedPreferences p = prefs(context);
        int status = p.getInt("status", STATUS_IDLE);
        long updated = p.getLong("updatedAt", 0);
        // A "running" state older than 5 minutes without updates belongs to a killed service.
        if (status == STATUS_RUNNING && System.currentTimeMillis() - updated > 5 * 60_000L) status = STATUS_FAILED;
        return new HorizonInstallState(status, p.getString("stage", ""), p.getLong("done", 0), p.getLong("total", 0),
                p.getString("error", null), updated);
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().commit();
    }

    public boolean isRunning() {
        return status == STATUS_RUNNING;
    }

    public int percent() {
        return percent(done, total);
    }

    static int percent(long done, long total) {
        if (total <= 0) return 0;
        return (int) Math.max(0, Math.min(100, done * 100 / total));
    }
}
