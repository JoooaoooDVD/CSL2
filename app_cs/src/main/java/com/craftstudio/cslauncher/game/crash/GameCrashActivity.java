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
import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.game.crash.autofix.CrashAnalysis;
import com.craftstudio.cslauncher.game.crash.autofix.CrashCategory;
import com.craftstudio.cslauncher.game.crash.autofix.CrashClassifier;
import com.craftstudio.cslauncher.game.crash.autofix.CrashContext;
import com.craftstudio.cslauncher.game.crash.autofix.RepairEngine;
import com.craftstudio.cslauncher.game.crash.autofix.RepairHistory;
import com.craftstudio.cslauncher.game.crash.autofix.RepairReport;
import com.craftstudio.cslauncher.game.crash.autofix.RepairStep;
import android.widget.ProgressBar;
import androidx.appcompat.app.AlertDialog;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
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

    // Auto fix state (all touched on the UI thread only)
    private CrashContext mCtx;
    private CrashAnalysis mAnalysis;
    private List<RepairStep> mEffectivePlan = new ArrayList<>();
    private boolean mBudgetExhausted;
    private boolean mFixRunning;
    private boolean mFixApplied;
    private File mCrashReport, mHsErr;
    private String mHsSummary;
    private TextView mSubtitle, mDxTitle, mDxAdvice, mDxEvidence, mAfConfidence, mAfComponent, mAfPlan, mAfStatus,
            mAfFix, mAfRetry, mAfReport, mAfDetails, mAfUndo, mAfDetailsText;
    private View mAfDetailsPane, mAfProgress;

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

        mSubtitle = findViewById(R.id.gc_subtitle);
        mDxTitle = findViewById(R.id.gc_dx_title);
        mDxAdvice = findViewById(R.id.gc_dx_advice);
        mDxEvidence = findViewById(R.id.gc_dx_evidence);
        mAfConfidence = findViewById(R.id.gc_af_confidence);
        mAfComponent = findViewById(R.id.gc_af_component);
        mAfPlan = findViewById(R.id.gc_af_plan);
        mAfStatus = findViewById(R.id.gc_af_status);
        mAfProgress = findViewById(R.id.gc_af_progress);
        mAfFix = findViewById(R.id.gc_af_fix);
        mAfRetry = findViewById(R.id.gc_af_retry);
        mAfReport = findViewById(R.id.gc_af_report);
        mAfDetails = findViewById(R.id.gc_af_details);
        mAfUndo = findViewById(R.id.gc_af_undo);
        mAfDetailsPane = findViewById(R.id.gc_af_details_pane);
        mAfDetailsText = findViewById(R.id.gc_af_details_text);

        // Provisional diagnosis from the legacy analyzer (instant); the auto-fix classifier
        // replaces it a moment later from a worker thread.
        CrashAnalyzer.Diagnosis dx = CrashAnalyzer.analyze(mLines, isSignal, code);
        String title = getString(dx.titleRes);
        if (dx.detail != null) title = title + " \u2014 " + dx.detail;
        mDxTitle.setText(title);
        mDxAdvice.setText(dx.adviceRes);
        if (dx.evidence != null) mDxEvidence.setText(dx.evidence); else mDxEvidence.setVisibility(View.GONE);
        mAfFix.setEnabled(false);
        mAfFix.setAlpha(0.5f);
        mAfPlan.setText(R.string.gc_subtitle_analyzing);

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
        // "Play again" is superseded by RETRY LAUNCH in the auto-fix card
        findViewById(R.id.gc_play).setVisibility(View.GONE);
        boolean canLaunch = fInstance != null && fInstance.getKey() != null;
        mAfRetry.setOnClickListener(v -> retryLaunch());
        if (!canLaunch) { mAfRetry.setEnabled(false); mAfRetry.setAlpha(0.5f); }
        View launcherBtn = findViewById(R.id.gc_launcher);
        launcherBtn.setOnClickListener(v -> backToLauncher());
        if (launcherBtn.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            ((android.view.ViewGroup.MarginLayoutParams) launcherBtn.getLayoutParams()).setMarginStart(0);
        }
        mAfFix.setOnClickListener(v -> onAutoFixPressed());
        mAfReport.setOnClickListener(v -> viewCrashReport());
        mAfDetails.setOnClickListener(v -> toggleDetails());
        mAfUndo.setOnClickListener(v -> undoLastFix());

        // Newest crash report / hs_err, if the game wrote one for this crash (last 10 minutes)
        mCrashReport = newestCrashReport(instance);
        findViewById(R.id.gc_crash_report).setVisibility(View.GONE); // replaced by VIEW CRASH REPORT
        mHsErr = newestHsErr(instance);
        TextView hsView = findViewById(R.id.gc_hs_err);
        if (mHsErr != null) {
            final File fHsErr = mHsErr;
            hsView.setVisibility(View.VISIBLE);
            hsView.setText(getString(R.string.gc_share_hserr_fmt, fHsErr.getName()));
            hsView.setOnClickListener(v -> {
                try { Tools.openPath(this, fHsErr, true); } catch (Throwable t) { Toast.makeText(this, R.string.gc_share_failed, Toast.LENGTH_SHORT).show(); }
            });
        }

        // Classification on a worker: reads mod jars / history, must not block the first frame.
        final int fCode = code;
        final boolean fSignal = isSignal;
        final List<String> snapshot = new ArrayList<>(mLines);
        CSLauncherApplication.sExecutorService.execute(() -> {
            String hsSummary = mHsErr == null ? null : CrashAnalyzer.summarizeHsErr(mHsErr);
            CrashContext ctx = new CrashContext(this, fInstance, fCode, fSignal, snapshot, hsSummary);
            CrashAnalysis analysis = CrashClassifier.analyze(new CrashClassifier.Input(snapshot, fCode, fSignal, hsSummary, ctx.loader));
            List<RepairStep> plan;
            boolean budget = false;
            boolean hasUndo = false;
            if (ctx.hasProfile()) {
                RepairHistory history = new RepairHistory(ctx.historyFile);
                // Same crash after an applied fix → that fix did not work; never repeat it.
                history.markRelapse(ctx.instanceKey, analysis.signature);
                plan = history.filterPlan(ctx.instanceKey, analysis.plan);
                budget = !history.canAttempt(ctx.instanceKey, analysis.signature);
                hasUndo = history.lastRevertible(ctx.instanceKey) != null;
            } else {
                plan = new ArrayList<>();
            }
            final List<RepairStep> fPlan = plan;
            final boolean fBudget = budget, fUndo = hasUndo;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mCtx = ctx;
                mHsSummary = hsSummary;
                mAnalysis = analysis;
                mEffectivePlan = fPlan;
                mBudgetExhausted = fBudget;
                mAfUndo.setVisibility(fUndo ? View.VISIBLE : View.GONE);
                bindAnalysis(dx);
            });
        });

        // Entrance
        View left = findViewById(R.id.gc_left);
        left.setAlpha(0f); left.setTranslationX(-24f);
        left.animate().alpha(1f).translationX(0f).setDuration(280).start();
        View right = findViewById(R.id.gc_log_scroll);
        right.setAlpha(0f);
        right.animate().alpha(1f).setStartDelay(120).setDuration(300).start();
    }

    // ---- auto fix UI --------------------------------------------------------------------------

    private void bindAnalysis(CrashAnalyzer.Diagnosis legacy) {
        CrashAnalysis a = mAnalysis;
        boolean unknown = a.category == CrashCategory.UNKNOWN_ERROR;
        mSubtitle.setText(unknown ? R.string.gc_subtitle_unknown : R.string.gc_subtitle);

        // Diagnosis card: the classifier's verdict, or the legacy analyzer's when ours is unknown
        if (unknown && legacy.titleRes != R.string.gc_dx_unknown_title) {
            // keep the legacy text already shown (account / world / LAN cases)
        } else {
            String title = getString(categoryLabel(a.category));
            if (!a.suspectComponent.isEmpty() && a.category != CrashCategory.OUT_OF_MEMORY) title = title + " \u2014 " + a.suspectComponent;
            mDxTitle.setText(title);
            mDxAdvice.setText(getString(categoryWhy(a.category)) + "\n" + getString(categoryAdvice(a.category)));
            String evidence = a.evidenceText();
            if (mHsSummary != null) evidence = evidence.isEmpty() ? mHsSummary : evidence + "\n" + mHsSummary;
            if (evidence.isEmpty()) mDxEvidence.setVisibility(View.GONE);
            else { mDxEvidence.setVisibility(View.VISIBLE); mDxEvidence.setText(evidence); }
        }

        mAfConfidence.setVisibility(View.VISIBLE);
        mAfConfidence.setText(getString(R.string.gc_af_confidence_fmt, Math.round(a.confidence * 100)));
        if (a.suspectComponent.isEmpty()) mAfComponent.setVisibility(View.GONE);
        else { mAfComponent.setVisibility(View.VISIBLE); mAfComponent.setText(getString(R.string.gc_af_component_fmt, a.suspectComponent)); }

        boolean canFix;
        if (mCtx == null || !mCtx.hasProfile() || a.plan.isEmpty() || !a.safeToAutoFix) {
            mAfPlan.setText(R.string.gc_af_plan_none);
            canFix = false;
        } else if (mBudgetExhausted) {
            mAfPlan.setText(getString(R.string.gc_af_plan_budget, RepairHistory.MAX_ATTEMPTS_PER_SIGNATURE));
            canFix = false;
        } else if (mEffectivePlan.isEmpty()) {
            mAfPlan.setText(R.string.gc_af_plan_filtered);
            canFix = false;
        } else {
            StringBuilder sb = new StringBuilder();
            for (RepairStep step : mEffectivePlan) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("\u2022 ").append(stepLabel(step));
            }
            mAfPlan.setText(sb);
            canFix = true;
        }
        mAfFix.setEnabled(canFix && !mFixRunning);
        mAfFix.setAlpha(canFix && !mFixRunning ? 1f : 0.5f);
        if (mAfDetailsPane.getVisibility() == View.VISIBLE) mAfDetailsText.setText(buildDetails());
    }

    private void onAutoFixPressed() {
        if (mFixRunning || RepairEngine.isRunning()) { Toast.makeText(this, R.string.gc_af_busy, Toast.LENGTH_SHORT).show(); return; }
        if (mAnalysis == null || mCtx == null || mEffectivePlan.isEmpty()) return;
        if (mAnalysis.requiresConfirmation) {
            StringBuilder sb = new StringBuilder();
            for (RepairStep step : mEffectivePlan) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("\u2022 ").append(stepLabel(step));
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.gc_af_confirm_title)
                    .setMessage(getString(R.string.gc_af_confirm_msg, sb))
                    .setPositiveButton(R.string.gc_af_confirm_ok, (d, w) -> startAutoFix())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            startAutoFix();
        }
    }

    private void startAutoFix() {
        if (mFixRunning) return;
        mFixRunning = true;
        mAfFix.setEnabled(false);
        mAfFix.setAlpha(0.5f);
        mAfRetry.setEnabled(false);
        mAfRetry.setAlpha(0.5f);
        mAfUndo.setEnabled(false);
        mAfProgress.setVisibility(View.VISIBLE);
        mAfStatus.setVisibility(View.VISIBLE);
        mAfStatus.setText(R.string.gc_af_stage_analyzing);
        final CrashContext ctx = mCtx;
        final CrashAnalysis analysis = mAnalysis.withPlan(mEffectivePlan);
        CSLauncherApplication.sExecutorService.execute(() -> {
            RepairReport report = RepairEngine.run(ctx, analysis, (stage, detail) -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                switch (stage) {
                    case ANALYZING: mAfStatus.setText(R.string.gc_af_stage_analyzing); break;
                    case BACKING_UP: mAfStatus.setText(R.string.gc_af_stage_backup); break;
                    case APPLYING: mAfStatus.setText(getString(R.string.gc_af_stage_applying, detail)); break;
                    case VERIFYING: mAfStatus.setText(getString(R.string.gc_af_stage_verifying, detail)); break;
                    case ROLLING_BACK: mAfStatus.setText(R.string.gc_af_stage_rollback); break;
                    default: break;
                }
            }));
            final boolean hasUndo = ctx.hasProfile() && new RepairHistory(ctx.historyFile).lastRevertible(ctx.instanceKey) != null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mFixRunning = false;
                mAfProgress.setVisibility(View.GONE);
                mAfUndo.setEnabled(true);
                mAfUndo.setVisibility(hasUndo ? View.VISIBLE : View.GONE);
                boolean canLaunch = mCtx != null && mCtx.hasProfile();
                mAfRetry.setEnabled(canLaunch);
                mAfRetry.setAlpha(canLaunch ? 1f : 0.5f);
                showReport(report);
            });
        });
    }

    private void showReport(RepairReport report) {
        StringBuilder sb = new StringBuilder();
        switch (report.status) {
            case FIXED:
                mFixApplied = true;
                sb.append(getString(R.string.gc_af_result_fixed));
                break;
            case NO_CHANGE:
                sb.append(getString(R.string.gc_af_result_no_change));
                break;
            case FAILED:
                sb.append(getString(R.string.gc_af_result_failed, report.message));
                break;
            case ROLLBACK_INCOMPLETE:
                sb.append(getString(R.string.gc_af_result_rollback_incomplete, report.problems.toString()));
                break;
            default:
                sb.append(getString(R.string.gc_af_result_skipped, report.message));
                break;
        }
        for (RepairReport.StepResult r : report.steps) {
            sb.append('\n');
            String label = stepLabel(r.step);
            if (r.ok && r.noop) sb.append(getString(R.string.gc_af_step_noop, label));
            else if (r.ok) sb.append(getString(R.string.gc_af_step_ok, r.description.isEmpty() ? label : label + " (" + r.description + ")"));
            else sb.append(getString(R.string.gc_af_step_failed, label, r.message));
        }
        mAfStatus.setVisibility(View.VISIBLE);
        mAfStatus.setText(sb);
        // One run per crash screen: a second press would only hit the retry budget. The plan text
        // explains why the button stays off.
        mAfFix.setEnabled(false);
        mAfFix.setAlpha(0.5f);
        if (report.status == RepairReport.Status.FIXED) {
            mAfRetry.setBackgroundResource(R.drawable.bg_gc_btn_primary);
            mAfRetry.setTextColor(getResources().getColor(R.color.hm_backdrop_bottom));
            mAfFix.setBackgroundResource(R.drawable.bg_gc_btn_ghost);
            mAfFix.setTextColor(getResources().getColor(R.color.hm_text));
        }
        if (mAfDetailsPane.getVisibility() == View.VISIBLE) mAfDetailsText.setText(buildDetails());
    }

    private void undoLastFix() {
        if (mFixRunning || mCtx == null) return;
        mAfUndo.setEnabled(false);
        final CrashContext ctx = mCtx;
        CSLauncherApplication.sExecutorService.execute(() -> {
            List<String> problems = RepairEngine.rollbackLast(ctx);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mAfUndo.setEnabled(true);
                if (problems.isEmpty()) {
                    mAfUndo.setVisibility(new RepairHistory(ctx.historyFile).lastRevertible(ctx.instanceKey) != null ? View.VISIBLE : View.GONE);
                    mAfStatus.setVisibility(View.VISIBLE);
                    mAfStatus.setText(R.string.gc_af_undo_done);
                    mFixApplied = false;
                } else {
                    mAfStatus.setVisibility(View.VISIBLE);
                    mAfStatus.setText(getString(R.string.gc_af_undo_failed, problems.toString()));
                }
                if (mAfDetailsPane.getVisibility() == View.VISIBLE) mAfDetailsText.setText(buildDetails());
            });
        });
    }

    private void retryLaunch() {
        if (mFixRunning) { Toast.makeText(this, R.string.gc_af_busy, Toast.LENGTH_SHORT).show(); return; }
        if (mCtx == null || !mCtx.hasProfile()) {
            Instance inst = null;
            try { inst = Instances.loadSelectedInstance(); } catch (Throwable ignored) {}
            if (inst == null || inst.getKey() == null) return;
            startActivity(ProfileShortcuts.launchIntent(this, inst.getKey()));
        } else {
            startActivity(ProfileShortcuts.launchIntent(this, mCtx.instanceKey));
        }
        finish();
    }

    private void viewCrashReport() {
        if (mCrashReport == null) { Toast.makeText(this, R.string.gc_af_no_report, Toast.LENGTH_LONG).show(); return; }
        try { Tools.openPath(this, mCrashReport, true); } catch (Throwable t) { Toast.makeText(this, R.string.gc_share_failed, Toast.LENGTH_SHORT).show(); }
    }

    private void toggleDetails() {
        boolean show = mAfDetailsPane.getVisibility() != View.VISIBLE;
        mAfDetailsPane.setVisibility(show ? View.VISIBLE : View.GONE);
        mAfDetails.setText(show ? R.string.gc_af_btn_details_hide : R.string.gc_af_btn_details);
        if (show) mAfDetailsText.setText(buildDetails());
    }

    private CharSequence buildDetails() {
        StringBuilder sb = new StringBuilder();
        if (mAnalysis == null) return getString(R.string.gc_subtitle_analyzing);
        CrashAnalysis a = mAnalysis;
        sb.append("category: ").append(a.category.name()).append('\n');
        sb.append("confidence: ").append(String.format(Locale.ROOT, "%.2f", a.confidence)).append('\n');
        sb.append("reason: ").append(a.reason).append('\n');
        sb.append("component: ").append(a.suspectComponent.isEmpty() ? "-" : a.suspectComponent).append('\n');
        sb.append("safeToAutoFix: ").append(a.safeToAutoFix).append("  confirm: ").append(a.requiresConfirmation).append("  retryLaunch: ").append(a.canRetryLaunch).append('\n');
        sb.append("signature: ").append(a.signature).append('\n');
        if (mCtx != null) {
            sb.append("profile: ").append(mCtx.instanceKey.isEmpty() ? "-" : mCtx.instanceKey)
              .append("  version: ").append(mCtx.versionId.isEmpty() ? "-" : mCtx.versionId)
              .append("  mc: ").append(mCtx.mcVersion.isEmpty() ? "?" : mCtx.mcVersion)
              .append("  loader: ").append(mCtx.loader.isEmpty() ? "vanilla" : mCtx.loader).append('\n');
            sb.append("exit: ").append(mCtx.signal ? "signal " : "code ").append(mCtx.exitCode).append('\n');
        }
        sb.append("plan:");
        if (a.plan.isEmpty()) sb.append(" (none)");
        for (RepairStep s : a.plan) sb.append("\n  - ").append(s.toString());
        if (!a.evidence.isEmpty()) {
            sb.append("\nevidence:");
            for (String e : a.evidence) sb.append("\n  ").append(e);
        }
        if (mCtx != null && mCtx.hasProfile()) {
            sb.append("\n\n").append(getString(R.string.gc_af_details_history)).append(':');
            List<RepairHistory.Attempt> attempts = new RepairHistory(mCtx.historyFile).forInstance(mCtx.instanceKey);
            if (attempts.isEmpty()) sb.append(' ').append(getString(R.string.gc_af_details_history_none));
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
            int from = Math.max(0, attempts.size() - 8);
            for (int i = attempts.size() - 1; i >= from; i--) {
                RepairHistory.Attempt at = attempts.get(i);
                sb.append("\n  ").append(df.format(new Date(at.time))).append("  ").append(at.category).append("  ").append(at.outcome);
                if (at.steps != null && !at.steps.isEmpty()) sb.append("  ").append(at.steps);
                if (at.message != null && !at.message.isEmpty()) sb.append("\n      ").append(at.message);
            }
        }
        return sb.toString();
    }

    private String stepLabel(RepairStep step) {
        switch (step.kind) {
            case DISABLE_MOD: return getString(R.string.gc_af_step_disable_mod, step.target);
            case ENABLE_MOD: return getString(R.string.gc_af_step_enable_mod, step.target);
            case INSTALL_DEPENDENCY: return getString(R.string.gc_af_step_install_dependency, step.target);
            case VERIFY_LIBRARIES: return getString(R.string.gc_af_step_verify_libraries);
            case PRUNE_JVM_ARGS: return getString(R.string.gc_af_step_prune_jvm_args, step.target);
            case ADJUST_MEMORY: return getString(R.string.gc_af_step_adjust_memory);
            case FALLBACK_RENDERER: return getString(R.string.gc_af_step_fallback_renderer);
            case SELECT_RUNTIME: return getString(R.string.gc_af_step_select_runtime, step.target);
            case DISABLE_SHADERS: return getString(R.string.gc_af_step_disable_shaders);
            case DISABLE_RESOURCE_PACK: return step.target.isEmpty() ? getString(R.string.gc_af_step_disable_resource_pack_all)
                    : getString(R.string.gc_af_step_disable_resource_pack, step.target);
            case RESET_CONFIG: return getString(R.string.gc_af_step_reset_config, step.target);
            case CHECK_NETWORK: return getString(R.string.gc_af_step_check_network);
            default: return step.toString();
        }
    }

    private static int categoryLabel(CrashCategory c) {
        switch (c) {
            case JAVA_RUNTIME_ERROR: return R.string.gc_af_cat_java_runtime_error;
            case OUT_OF_MEMORY: return R.string.gc_af_cat_out_of_memory;
            case MOD_CONFLICT: return R.string.gc_af_cat_mod_conflict;
            case MISSING_DEPENDENCY: return R.string.gc_af_cat_missing_dependency;
            case INCOMPATIBLE_MOD_VERSION: return R.string.gc_af_cat_incompatible_mod_version;
            case CORRUPTED_CONFIGURATION: return R.string.gc_af_cat_corrupted_configuration;
            case CORRUPTED_LIBRARY: return R.string.gc_af_cat_corrupted_library;
            case INVALID_JVM_ARGUMENT: return R.string.gc_af_cat_invalid_jvm_argument;
            case GRAPHICS_INITIALIZATION_ERROR: return R.string.gc_af_cat_graphics_initialization_error;
            case SHADER_ERROR: return R.string.gc_af_cat_shader_error;
            case RESOURCE_PACK_ERROR: return R.string.gc_af_cat_resource_pack_error;
            case NATIVE_LIBRARY_ERROR: return R.string.gc_af_cat_native_library_error;
            case DISK_OR_STORAGE_ERROR: return R.string.gc_af_cat_disk_or_storage_error;
            case NETWORK_DOWNLOAD_ERROR: return R.string.gc_af_cat_network_download_error;
            default: return R.string.gc_af_cat_unknown_error;
        }
    }

    private static int categoryWhy(CrashCategory c) {
        switch (c) {
            case JAVA_RUNTIME_ERROR: return R.string.gc_af_why_java_runtime_error;
            case OUT_OF_MEMORY: return R.string.gc_af_why_out_of_memory;
            case MOD_CONFLICT: return R.string.gc_af_why_mod_conflict;
            case MISSING_DEPENDENCY: return R.string.gc_af_why_missing_dependency;
            case INCOMPATIBLE_MOD_VERSION: return R.string.gc_af_why_incompatible_mod_version;
            case CORRUPTED_CONFIGURATION: return R.string.gc_af_why_corrupted_configuration;
            case CORRUPTED_LIBRARY: return R.string.gc_af_why_corrupted_library;
            case INVALID_JVM_ARGUMENT: return R.string.gc_af_why_invalid_jvm_argument;
            case GRAPHICS_INITIALIZATION_ERROR: return R.string.gc_af_why_graphics_initialization_error;
            case SHADER_ERROR: return R.string.gc_af_why_shader_error;
            case RESOURCE_PACK_ERROR: return R.string.gc_af_why_resource_pack_error;
            case NATIVE_LIBRARY_ERROR: return R.string.gc_af_why_native_library_error;
            case DISK_OR_STORAGE_ERROR: return R.string.gc_af_why_disk_or_storage_error;
            case NETWORK_DOWNLOAD_ERROR: return R.string.gc_af_why_network_download_error;
            default: return R.string.gc_af_why_unknown_error;
        }
    }

    /** Practical advice per category, reusing the existing diagnosis strings where they fit. */
    private static int categoryAdvice(CrashCategory c) {
        switch (c) {
            case JAVA_RUNTIME_ERROR: return R.string.gc_dx_java_advice;
            case OUT_OF_MEMORY: return R.string.gc_dx_oom_advice;
            case MOD_CONFLICT: return R.string.gc_dx_mixin_advice;
            case MISSING_DEPENDENCY: return R.string.gc_dx_dependency_advice;
            case INCOMPATIBLE_MOD_VERSION: return R.string.gc_dx_loader_advice;
            case CORRUPTED_CONFIGURATION: return R.string.gc_dx_config_advice;
            case CORRUPTED_LIBRARY: return R.string.gc_dx_install_advice;
            case INVALID_JVM_ARGUMENT: return R.string.gc_dx_jvmarg_advice;
            case GRAPHICS_INITIALIZATION_ERROR: return R.string.gc_dx_renderer_advice;
            case SHADER_ERROR: return R.string.gc_dx_shader_advice;
            case RESOURCE_PACK_ERROR: return R.string.gc_dx_rp_advice;
            case NATIVE_LIBRARY_ERROR: return R.string.gc_dx_native_advice;
            case DISK_OR_STORAGE_ERROR: return R.string.gc_dx_disk_advice;
            case NETWORK_DOWNLOAD_ERROR: return R.string.gc_dx_network_advice;
            default: return R.string.gc_dx_unknown_advice;
        }
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
