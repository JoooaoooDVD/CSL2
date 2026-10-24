package com.craftstudio.cslauncher.game.crash.autofix;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent list of repair attempts (history.json). Used for the details pane, to find the
 * backup to roll back, and to bound retries: the same step is never re-run after it failed, and a
 * crash signature gets at most {@link #MAX_ATTEMPTS_PER_SIGNATURE} automatic attempts per profile.
 */
public final class RepairHistory {
    public static final int MAX_ATTEMPTS_PER_SIGNATURE = 2;
    public static final int MAX_ENTRIES = 200;

    public static final String OUTCOME_APPLIED = "applied";        // changes made, verified, waiting for relaunch
    public static final String OUTCOME_FAILED = "failed";          // could not apply, rolled back
    public static final String OUTCOME_NO_CHANGE = "no_change";    // nothing to change (e.g. files already valid)
    public static final String OUTCOME_ROLLED_BACK = "rolled_back"; // user or engine reverted it later
    public static final String OUTCOME_RELAPSED = "relapsed";      // applied, but the same crash came back

    public static final class Attempt {
        public long time;
        public String instanceKey;
        public String signature;
        public String category;
        public List<String> steps = new ArrayList<>();
        public List<String> failedSteps = new ArrayList<>();
        public String outcome;
        public String message;
        public String backupId;
    }

    private static final class Store {
        List<Attempt> attempts = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File mFile;
    private final Store mStore;

    public RepairHistory(File file) {
        mFile = file;
        Store s = null;
        if (file.isFile()) {
            try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                s = GSON.fromJson(r, Store.class);
            } catch (Exception ignored) {
            }
        }
        if (s == null || s.attempts == null) s = new Store();
        mStore = s;
    }

    public List<Attempt> all() { return Collections.unmodifiableList(mStore.attempts); }

    public List<Attempt> forInstance(String instanceKey) {
        List<Attempt> out = new ArrayList<>();
        for (Attempt a : mStore.attempts) if (eq(a.instanceKey, instanceKey)) out.add(a);
        return out;
    }

    /** Automatic attempts already spent on this crash signature for this profile (any outcome except no-change). */
    public int attemptsFor(String instanceKey, String signature) {
        int n = 0;
        for (Attempt a : mStore.attempts) {
            if (eq(a.instanceKey, instanceKey) && eq(a.signature, signature) && !OUTCOME_NO_CHANGE.equals(a.outcome)) n++;
        }
        return n;
    }

    public boolean canAttempt(String instanceKey, String signature) {
        return attemptsFor(instanceKey, signature) < MAX_ATTEMPTS_PER_SIGNATURE;
    }

    /** Step keys that already failed (or relapsed) for this profile - these are not tried again. */
    public List<String> exhaustedSteps(String instanceKey) {
        List<String> out = new ArrayList<>();
        for (Attempt a : mStore.attempts) {
            if (!eq(a.instanceKey, instanceKey)) continue;
            if (a.failedSteps != null) for (String s : a.failedSteps) if (!out.contains(s)) out.add(s);
            if (OUTCOME_RELAPSED.equals(a.outcome) && a.steps != null) for (String s : a.steps) if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    /** Removes steps that already failed before. */
    public List<RepairStep> filterPlan(String instanceKey, List<RepairStep> plan) {
        List<String> ex = exhaustedSteps(instanceKey);
        List<RepairStep> out = new ArrayList<>();
        for (RepairStep s : plan) if (!ex.contains(s.key())) out.add(s);
        return out;
    }

    /** The most recent attempt whose backup can still be reverted, or null. */
    public Attempt lastRevertible(String instanceKey) {
        for (int i = mStore.attempts.size() - 1; i >= 0; i--) {
            Attempt a = mStore.attempts.get(i);
            if (!eq(a.instanceKey, instanceKey)) continue;
            if (a.backupId == null) continue;
            if (OUTCOME_APPLIED.equals(a.outcome) || OUTCOME_RELAPSED.equals(a.outcome)) return a;
        }
        return null;
    }

    /**
     * Called when a new crash arrives: if the previous attempt for the same signature was "applied"
     * and the game crashed the same way again, mark it relapsed so its steps are not retried.
     * @return true when something was marked
     */
    public boolean markRelapse(String instanceKey, String signature) {
        boolean changed = false;
        for (int i = mStore.attempts.size() - 1; i >= 0; i--) {
            Attempt a = mStore.attempts.get(i);
            if (eq(a.instanceKey, instanceKey) && eq(a.signature, signature)) {
                if (OUTCOME_APPLIED.equals(a.outcome)) { a.outcome = OUTCOME_RELAPSED; changed = true; }
                break;
            }
        }
        if (changed) save();
        return changed;
    }

    public void setOutcome(Attempt a, String outcome, String message) {
        a.outcome = outcome;
        if (message != null) a.message = message;
        save();
    }

    public Attempt record(String instanceKey, String signature, String category, List<RepairStep> steps,
                          List<String> failedSteps, String outcome, String message, String backupId) {
        Attempt a = new Attempt();
        a.time = System.currentTimeMillis();
        a.instanceKey = instanceKey;
        a.signature = signature;
        a.category = category;
        for (RepairStep s : steps) a.steps.add(s.key());
        if (failedSteps != null) a.failedSteps.addAll(failedSteps);
        a.outcome = outcome;
        a.message = message;
        a.backupId = backupId;
        mStore.attempts.add(a);
        while (mStore.attempts.size() > MAX_ENTRIES) mStore.attempts.remove(0);
        save();
        return a;
    }

    private void save() {
        try {
            BackupSession.writeAtomic(mFile, GSON.toJson(mStore));
        } catch (IOException ignored) {
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
