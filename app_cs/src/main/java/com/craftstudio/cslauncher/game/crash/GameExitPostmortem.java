package com.craftstudio.cslauncher.game.crash;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.BuildConfig;
import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Post-mortem for the {@code :game} process when it disappears WITHOUT any of our own
 * reporting firing: no Java crash report (the uncaught-exception handler never ran), no JVM
 * {@code hs_err} file and no exit dialog (the JVM never reached its exit hook). That is what
 * a native abort inside ART (CheckJNI "JNI DETECTED ERROR", "Thread suspension timed out"),
 * a SIGKILL from the low-memory killer or a crash that never reached a signal handler look
 * like from the outside: the game just vanishes and the launcher is back.
 * <p>
 * Android keeps the reason anyway. Since Android 11 {@link ActivityManager#getHistoricalProcessExitReasons}
 * returns, per process, WHY it died (reason, signal / exit status, system description, memory at
 * the time) and - for native crashes and ANRs - the tombstone / ANR trace itself. This class reads
 * that record the next time the launcher UI shows up, writes a readable report next to
 * {@code latestlog.txt} and opens the crash screen with it, so a "silent" death is never
 * silent again. It is also appended to the Virtual LAN "Send logs" report.
 */
public final class GameExitPostmortem {
    public static final String REPORT_FILE = "cs_game_exit_report.txt";
    private static final String PREFS = "cs_postmortem";
    private static final String KEY_LAST_TS = "last_exit_ts";
    private static final String KEY_SHOWN_TS = "last_shown_ts";
    private static final int MAX_TRACE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_HIGHLIGHT_CHARS = 14_000;
    private static final int MAX_STRINGS_CHARS = 36_000;

    private static final String KEY_VLAN_DISCONNECT_AT = "vlan_disconnect_at";
    private static final String KEY_LAUNCHER_LAST_TS = "launcher_last_exit_ts";
    /** A launcher-process death this soon after Disconnect is attributed to it */
    private static final long VLAN_DISCONNECT_WINDOW_MS = 45_000L;

    private GameExitPostmortem() {}

    /**
     * Virtual LAN "Disconnect / End session" was just pressed in the launcher UI. If the launcher
     * process dies within the next seconds, the next start reports that death (with Android's
     * reason and trace) instead of staying silent.
     */
    public static void markVirtualLanDisconnect(@NonNull Context ctx) {
        try {
            ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_VLAN_DISCONNECT_AT, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {}
    }

    /** Summary of one exit record, enough for a dialog line and for the report header */
    public static final class Record {
        public final long timestamp;
        public final String process;
        public final int reason;
        public final int status;
        public final String reasonName;
        public final String statusText;
        public final String description;
        public final String explanation;
        public final long pssKb, rssKb;
        public final int importance;
        public final boolean abnormal;
        @Nullable public final ApplicationExitInfo info;

        Record(long timestamp, String process, int reason, int status, String description,
               long pssKb, long rssKb, int importance, @Nullable ApplicationExitInfo info) {
            this.timestamp = timestamp;
            this.process = process;
            this.reason = reason;
            this.status = status;
            this.description = description == null ? "" : description;
            this.pssKb = pssKb;
            this.rssKb = rssKb;
            this.importance = importance;
            this.info = info;
            this.reasonName = reasonName(reason);
            this.statusText = statusText(reason, status);
            this.explanation = explain(reason, status);
            this.abnormal = isAbnormal(reason, status);
        }

        /** One-line human summary: "Native crash (SIGABRT, abort) · 14:51:23" */
        @NonNull public String headline() {
            StringBuilder sb = new StringBuilder(reasonName);
            if (!statusText.isEmpty()) sb.append(" (").append(statusText).append(')');
            sb.append(" \u00b7 ").append(new SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(new Date(timestamp)));
            return sb.toString();
        }
    }

    /** Is the post-mortem available on this device at all */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * Launcher UI entry point: look for a game-process death newer than the last one we saw, write
     * the report and show it. Safe to call on every resume; it does its work off the UI thread.
     */
    public static void checkAndShow(@NonNull Activity activity) {
        if (!supported()) return;
        final Context app = activity.getApplicationContext();
        CSLauncherApplication.sExecutorService.execute(() -> {
            Record found = null;
            File report = null;
            try {
                SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                long lastSeen = prefs.getLong(KEY_LAST_TS, -1L);
                List<Record> records = read(app, 24);
                if (lastSeen < 0) {
                    // First run with this feature: take the newest existing record as the baseline so
                    // old history (before the update) is not reported as "just happened".
                    long newest = 0;
                    for (Record r : records) newest = Math.max(newest, r.timestamp);
                    prefs.edit().putLong(KEY_LAST_TS, newest).apply();
                    return;
                }
                long newestTs = lastSeen;
                for (Record r : records) {
                    if (r.timestamp <= lastSeen) continue;
                    newestTs = Math.max(newestTs, r.timestamp);
                    if (r.abnormal && (found == null || r.timestamp > found.timestamp)) found = r;
                }
                if (newestTs != lastSeen) prefs.edit().putLong(KEY_LAST_TS, newestTs).apply();
                if (found == null) {
                    // Launcher process itself: only when it died right after a Virtual LAN Disconnect
                    long marker = prefs.getLong(KEY_VLAN_DISCONNECT_AT, 0L);
                    if (marker > 0L) {
                        long seenLauncher = prefs.getLong(KEY_LAUNCHER_LAST_TS, 0L);
                        for (Record r : readProcess(app, app.getPackageName() + ":launcher", 24)) {
                            if (r.timestamp <= seenLauncher || r.timestamp < marker || r.timestamp > marker + VLAN_DISCONNECT_WINDOW_MS) continue;
                            if (r.abnormal && (found == null || r.timestamp > found.timestamp)) found = r;
                        }
                        if (found != null) prefs.edit().putLong(KEY_LAUNCHER_LAST_TS, found.timestamp).remove(KEY_VLAN_DISCONNECT_AT).apply();
                        else if (System.currentTimeMillis() - marker > VLAN_DISCONNECT_WINDOW_MS) prefs.edit().remove(KEY_VLAN_DISCONNECT_AT).apply();
                    }
                }
                if (found == null) return;
                report = writeReport(app, found);
                prefs.edit().putLong(KEY_SHOWN_TS, found.timestamp).apply();
            } catch (Throwable t) {
                android.util.Log.w("GameExitPostmortem", "post-mortem failed", t);
                return;
            }
            final Record fFound = found;
            final File fReport = report;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                showDialog(activity, fFound, fReport);
            });
        });
    }

    private static void showDialog(@NonNull Activity activity, @NonNull Record r, @Nullable File report) {
        StringBuilder body = new StringBuilder();
        boolean launcher = r.process != null && r.process.endsWith(":launcher");
        body.append(activity.getString(launcher ? R.string.pm_body_intro_launcher : R.string.pm_body_intro)).append("\n\n");
        body.append(r.headline()).append('\n');
        if (!r.description.isEmpty()) body.append(r.description.trim()).append('\n');
        body.append('\n').append(r.explanation);
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(activity)
                .setTitle(launcher ? R.string.pm_title_launcher : R.string.pm_title)
                .setMessage(body)
                .setNegativeButton(R.string.pm_dismiss, null);
        if (report != null) {
            b.setPositiveButton(R.string.pm_view, (d, w) -> {
                Intent i = GameCrashActivity.createIntent(activity, 0, true);
                i.putExtra(GameCrashActivity.EXTRA_POSTMORTEM, report.getAbsolutePath());
                i.putExtra(GameCrashActivity.EXTRA_POSTMORTEM_SUMMARY, r.headline());
                i.setFlags(0);
                try { activity.startActivity(i); } catch (Throwable ignored) {}
            });
        }
        try { b.show(); } catch (Throwable ignored) {}
    }

    // ───────────────────────────── reading ─────────────────────────────

    /** All exit records of the game process, newest first (empty below Android 11) */
    @NonNull
    public static List<Record> read(@NonNull Context ctx, int max) {
        return readProcess(ctx, ctx.getPackageName() + ":game", max);
    }

    /** Exit records of one of our processes (full process name), newest first */
    @NonNull
    public static List<Record> readProcess(@NonNull Context ctx, @NonNull String gameProcess, int max) {
        List<Record> out = new ArrayList<>();
        if (!supported()) return out;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return out;
            List<ApplicationExitInfo> infos = am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, Math.max(1, max));
            if (infos == null) return out;
            for (ApplicationExitInfo info : infos) {
                if (info == null || info.getProcessName() == null) continue;
                if (!gameProcess.equals(info.getProcessName())) continue;
                out.add(new Record(info.getTimestamp(), info.getProcessName(), info.getReason(), info.getStatus(),
                        info.getDescription(), info.getPss(), info.getRss(), info.getImportance(), info));
            }
        } catch (Throwable t) {
            android.util.Log.w("GameExitPostmortem", "exit reasons unavailable", t);
        }
        return out;
    }

    /** Section for diagnostics bundles (Virtual LAN "Send logs"): the last few game-process exits */
    @NonNull
    public static String sectionForReport(@NonNull Context ctx) {
        if (!supported()) return "(Android " + Build.VERSION.RELEASE + ": process exit reasons need Android 11+)\n";
        List<Record> records = read(ctx, 16);
        if (records.isEmpty()) return "(no exit record for the game process yet)\n";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Record r : records) {
            if (n++ >= 5) break;
            sb.append(r.headline()).append('\n');
            sb.append("  reason=").append(r.reason).append(" status=").append(r.status)
                    .append(" importance=").append(r.importance)
                    .append(" pss=").append(r.pssKb / 1024).append("MB rss=").append(r.rssKb / 1024).append("MB\n");
            if (!r.description.isEmpty()) sb.append("  description: ").append(r.description.trim().replace('\n', ' ')).append('\n');
            sb.append("  -> ").append(r.explanation).append('\n');
        }
        Record newest = records.get(0);
        if (newest.abnormal) {
            String trace = extractTrace(newest, 6000, 6000);
            if (!trace.isEmpty()) sb.append("\n-- trace of the newest record --\n").append(trace).append('\n');
        }
        // Launcher process too: a death right after Disconnect shows up here, not under :game
        List<Record> launcher = readProcess(ctx, ctx.getPackageName() + ":launcher", 16);
        if (!launcher.isEmpty()) {
            sb.append("\n-- launcher process exits --\n");
            int m = 0;
            for (Record r : launcher) {
                if (m++ >= 3) break;
                sb.append(r.headline()).append(r.abnormal ? "  [abnormal]" : "").append('\n');
                if (!r.description.isEmpty()) sb.append("  description: ").append(r.description.trim().replace('\n', ' ')).append('\n');
            }
            Record newestL = launcher.get(0);
            if (newestL.abnormal) {
                String trace = extractTrace(newestL, 6000, 6000);
                if (!trace.isEmpty()) sb.append("-- trace of the newest launcher record --\n").append(trace).append('\n');
            }
        }
        File report = new File(Tools.DIR_GAME_HOME, REPORT_FILE);
        if (report.isFile()) sb.append("(full report: ").append(report.getAbsolutePath()).append(")\n");
        return sb.toString();
    }

    // ───────────────────────────── report ─────────────────────────────

    @NonNull
    private static File writeReport(@NonNull Context ctx, @NonNull Record r) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("CS Launcher Plus - game process post-mortem\n");
        sb.append("Written: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date())).append('\n');
        sb.append("Launcher: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(", ").append(BuildConfig.BUILD_TYPE).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");
        sb.append("==== What Android recorded for ").append(r.process).append(" ====\n");
        sb.append("Time:        ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(r.timestamp))).append('\n');
        sb.append("Reason:      ").append(r.reasonName).append(" (").append(r.reason).append(")\n");
        sb.append("Status:      ").append(r.statusText.isEmpty() ? String.valueOf(r.status) : r.statusText + " [" + r.status + "]").append('\n');
        sb.append("Description: ").append(r.description.isEmpty() ? "-" : r.description.trim()).append('\n');
        sb.append("Importance:  ").append(importanceName(r.importance)).append(" (").append(r.importance).append(")\n");
        sb.append("Memory:      pss ").append(r.pssKb / 1024).append(" MB, rss ").append(r.rssKb / 1024).append(" MB\n");
        sb.append("Meaning:     ").append(r.explanation).append("\n\n");
        String trace = extractTrace(r, MAX_HIGHLIGHT_CHARS, MAX_STRINGS_CHARS);
        if (!trace.isEmpty()) {
            sb.append("==== Trace captured by Android (tombstone / ANR) ====\n").append(trace).append('\n');
        } else {
            sb.append("==== Trace ====\n(Android attached no trace to this record)\n");
        }
        File out = new File(Tools.DIR_GAME_HOME, REPORT_FILE);
        //noinspection ResultOfMethodCallIgnored
        out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    /**
     * The trace Android stored with the record. ANR traces are plain text; native-crash tombstones
     * are a protobuf blob (Android 12+), from which the printable strings - signal line, abort
     * message, thread names, backtrace symbols - are pulled the way {@code strings(1)} would.
     */
    @NonNull
    private static String extractTrace(@NonNull Record r, int highlightChars, int stringsChars) {
        if (r.info == null || !supported()) return "";
        byte[] data;
        try (InputStream in = r.info.getTraceInputStream()) {
            if (in == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total >= MAX_TRACE_BYTES) break;
            }
            data = bos.toByteArray();
        } catch (Throwable t) {
            return "";
        }
        if (data.length == 0) return "";
        int printable = 0;
        int sample = Math.min(data.length, 4096);
        for (int i = 0; i < sample; i++) {
            int c = data[i] & 0xFF;
            if (c == '\n' || c == '\r' || c == '\t' || (c >= 0x20 && c < 0x7F)) printable++;
        }
        if (printable > sample * 0.97) {
            String text = new String(data, StandardCharsets.UTF_8);
            return text.length() > stringsChars ? text.substring(0, stringsChars) + "\n[...]" : text;
        }
        // binary (tombstone proto): printable runs
        List<String> runs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (byte datum : data) {
            int c = datum & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                cur.append((char) c);
            } else {
                if (cur.length() >= 5) runs.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() >= 5) runs.add(cur.toString());
        StringBuilder high = new StringBuilder();
        StringBuilder all = new StringBuilder();
        String[] keys = {"signal", "SIG", "abort", "Abort", "JNI", "Check failed", "CHECK", "backtrace", "Cause", "fault addr",
                "pc 0", "/lib/", ".so", "thread", "Thread", "art::", "libart", "libc", "java", "Fatal", "FATAL", "suspension", "OutOfMemory", "stack", "tombstone", "pid:", "tid:"};
        for (String run : runs) {
            if (all.length() < stringsChars) all.append(run).append('\n');
            if (high.length() < highlightChars) {
                for (String k : keys) {
                    if (run.contains(k)) { high.append(run).append('\n'); break; }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("(binary tombstone, ").append(data.length).append(" bytes; readable strings extracted)\n");
        if (high.length() > 0) sb.append("-- highlights --\n").append(high);
        if (all.length() > 0) sb.append("-- all strings --\n").append(all);
        return sb.toString();
    }

    // ───────────────────────────── naming / meaning ─────────────────────────────

    private static boolean isAbnormal(int reason, int status) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return status != 0; // System.exit(0) is the normal game exit
            case ApplicationExitInfo.REASON_USER_REQUESTED:
            case ApplicationExitInfo.REASON_USER_STOPPED:
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED:
                return false;
            default: return true;
        }
    }

    @NonNull
    static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "Exited by itself";
            case ApplicationExitInfo.REASON_SIGNALED: return "Killed by a signal";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "Killed by the low-memory killer";
            case ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "Native crash";
            case ApplicationExitInfo.REASON_ANR: return "Not responding (ANR)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "Initialization failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "Permission change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "Excessive resource usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "Stopped by the user";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "User stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "A dependency died";
            case ApplicationExitInfo.REASON_OTHER: return "Killed by the system (other)";
            case ApplicationExitInfo.REASON_FREEZER: return "Frozen by the system";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "Package state change";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "Package updated";
            default: return "Unknown (" + reason + ")";
        }
    }

    @NonNull
    static String statusText(int reason, int status) {
        if (reason == ApplicationExitInfo.REASON_SIGNALED || reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            return signalName(status);
        }
        if (reason == ApplicationExitInfo.REASON_EXIT_SELF) return "exit code " + status;
        return status == 0 ? "" : String.valueOf(status);
    }

    @NonNull
    static String signalName(int sig) {
        switch (sig) {
            case 4: return "SIGILL, illegal instruction";
            case 5: return "SIGTRAP";
            case 6: return "SIGABRT, abort() - runtime check failed";
            case 7: return "SIGBUS, bad memory access";
            case 8: return "SIGFPE";
            case 9: return "SIGKILL, killed from outside";
            case 11: return "SIGSEGV, invalid memory access";
            case 15: return "SIGTERM";
            default: return "signal " + sig;
        }
    }

    @NonNull
    static String explain(int reason, int status) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                if (status == 6) return "The process aborted itself: a native runtime check failed (ART/JNI error, native assertion or a library calling abort). The trace below names the thread and the message.";
                return "Native code crashed (" + signalName(status) + "). The trace below shows the library and the thread that faulted.";
            case ApplicationExitInfo.REASON_SIGNALED:
                if (status == 9) return "The process was killed with SIGKILL from outside: the system (memory pressure, background restrictions) or another component - it did not crash on its own.";
                return "The process was terminated by " + signalName(status) + ".";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "Android's low-memory killer stopped the game to free RAM. Lower the RAM allocation in the profile, close other apps, or use a lighter renderer / fewer mods.";
            case ApplicationExitInfo.REASON_ANR:
                return "The process stopped responding and Android killed it. The ANR trace below shows what the main thread was doing.";
            case ApplicationExitInfo.REASON_CRASH:
                return "An uncaught Java exception in the launcher part of the game process.";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "Android killed the process for excessive CPU / resource usage in the background.";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "A process this one depended on (a bound service or provider) died, so Android removed this one as well.";
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return status == 0 ? "Normal exit." : "The game exited with a non-zero code; see latestlog.txt for the Java-side reason.";
            case ApplicationExitInfo.REASON_FREEZER:
                return "The process was frozen by the system while cached.";
            case ApplicationExitInfo.REASON_OTHER:
                return "Killed by the system for a reason it described in the description line.";
            default:
                return "See the description and trace below.";
        }
    }

    @NonNull
    private static String importanceName(int importance) {
        switch (importance) {
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND: return "foreground";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE: return "foreground service";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE: return "visible";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE: return "perceptible";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE: return "service";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED: return "cached";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE: return "gone";
            default: return "level " + importance;
        }
    }
}
