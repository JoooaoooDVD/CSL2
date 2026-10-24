package com.craftstudio.cslauncher.game.crash.autofix;

import androidx.annotation.NonNull;

/**
 * One concrete repair. Lifecycle: {@link #prepare()} validates the target without touching
 * anything (may do network lookups), {@link #apply(BackupSession)} makes the change recording every
 * modification in the session, {@link #verify()} proves the change is in place.
 */
public interface RepairAction {
    /** Result of prepare(): either ready, or a reason why this step cannot run. */
    final class Readiness {
        public final boolean ready;
        /** Human readable English reason, used in the details pane and the history. */
        public final String reason;
        /** True when the situation is already fine (nothing to change) rather than an error. */
        public final boolean alreadyFine;

        private Readiness(boolean ready, String reason, boolean alreadyFine) {
            this.ready = ready; this.reason = reason == null ? "" : reason; this.alreadyFine = alreadyFine;
        }

        public static Readiness ready() { return new Readiness(true, "", false); }
        public static Readiness blocked(String reason) { return new Readiness(false, reason, false); }
        public static Readiness nothingToDo(String reason) { return new Readiness(false, reason, true); }
    }

    @NonNull RepairStep step();

    /** Short description of what will change (English, technical) - e.g. "sodium-extra-0.5.jar -> .disabled". */
    @NonNull String describe();

    @NonNull Readiness prepare() throws Exception;

    void apply(@NonNull BackupSession backup) throws Exception;

    /** @return true when the change is observable on disk / in settings. */
    boolean verify() throws Exception;
}
