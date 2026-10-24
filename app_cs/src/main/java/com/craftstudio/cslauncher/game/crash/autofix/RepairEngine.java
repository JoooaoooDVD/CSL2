package com.craftstudio.cslauncher.game.crash.autofix;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes a {@link CrashAnalysis} plan: prepare every step, back up, apply, verify, and roll
 * everything back if any step fails. One run at a time per process; retries are bounded by
 * {@link RepairHistory}. Call {@link #run} from a worker thread.
 */
public final class RepairEngine {
    private static final String TAG = "CrashAutoFix";

    public enum Stage { ANALYZING, BACKING_UP, APPLYING, VERIFYING, ROLLING_BACK, DONE }

    public interface Listener {
        /** Worker thread. {@code detail} is a short English technical string (may be empty). */
        void onStage(@NonNull Stage stage, @NonNull String detail);
    }

    private static final AtomicBoolean sRunning = new AtomicBoolean(false);

    private RepairEngine() {}

    public static boolean isRunning() {
        return sRunning.get();
    }

    /**
     * Repairs according to the analysis. Never throws; everything ends in a {@link RepairReport}.
     */
    @NonNull
    public static RepairReport run(@NonNull CrashContext ctx, @NonNull CrashAnalysis analysis, @Nullable Listener listener) {
        if (!sRunning.compareAndSet(false, true)) {
            return new RepairReport(RepairReport.Status.SKIPPED, new ArrayList<RepairReport.StepResult>(), null, "A repair is already running", null);
        }
        try {
            return runLocked(ctx, analysis, listener == null ? new Listener() { public void onStage(@NonNull Stage s, @NonNull String d) {} } : listener);
        } catch (Throwable t) {
            Log.e(TAG, "repair crashed", t);
            return new RepairReport(RepairReport.Status.FAILED, new ArrayList<RepairReport.StepResult>(), null, "Unexpected error: " + t, null);
        } finally {
            sRunning.set(false);
        }
    }

    private static RepairReport runLocked(CrashContext ctx, CrashAnalysis analysis, Listener l) {
        List<RepairReport.StepResult> results = new ArrayList<>();
        if (!ctx.hasProfile()) {
            return new RepairReport(RepairReport.Status.SKIPPED, results, null, "No profile to repair", null);
        }
        RepairHistory history = new RepairHistory(ctx.historyFile);
        if (!history.canAttempt(ctx.instanceKey, analysis.signature)) {
            return new RepairReport(RepairReport.Status.SKIPPED, results, null,
                    "Automatic repair was already tried " + RepairHistory.MAX_ATTEMPTS_PER_SIGNATURE + " times for this crash", null);
        }
        List<RepairStep> plan = history.filterPlan(ctx.instanceKey, analysis.plan);
        if (plan.isEmpty()) {
            return new RepairReport(RepairReport.Status.SKIPPED, results, null,
                    analysis.plan.isEmpty() ? "Nothing safe to change automatically" : "Every applicable fix already failed before", null);
        }

        // ---- 1. prepare ------------------------------------------------------------------------
        l.onStage(Stage.ANALYZING, "");
        List<RepairAction> ready = new ArrayList<>();
        List<String> failedKeys = new ArrayList<>();
        boolean anyBlocked = false;
        for (RepairStep step : plan) {
            RepairAction action;
            try {
                action = RepairActions.create(step, ctx);
            } catch (Throwable t) {
                results.add(new RepairReport.StepResult(step, false, false, step.toString(), "Unsupported step"));
                failedKeys.add(step.key());
                anyBlocked = true;
                continue;
            }
            l.onStage(Stage.ANALYZING, step.kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + (step.target.isEmpty() ? "" : " \u00b7 " + step.target));
            RepairAction.Readiness r;
            try {
                r = action.prepare();
            } catch (Throwable t) {
                Log.w(TAG, "prepare failed for " + step, t);
                r = RepairAction.Readiness.blocked(message(t));
            }
            if (r.ready) {
                ready.add(action);
            } else if (r.alreadyFine) {
                results.add(new RepairReport.StepResult(step, true, true, action.describe(), r.reason));
            } else {
                results.add(new RepairReport.StepResult(step, false, false, action.describe(), r.reason));
                failedKeys.add(step.key());
                anyBlocked = true;
            }
        }
        if (ready.isEmpty()) {
            if (!anyBlocked) {
                history.record(ctx.instanceKey, analysis.signature, analysis.category.name(), plan, null,
                        RepairHistory.OUTCOME_NO_CHANGE, "Nothing needed changing", null);
                l.onStage(Stage.DONE, "");
                return new RepairReport(RepairReport.Status.NO_CHANGE, results, null, "Everything checked is already in order", null);
            }
            history.record(ctx.instanceKey, analysis.signature, analysis.category.name(), plan, failedKeys,
                    RepairHistory.OUTCOME_FAILED, firstMessage(results), null);
            l.onStage(Stage.DONE, "");
            return new RepairReport(RepairReport.Status.FAILED, results, null, firstMessage(results), null);
        }

        // ---- 2. backup session -----------------------------------------------------------------
        l.onStage(Stage.BACKING_UP, "");
        BackupSession session;
        try {
            session = BackupSession.create(ctx.backupRoot, ctx.instanceKey, analysis.signature, analysis.category.name());
        } catch (Throwable t) {
            Log.e(TAG, "cannot create backup", t);
            history.record(ctx.instanceKey, analysis.signature, analysis.category.name(), plan, null,
                    RepairHistory.OUTCOME_FAILED, "Backup folder could not be created", null);
            return new RepairReport(RepairReport.Status.FAILED, results, null, "Backup folder could not be created: " + message(t), null);
        }

        // ---- 3. apply + verify each ------------------------------------------------------------
        RepairAction failed = null;
        String failure = null;
        List<RepairStep> appliedSteps = new ArrayList<>();
        for (RepairAction action : ready) {
            RepairStep step = action.step();
            l.onStage(Stage.APPLYING, action.describe());
            try {
                action.apply(session);
            } catch (Throwable t) {
                Log.w(TAG, "apply failed for " + step, t);
                failed = action;
                failure = message(t);
                break;
            }
            l.onStage(Stage.VERIFYING, action.describe());
            boolean ok;
            try {
                ok = action.verify();
            } catch (Throwable t) {
                Log.w(TAG, "verify threw for " + step, t);
                ok = false;
                failure = message(t);
            }
            if (!ok) {
                failed = action;
                if (failure == null) failure = "Verification failed after applying the change";
                break;
            }
            appliedSteps.add(step);
            results.add(new RepairReport.StepResult(step, true, false, action.describe(), ""));
        }

        // ---- 4. rollback on failure ------------------------------------------------------------
        if (failed != null) {
            results.add(new RepairReport.StepResult(failed.step(), false, false, failed.describe(), failure));
            failedKeys.add(failed.step().key());
            l.onStage(Stage.ROLLING_BACK, "");
            List<String> problems = session.rollback(new BackupSession.ValueRestorer() {
                @Override public void restore(String scope, String key, String oldValue) throws java.io.IOException {
                    RepairActions.restoreValue(ctx, scope, key, oldValue);
                }
            });
            String outcomeMsg = failure;
            history.record(ctx.instanceKey, analysis.signature, analysis.category.name(), plan, failedKeys,
                    RepairHistory.OUTCOME_FAILED, outcomeMsg, session.isEmpty() ? null : session.dir().getName());
            l.onStage(Stage.DONE, "");
            if (problems.isEmpty()) {
                return new RepairReport(RepairReport.Status.FAILED, results, session.dir().getName(), failure, null);
            }
            return new RepairReport(RepairReport.Status.ROLLBACK_INCOMPLETE, results, session.dir().getName(),
                    failure + " (rollback problems: " + problems.size() + ")", problems);
        }

        // ---- 5. success --------------------------------------------------------------------------
        try { session.markCompleted(); } catch (Throwable ignored) {}
        if (appliedSteps.isEmpty()) {
            // only no-op steps succeeded (cannot really happen since ready was non-empty, but be exact)
            history.record(ctx.instanceKey, analysis.signature, analysis.category.name(), plan, null,
                    RepairHistory.OUTCOME_NO_CHANGE, "Nothing needed changing", null);
            l.onStage(Stage.DONE, "");
            return new RepairReport(RepairReport.Status.NO_CHANGE, results, null, "Everything checked is already in order", null);
        }
        history.record(ctx.instanceKey, analysis.signature, analysis.category.name(), appliedSteps, failedKeys,
                RepairHistory.OUTCOME_APPLIED, "Applied " + appliedSteps.size() + " change(s)", session.isEmpty() ? null : session.dir().getName());
        l.onStage(Stage.DONE, "");
        return new RepairReport(RepairReport.Status.FIXED, results, session.dir().getName(), "Applied " + appliedSteps.size() + " change(s)", null);
    }

    /**
     * Reverts the most recent applied repair of this profile. Worker thread.
     * @return empty list on success, otherwise the problems
     */
    @NonNull
    public static List<String> rollbackLast(@NonNull CrashContext ctx) {
        List<String> problems = new ArrayList<>();
        RepairHistory history = new RepairHistory(ctx.historyFile);
        RepairHistory.Attempt a = history.lastRevertible(ctx.instanceKey);
        if (a == null) { problems.add("Nothing to undo"); return problems; }
        BackupSession s = BackupSession.open(new File(ctx.backupRoot, a.backupId));
        if (s == null) { problems.add("Backup " + a.backupId + " is missing"); return problems; }
        problems.addAll(s.rollback(new BackupSession.ValueRestorer() {
            @Override public void restore(String scope, String key, String oldValue) throws java.io.IOException {
                RepairActions.restoreValue(ctx, scope, key, oldValue);
            }
        }));
        if (problems.isEmpty()) history.setOutcome(a, RepairHistory.OUTCOME_ROLLED_BACK, "Reverted by the player");
        return problems;
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t.getClass().getSimpleName();
        return m;
    }

    private static String firstMessage(List<RepairReport.StepResult> results) {
        for (RepairReport.StepResult r : results) if (!r.ok && !r.message.isEmpty()) return r.message;
        return "Could not apply the fix";
    }
}
