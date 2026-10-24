package com.craftstudio.cslauncher.game.crash.autofix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Outcome of one {@link RepairEngine#run} call. */
public final class RepairReport {
    public enum Status {
        /** At least one change was applied and verified; a relaunch is the next step. */
        FIXED,
        /** Nothing needed changing (e.g. all libraries already valid). No files touched. */
        NO_CHANGE,
        /** A step failed; every change was rolled back. */
        FAILED,
        /** A step failed and the rollback itself had problems (listed in {@link #problems}). */
        ROLLBACK_INCOMPLETE,
        /** Refused: retry budget exhausted or nothing safe to do. */
        SKIPPED
    }

    public static final class StepResult {
        public final RepairStep step;
        public final boolean ok;
        /** true when the step did not need to change anything */
        public final boolean noop;
        public final String description;
        public final String message;

        StepResult(RepairStep step, boolean ok, boolean noop, String description, String message) {
            this.step = step; this.ok = ok; this.noop = noop;
            this.description = description == null ? "" : description;
            this.message = message == null ? "" : message;
        }
    }

    public final Status status;
    public final List<StepResult> steps;
    public final String backupId;
    /** Technical, English. */
    public final String message;
    public final List<String> problems;

    RepairReport(Status status, List<StepResult> steps, String backupId, String message, List<String> problems) {
        this.status = status;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.backupId = backupId;
        this.message = message == null ? "" : message;
        this.problems = Collections.unmodifiableList(new ArrayList<>(problems == null ? Collections.<String>emptyList() : problems));
    }

    public boolean changedSomething() {
        return status == Status.FIXED;
    }
}
