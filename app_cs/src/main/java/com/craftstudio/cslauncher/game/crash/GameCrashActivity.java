package com.craftstudio.cslauncher.game.crash;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.BaseActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.ShortcutLaunchActivity;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.utils.ProfileShortcuts;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen page shown when the game process ends with an error (non-zero exit or signal).
 * Replaces the old "exited with code N" dialog that closed the app: the player stays inside the
 * launcher, sees what happened, a diagnosis with advice, the log itself, and can share it, play
 * again or go back to the launcher.
 */
public class GameCrashActivity extends BaseActivity {
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_SIGNAL = "isSignal";

    private static final long READ_TAIL_BYTES = 256 * 1024;
    private static final int SHOW_LINES = 600;
    private static final int COLOR_ERROR = 0xFFFF8A8A;
    private static final int COLOR_WARN = 0xFFFFC862;
    private static final int COLOR_FRAME = 0xFF8A8D96;

    private final List<String> mLines = new ArrayList<>();
    private TextView mLog, mLogMeta, mChipErrors;
    private ScrollView mLogScroll;
    private boolean mErrorsOnly;
    private int mTotalLines;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_crash);

        int code = getIntent().getIntExtra(EXTRA_CODE, -1);
        boolean isSignal = getIntent().getBooleanExtra(EXTRA_SIGNAL, false);

        Instance instance = null;
        try { instance = Instances.loadSelectedInstance(); } catch (Throwable ignored) {}

        File logFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        readTail(logFile);

        ((TextView) findViewById(R.id.gc_title)).setText(isSignal ? R.string.gc_title_crash : R.string.gc_title_exit);
        StringBuilder meta = new StringBuilder();
        meta.append(isSignal ? getString(R.string.gc_meta_signal) : getString(R.string.gc_meta_code_fmt, code));
        if (instance != null) {
            if (instance.name != null && !instance.name.isEmpty()) meta.append("  \u00b7  ").append(instance.name);
            if (instance.versionId != null && !instance.versionId.isEmpty()) meta.append("  \u00b7  ").append(instance.versionId);
        }
        ((TextView) findViewById(R.id.gc_meta)).setText(meta);

        // Diagnosis
        CrashAnalyzer.Diagnosis dx = CrashAnalyzer.analyze(mLines, isSignal, code);
        TextView dxTitle = findViewById(R.id.gc_dx_title);
        TextView dxAdvice = findViewById(R.id.gc_dx_advice);
        TextView dxEvidence = findViewById(R.id.gc_dx_evidence);
        String title = getString(dx.titleRes);
        if (dx.detail != null) title = title + " \u2014 " + dx.detail;
        dxTitle.setText(title);
        dxAdvice.setText(dx.adviceRes);
        // A HotSpot hs_err file written for this crash is the best evidence we can get for a native death
        File hsErr = newestHsErr(instance);
        String hsSummary = hsErr == null ? null : CrashAnalyzer.summarizeHsErr(hsErr);
        String evidence = dx.evidence;
        if (hsSummary != null) evidence = evidence == null ? hsSummary : evidence + "\n" + hsSummary;
        if (evidence != null) dxEvidence.setText(evidence); else dxEvidence.setVisibility(View.GONE);

        // Log pane
        mLog = findViewById(R.id.gc_log);
        mLogMeta = findViewById(R.id.gc_log_meta);
        mLogScroll = findViewById(R.id.gc_log_scroll);
        mChipErrors = findViewById(R.id.gc_chip_errors);
        mChipErrors.setOnClickListener(v -> {
            mErrorsOnly = !mErrorsOnly;
            mChipErrors.setSelected(mErrorsOnly);
            renderLog();
        });
        findViewById(R.id.gc_chip_bottom).setOnClickListener(v -> mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN)));
        renderLog();
        mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));

        // Actions
        findViewById(R.id.gc_share).setOnClickListener(v -> Tools.shareLog(this, null));
        findViewById(R.id.gc_copy).setOnClickListener(v -> copyLog());
        final Instance fInstance = instance;
        View play = findViewById(R.id.gc_play);
        if (fInstance != null && fInstance.getKey() != null) {
            play.setOnClickListener(v -> {
                Intent intent = ProfileShortcuts.launchIntent(this, fInstance.getKey());
                startActivity(intent);
                finish();
            });
        } else {
            play.setVisibility(View.GONE);
        }
        findViewById(R.id.gc_launcher).setOnClickListener(v -> backToLauncher());

        // Newest crash report, if the game wrote one for this crash (last 10 minutes)
        File report = newestCrashReport(instance);
        TextView reportView = findViewById(R.id.gc_crash_report);
        if (report != null) {
            reportView.setVisibility(View.VISIBLE);
            reportView.setText(getString(R.string.gc_share_report_fmt, report.getName()));
            reportView.setOnClickListener(v -> {
                try { Tools.openPath(this, report, true); } catch (Throwable t) { Toast.makeText(this, R.string.gc_share_failed, Toast.LENGTH_SHORT).show(); }
            });
        }

        TextView hsView = findViewById(R.id.gc_hs_err);
        if (hsErr != null) {
            final File fHsErr = hsErr;
            hsView.setVisibility(View.VISIBLE);
            hsView.setText(getString(R.string.gc_share_hserr_fmt, fHsErr.getName()));
            hsView.setOnClickListener(v -> {
                try { Tools.openPath(this, fHsErr, true); } catch (Throwable t) { Toast.makeText(this, R.string.gc_share_failed, Toast.LENGTH_SHORT).show(); }
            });
        }

        // Entrance
        View left = findViewById(R.id.gc_left);
        left.setAlpha(0f); left.setTranslationX(-24f);
        left.animate().alpha(1f).translationX(0f).setDuration(280).start();
        View right = findViewById(R.id.gc_log_scroll);
        right.setAlpha(0f);
        right.animate().alpha(1f).setStartDelay(120).setDuration(300).start();
    }

    private void backToLauncher() {
        Tools.restartLauncherActivity(this);
        finish();
    }

    @Override
    public void onBackPressed() {
        backToLauncher();
    }

    private void readTail(File file) {
        mLines.clear();
        mTotalLines = 0;
        if (!file.isFile()) return;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - READ_TAIL_BYTES);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String text = new String(buf, "UTF-8");
            String[] all = text.split("\r?\n");
            int from = start > 0 && all.length > 1 ? 1 : 0; // first line may be cut in half
            mTotalLines = all.length - from;
            for (int i = Math.max(from, all.length - SHOW_LINES); i < all.length; i++) mLines.add(all[i]);
        } catch (Exception ignored) {
        }
    }

    private void renderLog() {
        if (mLines.isEmpty()) {
            mLog.setText(R.string.gc_no_log);
            mLogMeta.setText("latestlog.txt");
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int shown = 0;
        for (String line : mLines) {
            boolean err = CrashAnalyzer.isErrorLine(line);
            if (mErrorsOnly && !err) continue;
            int startIdx = sb.length();
            sb.append(line).append('\n');
            shown++;
            if (line.startsWith("\tat ") || line.trim().startsWith("at ")) {
                sb.setSpan(new ForegroundColorSpan(COLOR_FRAME), startIdx, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (err) {
                boolean warn = line.contains("WARN") && !line.contains("ERROR") && !line.contains("Exception");
                sb.setSpan(new ForegroundColorSpan(warn ? COLOR_WARN : COLOR_ERROR), startIdx, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (!warn) sb.setSpan(new StyleSpan(Typeface.BOLD), startIdx, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.contains("WARN")) {
                sb.setSpan(new ForegroundColorSpan(COLOR_WARN), startIdx, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (shown == 0) sb.append(getString(R.string.gc_no_errors));
        mLog.setText(sb);
        mLogMeta.setText(getString(R.string.gc_log_meta_fmt, mTotalLines, shown));
    }

    private void copyLog() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String l : mLines) sb.append(l).append('\n');
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("latestlog.txt", sb));
            Toast.makeText(this, R.string.gc_copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, R.string.gc_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Nullable
    private static File newestCrashReport(@Nullable Instance instance) {
        List<File> dirs = new ArrayList<>();
        try { if (instance != null) dirs.add(new File(instance.getGameDirectory(), "crash-reports")); } catch (Throwable ignored) {}
        if (Tools.DIR_HOME_CRASH != null) dirs.add(new File(Tools.DIR_HOME_CRASH));
        File best = null;
        for (File dir : dirs) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".txt")) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
        }
        if (best != null && System.currentTimeMillis() - best.lastModified() > 10 * 60 * 1000L) return null;
        return best;
    }

    /** Newest hs_err_pid*.log the JVM wrote in the last 10 minutes (game home, game dir, instance dir) */
    @Nullable
    private static File newestHsErr(@Nullable Instance instance) {
        List<File> dirs = new ArrayList<>();
        if (Tools.DIR_GAME_HOME != null) dirs.add(new File(Tools.DIR_GAME_HOME));
        if (Tools.DIR_GAME_NEW != null) dirs.add(new File(Tools.DIR_GAME_NEW));
        try { if (instance != null) dirs.add(instance.getGameDirectory()); } catch (Throwable ignored) {}
        File best = null;
        for (File dir : dirs) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName();
                if (!f.isFile() || !n.startsWith("hs_err_pid") || !n.endsWith(".log")) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
        }
        if (best != null && System.currentTimeMillis() - best.lastModified() > 10 * 60 * 1000L) return null;
        return best;
    }

    /** Entry point used by {@link com.craftstudio.cslauncher.ExitActivity} from the game process */
    public static Intent createIntent(@NonNull Context ctx, int code, boolean isSignal) {
        Intent i = new Intent(ctx, GameCrashActivity.class);
        i.putExtra(EXTRA_CODE, code);
        i.putExtra(EXTRA_SIGNAL, isSignal);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
