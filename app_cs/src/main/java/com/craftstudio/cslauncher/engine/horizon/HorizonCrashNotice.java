package com.craftstudio.cslauncher.engine.horizon;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.game.crash.GameCrashActivity;
import com.craftstudio.cslauncher.game.crash.GameExitPostmortem;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * CS Rust Edition stopped on its own: the engine process ({@code :horizon}) died with a Rust panic,
 * a native signal or an engine start-up error. The engine leaves what happened in its home
 * ({@code performance/last-rust-panic.txt}, {@code android-error.txt}, {@code crash-reports/}) and
 * Android keeps the process exit record; nothing of that was shown to the player before, the game
 * just vanished back to the launcher. The next time the launcher UI is up this reads both, writes
 * one readable report and shows a short notice with the first line of the cause and a "View report"
 * button (the crash screen). Normal exits (Quit Game, back to launcher) show nothing.
 */
public final class HorizonCrashNotice {
    private static final String PREFS = "cs_horizon_postmortem";
    private static final String KEY_SEEN = "seen_ts";
    public static final String REPORT_FILE = "cs_rust_exit_report.txt";
    private static final int MAX_FILE_CHARS = 24_000;

    private HorizonCrashNotice() {}

    /** Launcher UI entry point; cheap, does its reading off the UI thread. */
    public static void checkAndShow(@NonNull Activity activity) {
        final Context app = activity.getApplicationContext();
        CSLauncherApplication.sExecutorService.execute(() -> {
            GameExitPostmortem.Record found = null;
            File report;
            String headline;
            try {
                SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                long seen = prefs.getLong(KEY_SEEN, -1L);
                File home = HorizonEngine.home();
                File panic = new File(home, "performance/last-rust-panic.txt");
                File error = new File(home, "android-error.txt");
                long newest = 0;
                List<GameExitPostmortem.Record> records = GameExitPostmortem.readProcess(app, app.getPackageName() + ":horizon", 24);
                for (GameExitPostmortem.Record r : records) {
                    newest = Math.max(newest, r.timestamp);
                    if (r.abnormal && (found == null || r.timestamp > found.timestamp)) found = r;
                }
                long panicAt = panic.isFile() ? panic.lastModified() : 0;
                long errorAt = error.isFile() ? error.lastModified() : 0;
                newest = Math.max(newest, Math.max(panicAt, errorAt));
                if (seen < 0) {
                    // First run with this notice: today's history is the baseline, not "just happened".
                    prefs.edit().putLong(KEY_SEEN, newest).apply();
                    return;
                }
                if (newest <= seen) return;
                prefs.edit().putLong(KEY_SEEN, newest).apply();
                // What to show: an abnormal exit newer than the last one seen, or the engine's own
                // panic / error file written since then (a panic aborts, so both usually agree).
                boolean recordNew = found != null && found.timestamp > seen;
                boolean panicNew = panicAt > seen;
                boolean errorNew = errorAt > seen;
                if (!recordNew && !panicNew && !errorNew) return;
                if (!recordNew) found = null;

                StringBuilder extras = new StringBuilder();
                headline = null;
                if (panicNew) {
                    String text = head(panic);
                    extras.append("==== CS Engine: last Rust panic (performance/last-rust-panic.txt) ====\n").append(text).append("\n\n");
                    headline = panicLine(text);
                }
                if (errorNew) {
                    String text = head(error);
                    extras.append("==== CS Engine: start-up / event loop error (android-error.txt) ====\n").append(text).append("\n\n");
                    if (headline == null) headline = firstLine(text);
                }
                File newestCrash = newestCrashReport(new File(home, "crash-reports"), seen);
                if (newestCrash != null) {
                    extras.append("==== CS Engine: ").append(newestCrash.getName()).append(" ====\n").append(head(newestCrash)).append("\n\n");
                }
                File exit = new File(home, HorizonGameActivity.EXIT_FILE);
                if (exit.isFile()) {
                    extras.append("==== ").append(HorizonGameActivity.EXIT_FILE).append(" ====\n").append(head(exit)).append("\n\n");
                }
                if (headline == null) headline = found != null ? found.headline() : app.getString(R.string.rust_crash_unknown);
                report = GameExitPostmortem.writeReport(app, found, "CS Launcher Plus - CS Rust Edition post-mortem", extras.toString(), REPORT_FILE);
            } catch (Throwable t) {
                android.util.Log.w("HorizonCrashNotice", "post-mortem failed", t);
                return;
            }
            final File fReport = report;
            final String fHeadline = headline;
            final GameExitPostmortem.Record fFound = found;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                show(activity, fHeadline, fFound, fReport);
            });
        });
    }

    private static void show(@NonNull Activity activity, @NonNull String headline,
                             @Nullable GameExitPostmortem.Record r, @NonNull File report) {
        StringBuilder body = new StringBuilder();
        body.append(activity.getString(R.string.rust_crash_body)).append("\n\n").append(headline);
        if (r != null) body.append("\n\n").append(r.headline());
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.rust_crash_title)
                .setMessage(body)
                .setNegativeButton(R.string.pm_dismiss, null)
                .setPositiveButton(R.string.pm_view, (d, w) -> {
                    Intent i = GameCrashActivity.createIntent(activity, 0, true);
                    i.putExtra(GameCrashActivity.EXTRA_POSTMORTEM, report.getAbsolutePath());
                    i.putExtra(GameCrashActivity.EXTRA_POSTMORTEM_SUMMARY, headline);
                    i.setFlags(0);
                    try { activity.startActivity(i); } catch (Throwable ignored) {}
                });
        try { b.show(); } catch (Throwable ignored) {}
    }

    /** The panic message itself: the line after {@code panicked at ...}, else the first "panicked" line. */
    @NonNull
    private static String panicLine(@NonNull String text) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("panicked at")) {
                String where = lines[i].trim();
                String what = i + 1 < lines.length ? lines[i + 1].trim() : "";
                return what.isEmpty() ? where : what + "\n" + where;
            }
        }
        return firstLine(text);
    }

    @NonNull
    private static String firstLine(@NonNull String text) {
        for (String l : text.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("panic_timestamp_ms")) return t.length() > 300 ? t.substring(0, 300) + "…" : t;
        }
        return "";
    }

    @Nullable
    private static File newestCrashReport(@NonNull File dir, long since) {
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (!f.isFile() || f.lastModified() <= since) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    @NonNull
    private static String head(@NonNull File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null && sb.length() < MAX_FILE_CHARS) sb.append(line).append('\n');
            if (line != null) sb.append("… (truncated)\n");
        } catch (Exception e) {
            sb.append("(unreadable: ").append(e.getMessage()).append(")\n");
        }
        return sb.toString();
    }
}
