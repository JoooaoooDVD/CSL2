package com.craftstudio.cslauncher.engine.horizon;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;

/**
 * Install progress shared between the {@code :horizon} service and the launcher UI (multi-process prefs).
 *
 * <p>The engine reports six stages (see CS-FORK-NOTES "Install progress contract"). The UI shows one
 * overall percentage: the download is the bulk of the work but not the end of it — unpacking, world data,
 * controls and the final verification follow, and only {@link #STATUS_DONE} (set after the engine's
 * {@code ready()} check) means "installed".
 */
public final class HorizonInstallState {
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELLED = 4;

    /** Engine stages in order (labels are the engine's English stage names). */
    public static final int STAGE_UNKNOWN = -1;
    public static final int STAGE_METADATA = 0;
    public static final int STAGE_DOWNLOAD = 1;
    public static final int STAGE_EXTRACT = 2;
    public static final int STAGE_WORLD_DATA = 3;
    public static final int STAGE_CONTROLS = 4;
    public static final int STAGE_FINALIZE = 5;
    /** Not an engine stage: the service verifies the result after the engine returned. */
    public static final int STAGE_VERIFY = 6;

    /** Overall-percent window of every stage: [start, end]. */
    private static final int[][] WINDOWS = {
            {0, 2},     // metadata
            {2, 72},    // download
            {72, 90},   // unpack
            {90, 94},   // world data
            {94, 97},   // controls
            {97, 99},   // finalize
            {99, 100},  // verify
    };

    private static final String PREFS = "cs_horizon_install";
    /** The engine sends a heartbeat every second; the service itself every 3 s. */
    private static final long STALE_AFTER_MS = 45_000L;

    public final int status;
    @NonNull public final String stage;
    public final long done, total;
    @Nullable public final String error;
    public final long updatedAt;
    /** True when a RUNNING state stopped updating (service/process died). */
    public final boolean stale;

    private HorizonInstallState(int status, @NonNull String stage, long done, long total, @Nullable String error,
                                long updatedAt, boolean stale) {
        this.status = status;
        this.stage = stage;
        this.done = done;
        this.total = total;
        this.error = error;
        this.updatedAt = updatedAt;
        this.stale = stale;
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

    /** Heartbeat of the service: keeps a RUNNING state fresh without changing it. */
    static void touch(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getInt("status", STATUS_IDLE) != STATUS_RUNNING) return;
        p.edit().putLong("updatedAt", System.currentTimeMillis()).commit();
    }

    @NonNull
    public static HorizonInstallState get(Context context) {
        SharedPreferences p = prefs(context);
        int status = p.getInt("status", STATUS_IDLE);
        long updated = p.getLong("updatedAt", 0);
        boolean stale = status == STATUS_RUNNING && System.currentTimeMillis() - updated > STALE_AFTER_MS;
        return new HorizonInstallState(status, p.getString("stage", ""), p.getLong("done", 0), p.getLong("total", 0),
                p.getString("error", null), updated, stale);
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().commit();
    }

    /** Running and alive. */
    public boolean isRunning() {
        return status == STATUS_RUNNING && !stale;
    }

    /** A RUNNING record nobody updates any more: the install was interrupted (process killed, reboot). */
    public boolean isInterrupted() {
        return status == STATUS_RUNNING && stale;
    }

    public int stageIndex() {
        return stageIndex(stage);
    }

    /** Maps the engine's stage label to {@link #STAGE_METADATA}… (prefix match, English labels). */
    public static int stageIndex(@Nullable String label) {
        if (label == null) return STAGE_UNKNOWN;
        String l = label.toLowerCase(java.util.Locale.ROOT);
        if (l.startsWith("checking")) return STAGE_METADATA;
        if (l.startsWith("downloading touch")) return STAGE_CONTROLS;
        if (l.startsWith("downloading")) return STAGE_DOWNLOAD;
        if (l.startsWith("unpacking")) return STAGE_EXTRACT;
        if (l.startsWith("preparing")) return STAGE_WORLD_DATA;
        if (l.startsWith("finishing")) return STAGE_FINALIZE;
        if (l.startsWith("verifying")) return STAGE_VERIFY;
        return STAGE_UNKNOWN;
    }

    /** Percent inside the current stage (0 when the stage reports no totals). */
    public int stagePercent() {
        return percent(done, total);
    }

    /**
     * Overall percent 0–100. Reaches 100 only for {@link #STATUS_DONE}; a finished download shows ~72%
     * so the user sees the preparation that still follows.
     */
    public int overallPercent() {
        if (status == STATUS_DONE) return 100;
        if (status != STATUS_RUNNING) return 0;
        int index = stageIndex();
        if (index < 0) return 0;
        int[] window = WINDOWS[index];
        int span = window[1] - window[0];
        int inner = total > 0 ? stagePercent() : 0;
        return Math.min(99, window[0] + span * inner / 100);
    }

    /** Localised name of the current stage for the UI. */
    @NonNull
    public String stageLabel(@NonNull Context context) {
        switch (stageIndex()) {
            case STAGE_METADATA: return context.getString(R.string.horizon_stage_metadata);
            case STAGE_DOWNLOAD: return context.getString(R.string.horizon_stage_download);
            case STAGE_EXTRACT: return context.getString(R.string.horizon_stage_extract);
            case STAGE_WORLD_DATA: return context.getString(R.string.horizon_stage_world_data);
            case STAGE_CONTROLS: return context.getString(R.string.horizon_stage_controls);
            case STAGE_FINALIZE: return context.getString(R.string.horizon_stage_finalize);
            case STAGE_VERIFY: return context.getString(R.string.horizon_stage_verify);
            default: return stage.isEmpty() ? context.getString(R.string.horizon_install_preparing) : stage;
        }
    }

    /** "245 MB / 512 MB" for the download stage, "1 240 / 15 300 files" for unpacking, empty otherwise. */
    @NonNull
    public String detailLabel(@NonNull Context context) {
        if (status != STATUS_RUNNING || total <= 0) return "";
        int index = stageIndex();
        if (index == STAGE_DOWNLOAD) {
            return context.getString(R.string.horizon_detail_bytes, mb(done), mb(total));
        }
        if (index == STAGE_EXTRACT || index == STAGE_CONTROLS) {
            return context.getString(R.string.horizon_detail_files, done, total);
        }
        return "";
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.0f", bytes / 1048576.0);
    }

    static int percent(long done, long total) {
        if (total <= 0) return 0;
        return (int) Math.max(0, Math.min(100, done * 100 / total));
    }
}
